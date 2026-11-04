package community.propwash.crossserver;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

/**
 * Limbo: the Paper server for players who can't join the modded ones (no Fabric, the wrong game version,
 * missing or wrong mods). When a server refuses a player while they connect (it kicks them during the
 * join, also their first one into the network), they go to limbo instead of being disconnected, and are
 * told why, just below limbo's welcome (which has the links to set up). A player refused while already on limbo just gets
 * the message.
 * Limbo is the server named "limbo".
 */
final class Limbo {
    private static final String LIMBO = "limbo";
    private static final TextColor PROPWASH = TextColor.color(0xFFBB00);
    // Limbo's welcome (a datapack, servers/limbo/server.toml) shows on its first tick after a player
    // arrives; the reason waits this long after the arrival so it comes below the welcome.
    private static final long AFTER_WELCOME_MS = 1000;

    private final ProxyServer proxy;
    private final Object plugin;
    private final Logger logger;
    // Reasons for players on their way to limbo, sent once they are there.
    private final Map<UUID, Component> pending = new ConcurrentHashMap<>();

    Limbo(ProxyServer proxy, Object plugin, Logger logger) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.logger = logger;
    }

    void register() {
        proxy.getEventManager().register(plugin, this);
    }

    @Subscribe
    public void onKicked(KickedFromServerEvent event) {
        RegisteredServer from = event.getServer();
        RegisteredServer limbo = proxy.getServer(LIMBO).orElse(null);
        Player player = event.getPlayer();
        // Refused while joining: switching servers (kickedDuringServerConnect), or on first joining the
        // network, when they aren't on any server yet (and Velocity would try the next server instead).
        boolean joining = event.kickedDuringServerConnect() || player.getCurrentServer().isEmpty();
        if (limbo == null || !joining || from.equals(limbo)) {
            return;
        }
        Component reason = event.getServerKickReason().orElse(Component.text("the server refused the connection"));
        logger.info("{} can't join {}, to limbo: {}", player.getUsername(), from.getServerInfo().getName(),
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(reason));
        Component message = message(from.getServerInfo().getName(), reason);
        boolean onLimbo = player.getCurrentServer().map(s -> s.getServer().equals(limbo)).orElse(false);
        if (onLimbo) {
            event.setResult(KickedFromServerEvent.Notify.create(message));
        } else {
            pending.put(player.getUniqueId(), message);
            event.setResult(KickedFromServerEvent.RedirectPlayer.create(limbo));
        }
    }

    @Subscribe
    public void onArrive(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        boolean onLimbo = player.getCurrentServer().map(s -> s.getServerInfo().getName().equals(LIMBO)).orElse(false);
        Component message = onLimbo ? pending.remove(player.getUniqueId()) : null;
        if (message != null) {
            proxy.getScheduler().buildTask(plugin, () -> player.sendMessage(message))
                    .delay(AFTER_WELCOME_MS, TimeUnit.MILLISECONDS).schedule();
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    // Only the reason: limbo's own welcome (servers/limbo/server.toml), just above it, has what players
    // need and the links, so this doesn't repeat it.
    private static Component message(String server, Component reason) {
        return Component.text("[Propwash] ", PROPWASH, TextDecoration.BOLD)
                .append(Component.text("You can't join " + server + " yet: ", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
                .append(reason.colorIfAbsent(NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false));
    }
}
