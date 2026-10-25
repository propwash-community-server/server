package community.propwash.stats;

import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

/**
 * Raw player activity for graphs later (a dashboard reads these files; nothing is shown in game).
 * One JSON object per line in plugins/stats/events-YYYY-MM.jsonl (UTC months), never rewritten:
 * <ul>
 *   <li>{"t":..., "e":"join", "uuid":..., "name":...}: joined the network</li>
 *   <li>{"t":..., "e":"server", "uuid":..., "name":..., "server":..., "from":...}: arrived on a server
 *       (from: the one before, absent right after joining)</li>
 *   <li>{"t":..., "e":"leave", "uuid":..., "name":..., "server":...}: left the network</li>
 *   <li>{"t":..., "e":"online", "total":..., "servers":{"hub":2,...}}: everyone online, every 5 minutes</li>
 *   <li>{"t":..., "e":"start"} and {"t":..., "e":"stop"}: the proxy started or stopped (a stop ends
 *       every session; after a crash there's a start without a stop)</li>
 * </ul>
 * t is ISO-8601 UTC. No IP addresses. Vanished players are counted like everyone else.
 */
@Plugin(id = "stats", name = "Stats", version = "1.0.0",
        description = "Records joins, server switches, leaves and players online for graphs")
public final class Stats {
    private static final long SAMPLE_MINUTES = 5;

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path folder;

    @Inject
    public Stats(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.folder = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        write(event("start"));
        proxy.getScheduler().buildTask(this, this::sample)
                .delay(SAMPLE_MINUTES, TimeUnit.MINUTES).repeat(SAMPLE_MINUTES, TimeUnit.MINUTES).schedule();
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        write(event("stop"));
    }

    @Subscribe(order = PostOrder.LAST)
    public void onJoin(PostLoginEvent event) {
        write(player("join", event.getPlayer()));
    }

    @Subscribe(order = PostOrder.LAST)
    public void onServer(ServerConnectedEvent event) {
        JsonObject o = player("server", event.getPlayer());
        o.addProperty("server", event.getServer().getServerInfo().getName());
        event.getPreviousServer().ifPresent(s -> o.addProperty("from", s.getServerInfo().getName()));
        write(o);
    }

    @Subscribe(order = PostOrder.LAST)
    public void onLeave(DisconnectEvent event) {
        if (event.getLoginStatus() != DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN) {
            return; // never joined (refused, banned, gone during login): no join was written either
        }
        JsonObject o = player("leave", event.getPlayer());
        event.getPlayer().getCurrentServer().ifPresent(s -> o.addProperty("server", s.getServerInfo().getName()));
        write(o);
    }

    private void sample() {
        JsonObject o = event("online");
        o.addProperty("total", proxy.getPlayerCount());
        JsonObject servers = new JsonObject();
        for (RegisteredServer server : proxy.getAllServers()) {
            servers.addProperty(server.getServerInfo().getName(), server.getPlayersConnected().size());
        }
        o.add("servers", servers);
        write(o);
    }

    private static JsonObject event(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("t", Instant.now().toString());
        o.addProperty("e", type);
        return o;
    }

    private static JsonObject player(String type, Player player) {
        JsonObject o = event(type);
        o.addProperty("uuid", player.getUniqueId().toString());
        o.addProperty("name", player.getUsername());
        return o;
    }

    private synchronized void write(JsonObject o) {
        Path file = folder.resolve("events-" + YearMonth.now(ZoneOffset.UTC) + ".jsonl");
        try {
            Files.createDirectories(folder);
            Files.writeString(file, o + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.warn("Couldn't write to {}", file, e);
        }
    }
}
