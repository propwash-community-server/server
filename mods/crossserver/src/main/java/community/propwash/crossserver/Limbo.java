package community.propwash.crossserver;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.slf4j.Logger;

/**
 * Limbo: the Paper server for players who can't join the modded ones (no Fabric, the wrong game version,
 * missing or wrong mods). When a server refuses a player while they connect (it kicks them during the
 * join, also their first one into the network), they go to limbo instead of being disconnected, and are
 * told why, with the links to set up. A player refused while already on limbo just gets the message.
 * Limbo is the server named "limbo".
 */
final class Limbo {
    private static final String LIMBO = "limbo";
    private static final TextColor PROPWASH = TextColor.color(0xFFBB00);

    private final ProxyServer proxy;
    private final Object plugin;
    private final Logger logger;

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
        event.setResult(onLimbo ? KickedFromServerEvent.Notify.create(message)
                : KickedFromServerEvent.RedirectPlayer.create(limbo, message));
    }

    private static Component message(String server, Component reason) {
        return Component.text("[Propwash] ", PROPWASH, TextDecoration.BOLD)
                .append(Component.text("You can't join " + server + " yet: ", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
                .append(reason.colorIfAbsent(NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false))
                .append(Component.newline())
                .append(Component.text("You need Minecraft 26.3 with Fabric and the Propwash mods. Everything you need: ", NamedTextColor.GRAY)
                        .decoration(TextDecoration.BOLD, false))
                .append(link("propwashfpv.com", "https://propwashfpv.com/"))
                .append(Component.text(", help on ", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
                .append(link("Discord", "https://discord.propwashfpv.com"))
                .append(Component.text(".", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false));
    }

    private static Component link(String text, String url) {
        return Component.text(text, NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false)
                .decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text(url.replaceFirst("https://", ""))));
    }
}
