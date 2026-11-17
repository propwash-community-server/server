package community.propwash.crossserver;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;

/**
 * Limbo: the Paper server for players who can't join the modded ones (no Fabric, the wrong game version,
 * missing or wrong mods). When a server refuses a player while they connect (it kicks them during the
 * join, also their first one into the network), they go to limbo instead of being disconnected, and are
 * told why, just below limbo's welcome (which has the links to set up). A player refused while already on limbo just gets
 * the message. A server that doesn't answer at all (starting, restarting, down) hasn't refused anyone:
 * players already on a server stay there and are told to try again.
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
        // No kick reason: the server never answered (it's starting, restarting or down), it didn't
        // refuse them. Players already on a server stay there and are told to try again; limbo is only
        // for players a server turns away.
        if (event.getServerKickReason().isEmpty() && player.getCurrentServer().isPresent()) {
            logger.info("{} can't reach {}, staying on {}", player.getUsername(), from.getServerInfo().getName(),
                    player.getCurrentServer().get().getServerInfo().getName());
            event.setResult(KickedFromServerEvent.Notify.create(unreachable(from.getServerInfo().getName())));
            return;
        }
        // Fabric servers send the reason as a translation with a fallback text ("... %s ..."): the
        // client only fills in the arguments for keys it knows, and the log didn't at all, so players
        // saw "%s" where the version should be. Filled in here, once, for both.
        Component reason = withFallbacks(event.getServerKickReason().orElse(Component.text("the server refused the connection")));
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

    /**
     * The component with every translation that has a fallback text replaced by that text, its
     * arguments filled in (%s in order, %1$s by position, %% a percent sign). Translations without a
     * fallback stay as they are.
     */
    static Component withFallbacks(Component component) {
        List<Component> children = new ArrayList<>();
        for (Component child : component.children()) {
            children.add(withFallbacks(child));
        }
        if (component instanceof TranslatableComponent translatable && translatable.fallback() != null) {
            List<Component> args = new ArrayList<>();
            for (TranslationArgument arg : translatable.arguments()) {
                args.add(withFallbacks(arg.asComponent()));
            }
            TextComponent.Builder text = Component.text().style(translatable.style());
            String fallback = translatable.fallback();
            Matcher m = FORMAT.matcher(fallback);
            int at = 0;
            int next = 0;
            while (m.find()) {
                text.append(Component.text(fallback.substring(at, m.start())));
                if (m.group().equals("%%")) {
                    text.append(Component.text("%"));
                } else {
                    int index = m.group(1) != null ? Integer.parseInt(m.group(1)) - 1 : next++;
                    text.append(index >= 0 && index < args.size() ? args.get(index) : Component.text(m.group()));
                }
                at = m.end();
            }
            text.append(Component.text(fallback.substring(at)));
            return text.append(children).build();
        }
        return component.children(children);
    }

    private static final Pattern FORMAT = Pattern.compile("%(?:(\\d+)\\$)?s|%%");

    private static Component unreachable(String server) {
        return Component.text("[Propwash] ", PROPWASH, TextDecoration.BOLD)
                .append(Component.text(server + " isn't up right now (it may be starting or restarting). Try again in a moment.",
                        NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false));
    }

    // Only the reason: limbo's own welcome (servers/limbo/server.toml), just above it, has what players
    // need and the links, so this doesn't repeat it.
    private static Component message(String server, Component reason) {
        return Component.text("[Propwash] ", PROPWASH, TextDecoration.BOLD)
                .append(Component.text("You can't join " + server + " yet: ", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
                .append(reason.colorIfAbsent(NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false));
    }
}
