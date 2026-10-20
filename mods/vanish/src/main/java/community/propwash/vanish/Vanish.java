package community.propwash.vanish;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.command.CommandExecuteEvent.CommandResult;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent.ChatResult;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.integration.VanishIntegration;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.MetaNode;
import net.luckperms.api.node.types.PermissionNode;
import org.slf4j.Logger;

/**
 * Network-wide /v: hidden on every server (each server's own vanish, switched on as you arrive through
 * LuckPerms), in the tab list (TAB), the server list, join messages (NetworkJoinMessages) and chat
 * (Carbon, which asks this plugin as a Predicate of UUIDs). /v needs propwash.vanish;
 * propwash.vanish.see sees vanished players. Going into /v posts the usual leave message and coming out
 * the usual join message (NetworkJoinMessages' own /njoinspoof), so nobody can tell; staff are told
 * who vanished. On Paper servers (hub), which have no vanish boss bar of their own, it shows one.
 */
@Plugin(id = "vanish", name = "Vanish", version = "1.2.0",
        description = "Network-wide /v",
        dependencies = {@Dependency(id = "luckperms"), @Dependency(id = "tab", optional = true),
                @Dependency(id = "networkjoinmessages", optional = true)})
public final class Vanish implements Predicate<UUID> {
    private static final String USE = "propwash.vanish";
    private static final String SEE = "propwash.vanish.see";

    private final ProxyServer proxy;
    private final Logger logger;
    // Who is vanished, kept across restarts: one UUID per line.
    private final Path dataDirectory;
    private final Path stateFile;
    // /v auto on|off: uuid=true (always arrive vanished) or false (always visible); not listed, as you left.
    private final Path autoFile;
    private final Properties auto = new Properties();
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    // Servers running Paper (EssentialsX /vanish); the rest run Fabric (the Vanish mod).
    private final Set<String> paperServers;
    // The vanish mod on fpv and plots shows its own boss bar; this one is for the Paper servers.
    private final BossBar bossBar = BossBar.bossBar(
            Component.text("You are vanished. /v to show yourself.", NamedTextColor.GRAY),
            1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);

    @Inject
    public Vanish(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) throws IOException {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.stateFile = dataDirectory.resolve("vanished");
        this.autoFile = dataDirectory.resolve("auto.properties");
        if (Files.exists(autoFile)) {
            try (InputStream in = Files.newInputStream(autoFile)) {
                auto.load(in);
            }
        }
        if (Files.exists(stateFile)) {
            Files.readAllLines(stateFile).stream().filter(l -> !l.isBlank()).map(UUID::fromString).forEach(vanished::add);
        }
        Properties config = new Properties();
        Path configFile = dataDirectory.resolve("config.properties");
        if (Files.exists(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                config.load(in);
            }
        }
        this.paperServers = Arrays.stream(config.getProperty("paper-servers", "hub").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    /** For Carbon: whether the player is vanished. */
    @Override
    public boolean test(UUID uuid) {
        return vanished.contains(uuid);
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        // So /v shows up for those who may use it; running it is handled in onCommand.
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("v").aliases("vanish").plugin(this).build(),
                new SimpleCommand() {
                    @Override
                    public void execute(Invocation invocation) {
                    }

                    @Override
                    public boolean hasPermission(Invocation invocation) {
                        return invocation.source().hasPermission(USE);
                    }

                    // /v auto on|off (also /vanish ...).
                    @Override
                    public java.util.List<String> suggest(Invocation invocation) {
                        String[] args = invocation.arguments();
                        if (args.length <= 1) {
                            return "auto".startsWith(args.length == 0 ? "" : args[0].toLowerCase()) ? java.util.List.of("auto") : java.util.List.of();
                        }
                        if (args.length == 2 && args[0].equalsIgnoreCase("auto")) {
                            return java.util.stream.Stream.of("on", "off").filter(o -> o.startsWith(args[1].toLowerCase())).toList();
                        }
                        return java.util.List.of();
                    }
                });
        // TAB asks on its own and notices a change within half a second.
        if (proxy.getPluginManager().isLoaded("tab")) {
            new VanishIntegration("vanish") {
                @Override
                public boolean isVanished(TabPlayer player) {
                    return vanished.contains(player.getUniqueId());
                }

                @Override
                public boolean canSee(TabPlayer viewer, TabPlayer target) {
                    return !vanished.contains(target.getUniqueId())
                            || proxy.getPlayer(viewer.getUniqueId()).map(p -> p.hasPermission(SEE)).orElse(false);
                }
            }.register();
        }
    }

    // /v and /vanish (without arguments) toggle here; the server gets its own vanish command over rcon.
    @Subscribe
    public void onCommand(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player player) || !player.hasPermission(USE)) {
            return;
        }
        String command = event.getCommand().trim();
        String[] words = command.split("\\s+", 2);
        if (!words[0].equalsIgnoreCase("v") && !words[0].equalsIgnoreCase("vanish")) {
            return;
        }
        if (words.length > 1 && words[1].trim().toLowerCase().startsWith("auto")) {
            event.setResult(CommandResult.denied());
            auto(player, words[1].trim().split("\\s+"));
            return;
        }
        if (words.length > 1) {
            // /vanish check and the like: the server's own vanish command, passed on as it is.
            event.setResult(CommandResult.forwardToServer());
            return;
        }
        // Not passed on with the command changed: the client signs commands, and SignedVelocity's
        // rewrite on the server mangled it ("anish on"). The server gets its own vanish over rcon instead.
        event.setResult(CommandResult.denied());
        boolean on = !vanished.contains(player.getUniqueId());
        if (on) {
            // The leave message first, while NetworkJoinMessages still lets the player's messages through.
            spoof(player, "leave").whenComplete((ok, error) -> {
                set(player, true);
                showBossBar(player);
            });
        } else {
            // And the join message once it does again.
            set(player, false).whenComplete((ok, error) -> spoof(player, "join"));
            showBossBar(player);
        }
        serverVanish(player, on);
        player.sendMessage(Component.text(on
                ? "You are vanished on every server, and everyone saw you leave. Public chat is off; # or /sc for staff chat. /v again to show yourself."
                : "You are visible again, and everyone saw you join.", NamedTextColor.GRAY));
        // Staff see vanished players, so they're told, and don't call them out by accident.
        Component notice = Component.text(player.getUsername() + (on ? " vanished." : " is visible again."), NamedTextColor.GRAY);
        for (Player other : proxy.getAllPlayers()) {
            if (other != player && other.hasPermission(SEE)) {
                other.sendMessage(notice);
            }
        }
    }

    /**
     * The server the player is on hides or shows them now, as a console command over rcon: EssentialsX
     * (Paper) takes /vanish <player> on|off, the Vanish mod (Fabric) /vanish on|off <player>. The rcon
     * ports and password are mods/crossserver's, written next to this plugin's folder by the deploy.
     */
    private void serverVanish(Player player, boolean on) {
        player.getCurrentServer().ifPresent(connection -> {
            String server = connection.getServerInfo().getName();
            String state = on ? "on" : "off";
            String command = paperServers.contains(server)
                    ? "vanish " + player.getUsername() + " " + state
                    : "vanish " + state + " " + player.getUsername();
            proxy.getScheduler().buildTask(this, () -> {
                try {
                    Path crossserver = dataDirectory.getParent().resolve("crossserver");
                    Properties ports = new Properties();
                    try (InputStream in = Files.newInputStream(crossserver.resolve("rcon.properties"))) {
                        ports.load(in);
                    }
                    String port = ports.getProperty(server);
                    if (port == null) {
                        throw new IOException("no rcon port for " + server);
                    }
                    Rcon.run(Integer.parseInt(port.trim()), Files.readString(crossserver.resolve("rcon.password")).strip(), command);
                } catch (IOException | RuntimeException e) {
                    logger.warn("Vanish {} for {} on {} failed", state, player.getUsername(), server, e);
                    player.sendMessage(Component.text("The server didn't take the vanish change; /v again to retry.", NamedTextColor.RED));
                }
            }).schedule();
        });
    }

    /** /v auto [on|off]: whether this staff member arrives vanished. */
    private void auto(Player player, String[] args) {
        String key = player.getUniqueId().toString();
        if (args.length >= 2 && (args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
            auto.setProperty(key, Boolean.toString(args[1].equalsIgnoreCase("on")));
            try {
                Files.createDirectories(autoFile.getParent());
                try (var out = Files.newOutputStream(autoFile)) {
                    auto.store(out, "/v auto: true = always arrive vanished, false = always arrive visible");
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else if (args.length >= 2) {
            player.sendMessage(Component.text("/v auto on, /v auto off, or /v auto to see yours.", NamedTextColor.GRAY));
            return;
        }
        String value = auto.getProperty(key);
        player.sendMessage(Component.text(value == null
                ? "Auto-vanish isn't set: you arrive the way you left (vanished or not). /v auto on or /v auto off to choose."
                : Boolean.parseBoolean(value)
                        ? "Auto-vanish is on: you always arrive vanished, without a join message. /v auto off to change."
                        : "Auto-vanish is off: you always arrive visible, with your join message. /v auto on to change.",
                NamedTextColor.GRAY));
    }

    // Applied when leaving: whether the next join is silent and vanished is settled before the player is back.
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        String value = auto.getProperty(player.getUniqueId().toString());
        if (value != null && Boolean.parseBoolean(value) != vanished.contains(player.getUniqueId())) {
            set(player, Boolean.parseBoolean(value));
        }
    }

    /** NetworkJoinMessages' fake join or leave message for the player, the same as a real one. */
    private java.util.concurrent.CompletableFuture<Boolean> spoof(Player player, String which) {
        if (!proxy.getPluginManager().isLoaded("networkjoinmessages")) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        return proxy.getCommandManager().executeAsync(player, "njoinspoof " + which);
    }

    private boolean onPaper(Player player) {
        return player.getCurrentServer().map(s -> paperServers.contains(s.getServerInfo().getName())).orElse(false);
    }

    private void showBossBar(Player player) {
        if (vanished.contains(player.getUniqueId()) && onPaper(player)) {
            player.showBossBar(bossBar);
        } else {
            player.hideBossBar(bossBar);
        }
    }

    @Subscribe
    public void onServerConnected(ServerPostConnectEvent event) {
        showBossBar(event.getPlayer());
    }

    private java.util.concurrent.CompletableFuture<Void> set(Player player, boolean on) {
        UUID uuid = player.getUniqueId();
        if (on) {
            vanished.add(uuid);
        } else {
            vanished.remove(uuid);
        }
        save();
        // Read by each server when the player arrives: the Vanish mod (Fabric) and EssentialsX (Paper,
        // with allow-silent-join-quit) vanish them from the first tick; NetworkJoinMessages doesn't
        // announce them.
        logger.info("{} is {}", player.getUsername(), on ? "vanished" : "visible again");
        return LuckPermsProvider.get().getUserManager().modifyUser(uuid, user -> {
            user.data().clear(NodeType.META.predicate(n -> n.getMetaKey().equals("vanish_on_join")));
            user.data().add(MetaNode.builder("vanish_on_join", Boolean.toString(on)).build());
            for (String node : new String[]{"essentials.silentjoin.vanish", "networkjoinmessages.silent"}) {
                user.data().clear(n -> n.getKey().equals(node));
                user.data().add(PermissionNode.builder(node).value(on).build());
            }
        });
    }

    private void save() {
        try {
            Files.createDirectories(stateFile.getParent());
            Files.write(stateFile, vanished.stream().map(UUID::toString).toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Subscribe
    public void onLogin(PostLoginEvent event) {
        if (vanished.contains(event.getPlayer().getUniqueId())) {
            event.getPlayer().sendMessage(Component.text("You are vanished. /v to show yourself.", NamedTextColor.GRAY));
        }
    }

    // Before Carbon (which runs late): a vanished player's chat would give them away. Staff chat
    // with the # prefix still goes through. Denying signed chat is deprecated in Velocity; SignedVelocity
    // makes it work, as it does for Carbon.
    @Subscribe
    @SuppressWarnings("deprecation")
    public void onChat(PlayerChatEvent event) {
        if (vanished.contains(event.getPlayer().getUniqueId()) && !event.getMessage().startsWith("#")) {
            event.setResult(ChatResult.denied());
            event.getPlayer().sendMessage(Component.text(
                    "You are vanished: public chat is off. Use # or /sc for staff chat.", NamedTextColor.GRAY));
        }
    }

    // Not in the server list's player count or names.
    @Subscribe
    public void onPing(ProxyPingEvent event) {
        long hidden = proxy.getAllPlayers().stream().filter(p -> vanished.contains(p.getUniqueId())).count();
        if (hidden == 0) {
            return;
        }
        ServerPing.Builder ping = event.getPing().asBuilder();
        ping.onlinePlayers((int) Math.max(0, ping.getOnlinePlayers() - hidden));
        var sample = ping.getSamplePlayers().stream().filter(s -> !vanished.contains(s.getId())).toList();
        ping.clearSamplePlayers().samplePlayers(sample);
        event.setPing(ping.build());
    }
}
