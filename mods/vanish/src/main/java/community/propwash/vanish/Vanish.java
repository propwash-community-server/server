package community.propwash.vanish;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.command.CommandExecuteEvent.CommandResult;
import com.velocitypowered.api.event.connection.PostLoginEvent;
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
 * propwash.vanish.see sees vanished players.
 */
@Plugin(id = "vanish", name = "Vanish", version = "1.1.0",
        description = "Network-wide /v",
        dependencies = {@Dependency(id = "luckperms"), @Dependency(id = "tab", optional = true)})
public final class Vanish implements Predicate<UUID> {
    private static final String USE = "propwash.vanish";
    private static final String SEE = "propwash.vanish.see";

    private final ProxyServer proxy;
    private final Logger logger;
    // Who is vanished, kept across restarts: one UUID per line.
    private final Path stateFile;
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    // Servers running Paper (EssentialsX /vanish); the rest run Fabric (the Vanish mod).
    private final Set<String> paperServers;

    @Inject
    public Vanish(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) throws IOException {
        this.proxy = proxy;
        this.logger = logger;
        this.stateFile = dataDirectory.resolve("vanished");
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
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("v").plugin(this).build(),
                new SimpleCommand() {
                    @Override
                    public void execute(Invocation invocation) {
                    }

                    @Override
                    public boolean hasPermission(Invocation invocation) {
                        return invocation.source().hasPermission(USE);
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

    // /v and /vanish (without arguments) toggle here and go on to the server as its own vanish command.
    @Subscribe
    public void onCommand(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player player) || !player.hasPermission(USE)) {
            return;
        }
        String command = event.getCommand().trim();
        if (!command.equalsIgnoreCase("v") && !command.equalsIgnoreCase("vanish")) {
            return;
        }
        boolean on = !vanished.contains(player.getUniqueId());
        set(player, on);
        String state = on ? "on" : "off";
        boolean paper = player.getCurrentServer()
                .map(s -> paperServers.contains(s.getServerInfo().getName())).orElse(false);
        event.setResult(CommandResult.forwardToServer(
                paper ? "vanish " + state : "vanish " + state + " " + player.getUsername()));
        player.sendMessage(Component.text(on
                ? "You are vanished on every server. Public chat is off; # or /sc for staff chat. /v again to show yourself."
                : "You are visible again.", NamedTextColor.GRAY));
    }

    private void set(Player player, boolean on) {
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
        LuckPermsProvider.get().getUserManager().modifyUser(uuid, user -> {
            user.data().clear(NodeType.META.predicate(n -> n.getMetaKey().equals("vanish_on_join")));
            user.data().add(MetaNode.builder("vanish_on_join", Boolean.toString(on)).build());
            for (String node : new String[]{"essentials.silentjoin.vanish", "networkjoinmessages.silent"}) {
                user.data().clear(n -> n.getKey().equals(node));
                user.data().add(PermissionNode.builder(node).value(on).build());
            }
        });
        logger.info("{} is {}", player.getUsername(), on ? "vanished" : "visible again");
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
