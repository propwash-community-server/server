package community.propwash.crossserver;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

/**
 * /afk for everyone, the same on every server: toggles [AFK] after the name in the tab list (TAB
 * placeholder %propwash-afk%), without a chat message. It ends when you move (the proxy can't see
 * movement, so every few seconds it asks the server over rcon where each AFK player is), run /afk
 * again or leave. Anyone who runs a command naming an AFK player (/msg, /tp, /tpa, ...) is told so,
 * unless they can't see that player (vanished); the command still runs.
 */
final class Afk {
    static final String PLACEHOLDER = "%propwash-afk%";
    private static final long CHECK_SECONDS = 3;
    /** Farther than this (blocks) from where they were counts as moving. */
    private static final double MOVED = 0.3;
    private static final Pattern POS = Pattern.compile("\\[(-?[\\d.E-]+)d, (-?[\\d.E-]+)d, (-?[\\d.E-]+)d]");

    /** Runs a console command on a server (by name) and returns its answer. */
    interface Console {
        String run(String server, String command) throws IOException;
    }

    /** Where an AFK player was last seen: their server, and the position there (null: not known yet). */
    private record Seen(String server, double[] pos) {
    }

    private final ProxyServer proxy;
    private final Object plugin;
    private final Logger logger;
    private final Console console;
    /** Whether the first player may see the second (not vanished, or allowed to see vanished players). */
    private final BiPredicate<Player, Player> visible;
    private final Map<UUID, Seen> afk = new ConcurrentHashMap<>();

    Afk(ProxyServer proxy, Object plugin, Logger logger, Console console, BiPredicate<Player, Player> visible) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.logger = logger;
        this.console = console;
        this.visible = visible;
    }

    void register() {
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("afk").plugin(plugin).build(),
                (SimpleCommand) invocation -> {
                    if (invocation.source() instanceof Player player) {
                        toggle(player);
                    }
                });
        proxy.getEventManager().register(plugin, this);
        proxy.getScheduler().buildTask(plugin, this::checkMoved).repeat(CHECK_SECONDS, TimeUnit.SECONDS).schedule();
        // TAB asks on its own every half second.
        if (proxy.getPluginManager().isLoaded("tab")) {
            me.neznamy.tab.api.TabAPI.getInstance().getPlaceholderManager().registerPlayerPlaceholder(PLACEHOLDER, 500,
                    p -> afk.containsKey(p.getUniqueId()) ? " <gray>[AFK]</gray>" : "");
        }
    }

    private void toggle(Player player) {
        if (afk.remove(player.getUniqueId()) != null) {
            back(player);
        } else {
            afk.put(player.getUniqueId(), new Seen(null, null));
            player.sendMessage(Component.text("You're AFK until you move or /afk again.", NamedTextColor.GRAY));
        }
    }

    private static void back(Player player) {
        player.sendMessage(Component.text("You're back.", NamedTextColor.GRAY));
    }

    /** Ends AFK for whoever moved since the last check; the first check (or a new server) just notes where they are. */
    private void checkMoved() {
        for (UUID id : afk.keySet()) {
            Player player = proxy.getPlayer(id).orElse(null);
            String server = player == null ? null : player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(null);
            if (server == null) {
                continue;
            }
            double[] pos;
            try {
                pos = position(console.run(server, "data get entity " + player.getUsername() + " Pos"));
            } catch (IOException e) {
                logger.warn("AFK: can't ask {} where {} is", server, player.getUsername(), e);
                continue;
            }
            if (pos == null) {
                continue;
            }
            Seen seen = afk.get(id);
            if (seen == null) {
                continue; // they ended it meanwhile
            }
            if (server.equals(seen.server()) && seen.pos() != null && distance(seen.pos(), pos) > MOVED) {
                afk.remove(id);
                back(player);
            } else if (!server.equals(seen.server()) || seen.pos() == null) {
                afk.replace(id, seen, new Seen(server, pos));
            }
        }
    }

    /** The x, y, z in "<name> has the following entity data: [1.5d, 64.0d, -3.2d]", or null. */
    private static double[] position(String answer) {
        Matcher m = POS.matcher(answer == null ? "" : answer);
        if (!m.find()) {
            return null;
        }
        return new double[] {Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3))};
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Subscribe
    public void onCommand(CommandExecuteEvent event) {
        if (afk.isEmpty() || !(event.getCommandSource() instanceof Player sender)) {
            return;
        }
        String[] words = event.getCommand().trim().split("\\s+");
        for (int i = 1; i < words.length; i++) {
            Player target = proxy.getPlayer(words[i]).orElse(null);
            if (target != null && target != sender && afk.containsKey(target.getUniqueId()) && visible.test(sender, target)) {
                sender.sendMessage(Component.text(target.getUsername() + " is AFK.", NamedTextColor.GRAY));
                return;
            }
        }
    }

    @Subscribe
    public void onLeave(DisconnectEvent event) {
        afk.remove(event.getPlayer().getUniqueId());
    }
}
