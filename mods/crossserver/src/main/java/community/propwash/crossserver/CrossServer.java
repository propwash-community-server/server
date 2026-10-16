package community.propwash.crossserver;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.command.CommandExecuteEvent.CommandResult;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

/**
 * Teleporting and /invsee across servers. /tpa, /tpahere, /tpaccept, /tpdeny (propwash.tpa) and staff
 * /tp <player> (propwash.tp) move a player to the other's server first, then teleport them there with
 * a console tp over rcon. Staff /invsee (propwash.invsee) moves you to the player's server and opens
 * their inventory there (EssentialsX /invsee on Paper, InvView /view inv on Fabric). Vanished players
 * (the vanish plugin) can't be found by those who can't see them (propwash.vanish.see). Admin /exempt
 * (propwash.exempt) toggles the propwash.exempt tag (boats, projectiles, dropping items) on every server:
 * kept here and set over rcon on each server the player arrives on.
 */
@Plugin(id = "crossserver", name = "CrossServer", version = "1.1.0",
        description = "Teleporting and /invsee across servers",
        dependencies = {@Dependency(id = "vanish", optional = true)})
public final class CrossServer {
    private static final String TPA = "propwash.tpa";
    private static final String TP = "propwash.tp";
    private static final String INVSEE = "propwash.invsee";
    private static final String SEE_VANISHED = "propwash.vanish.see";
    private static final String EXEMPT = "propwash.exempt";
    private static final Duration EXPIRY = Duration.ofMinutes(2);
    // After arriving on a server: the hub sends arrivals to spawn first.
    private static final long ARRIVAL_DELAY_MS = 1000;

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    // Servers running Paper (EssentialsX /invsee); the rest run Fabric (InvView).
    private final Set<String> paperServers;

    private record Request(UUID from, boolean here, Instant at) {
    }

    // Open requests by the player asked, oldest first.
    private final Map<UUID, LinkedHashMap<UUID, Request>> requests = new ConcurrentHashMap<>();
    // Who has /exempt on, kept across restarts: one UUID per line.
    private final Path exemptFile;
    private final Set<UUID> exempt = ConcurrentHashMap.newKeySet();

    @Inject
    public CrossServer(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) throws IOException {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        Properties config = load(dataDirectory.resolve("config.properties"));
        this.paperServers = Arrays.stream(config.getProperty("paper-servers", "hub").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        this.exemptFile = dataDirectory.resolve("exempt");
        if (Files.exists(exemptFile)) {
            Files.readAllLines(exemptFile).stream().filter(l -> !l.isBlank()).map(UUID::fromString).forEach(exempt::add);
        }
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        register("tpa", TPA, (player, args) -> request(player, args, false), true);
        register("tpahere", TPA, (player, args) -> request(player, args, true), true);
        register("tpaccept", TPA, (player, args) -> answer(player, args, true), false);
        register("tpdeny", TPA, (player, args) -> answer(player, args, false), false);
        register("invsee", INVSEE, this::invsee, true);
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("exempt").plugin(this).build(),
                new SimpleCommand() {
                    @Override
                    public void execute(Invocation invocation) {
                        if (invocation.source() instanceof Player player) {
                            toggleExempt(player);
                        }
                    }

                    @Override
                    public boolean hasPermission(Invocation invocation) {
                        return invocation.source().hasPermission(EXEMPT);
                    }
                });
    }

    private void toggleExempt(Player player) {
        boolean on = !exempt.contains(player.getUniqueId());
        if (on) {
            exempt.add(player.getUniqueId());
        } else {
            exempt.remove(player.getUniqueId());
        }
        try {
            Files.createDirectories(exemptFile.getParent());
            Files.write(exemptFile, exempt.stream().map(UUID::toString).toList());
        } catch (IOException e) {
            logger.warn("Saving {} failed", exemptFile, e);
        }
        player.getCurrentServer().ifPresent(s -> applyExempt(player, s.getServerInfo().getName()));
        player.sendMessage(Component.text(on
                ? "Exempt on every server: you can use boats, projectiles and drop items. /exempt again to stop."
                : "No longer exempt.", NamedTextColor.GRAY));
    }

    // The tag lives with the player on each server, so it's set again on every arrival.
    @Subscribe
    public void onArrive(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        player.getCurrentServer().ifPresent(s -> proxy.getScheduler()
                .buildTask(this, () -> applyExempt(player, s.getServerInfo().getName()))
                .delay(ARRIVAL_DELAY_MS, TimeUnit.MILLISECONDS).schedule());
    }

    private void applyExempt(Player player, String server) {
        String command = "tag " + player.getUsername() + (exempt.contains(player.getUniqueId()) ? " add " : " remove ") + EXEMPT;
        proxy.getScheduler().buildTask(this, () -> {
            try {
                Rcon.run(port(server), password(), command);
            } catch (IOException e) {
                logger.warn("Setting {} on {} failed", EXEMPT, server, e);
            }
        }).schedule();
    }

    // Staff /tp <player> to someone on another server; any other /tp goes on to the server as it is.
    @Subscribe
    public void onCommand(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player player) || !player.hasPermission(TP)) {
            return;
        }
        String[] parts = event.getCommand().trim().split("\\s+");
        if (parts.length != 2 || !(parts[0].equalsIgnoreCase("tp") || parts[0].equalsIgnoreCase("teleport"))) {
            return;
        }
        Optional<Player> target = proxy.getPlayer(parts[1]);
        if (target.isEmpty() || target.get().equals(player) || sameServer(player, target.get())) {
            return;
        }
        event.setResult(CommandResult.denied());
        move(player, target.get());
    }

    private synchronized void request(Player from, String[] args, boolean here) {
        Optional<Player> to = target(from, args, here ? "/tpahere <player>" : "/tpa <player>");
        if (to.isEmpty()) {
            return;
        }
        Player target = to.get();
        requests.computeIfAbsent(target.getUniqueId(), k -> new LinkedHashMap<>())
                .put(from.getUniqueId(), new Request(from.getUniqueId(), here, Instant.now()));
        String name = from.getUsername();
        target.sendMessage(Component.text(name + (here ? " wants you to teleport to them. " : " wants to teleport to you. "),
                        NamedTextColor.YELLOW)
                .append(button("[Accept]", NamedTextColor.GREEN, "/tpaccept " + name))
                .append(Component.text(" "))
                .append(button("[Deny]", NamedTextColor.RED, "/tpdeny " + name)));
        from.sendMessage(Component.text("Request sent to " + target.getUsername() + ".", NamedTextColor.GRAY));
    }

    private synchronized void answer(Player target, String[] args, boolean accept) {
        LinkedHashMap<UUID, Request> open = requests.getOrDefault(target.getUniqueId(), new LinkedHashMap<>());
        open.values().removeIf(r -> r.at().plus(EXPIRY).isBefore(Instant.now()));
        Optional<Request> request = args.length == 0
                ? open.values().stream().reduce((a, b) -> b)
                : proxy.getPlayer(args[0]).map(p -> open.get(p.getUniqueId()));
        if (request.isEmpty()) {
            target.sendMessage(Component.text("No teleport request to answer.", NamedTextColor.RED));
            return;
        }
        open.remove(request.get().from());
        Optional<Player> from = proxy.getPlayer(request.get().from());
        if (from.isEmpty()) {
            target.sendMessage(Component.text("They're not online any more.", NamedTextColor.RED));
            return;
        }
        if (!accept) {
            target.sendMessage(Component.text("Request denied.", NamedTextColor.GRAY));
            from.get().sendMessage(Component.text(target.getUsername() + " denied your request.", NamedTextColor.GRAY));
            return;
        }
        from.get().sendMessage(Component.text(target.getUsername() + " accepted your request.", NamedTextColor.GRAY));
        if (request.get().here()) {
            move(target, from.get());
        } else {
            move(from.get(), target);
        }
    }

    private void invsee(Player staff, String[] args) {
        Optional<Player> to = target(staff, args, "/invsee <player>");
        if (to.isEmpty()) {
            return;
        }
        Player target = to.get();
        RegisteredServer server = target.getCurrentServer().orElseThrow().getServer();
        String command = paperServers.contains(server.getServerInfo().getName())
                ? "/essentials:invsee " + target.getUsername()
                : "/view inv " + target.getUsername();
        whenOn(staff, server, () -> staff.spoofChatInput(command));
    }

    // Moves the player to the other's server if needed, then teleports them to the other.
    private void move(Player mover, Player to) {
        RegisteredServer server = to.getCurrentServer().orElseThrow().getServer();
        String name = server.getServerInfo().getName();
        whenOn(mover, server, () -> proxy.getScheduler().buildTask(this, () -> {
            try {
                Rcon.run(port(name), password(), "tp " + mover.getUsername() + " " + to.getUsername());
            } catch (IOException e) {
                logger.warn("Teleporting {} to {} on {} failed", mover.getUsername(), to.getUsername(), name, e);
                mover.sendMessage(Component.text("Teleporting failed.", NamedTextColor.RED));
            }
        }).schedule());
    }

    // Runs the action once the player is on the server, moving them there first if needed.
    private void whenOn(Player player, RegisteredServer server, Runnable action) {
        if (player.getCurrentServer().map(s -> s.getServer().equals(server)).orElse(false)) {
            action.run();
            return;
        }
        player.createConnectionRequest(server).connect().thenAccept(result -> {
            if (result.isSuccessful()) {
                proxy.getScheduler().buildTask(this, action).delay(ARRIVAL_DELAY_MS, TimeUnit.MILLISECONDS).schedule();
            } else {
                player.sendMessage(Component.text("Couldn't move you to " + server.getServerInfo().getName() + ".",
                        NamedTextColor.RED));
            }
        });
    }

    // The online player named in args[0], if the sender may find them.
    private Optional<Player> target(Player sender, String[] args, String usage) {
        if (args.length != 1) {
            sender.sendMessage(Component.text("Usage: " + usage, NamedTextColor.RED));
            return Optional.empty();
        }
        Optional<Player> target = proxy.getPlayer(args[0]).filter(p -> visible(sender, p));
        if (target.isEmpty() || target.get().getCurrentServer().isEmpty()) {
            sender.sendMessage(Component.text(args[0] + " isn't online.", NamedTextColor.RED));
            return Optional.empty();
        }
        if (target.get().equals(sender)) {
            sender.sendMessage(Component.text("That's you.", NamedTextColor.RED));
            return Optional.empty();
        }
        return target;
    }

    @SuppressWarnings("unchecked")
    private boolean visible(Player viewer, Player target) {
        if (viewer.hasPermission(SEE_VANISHED)) {
            return true;
        }
        return proxy.getPluginManager().getPlugin("vanish").flatMap(PluginContainer::getInstance)
                .filter(Predicate.class::isInstance)
                .map(plugin -> !((Predicate<UUID>) plugin).test(target.getUniqueId()))
                .orElse(true);
    }

    private boolean sameServer(Player a, Player b) {
        return a.getCurrentServer().map(s -> s.getServerInfo().getName())
                .equals(b.getCurrentServer().map(s -> s.getServerInfo().getName()));
    }

    private void register(String name, String permission, BiConsumer<Player, String[]> action, boolean namesPlayer) {
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder(name).plugin(this).build(),
                new SimpleCommand() {
                    @Override
                    public void execute(Invocation invocation) {
                        if (invocation.source() instanceof Player player) {
                            action.accept(player, invocation.arguments());
                        }
                    }

                    @Override
                    public boolean hasPermission(Invocation invocation) {
                        return invocation.source().hasPermission(permission);
                    }

                    @Override
                    public List<String> suggest(Invocation invocation) {
                        if (!(invocation.source() instanceof Player player) || invocation.arguments().length > 1) {
                            return List.of();
                        }
                        String prefix = invocation.arguments().length == 0 ? "" : invocation.arguments()[0].toLowerCase();
                        var candidates = namesPlayer
                                ? proxy.getAllPlayers().stream().filter(p -> !p.equals(player) && visible(player, p))
                                : requests.getOrDefault(player.getUniqueId(), new LinkedHashMap<>()).keySet().stream()
                                        .map(proxy::getPlayer).flatMap(Optional::stream);
                        return candidates.map(Player::getUsername)
                                .filter(n -> n.toLowerCase().startsWith(prefix)).sorted().toList();
                    }
                });
    }

    private static Component button(String text, NamedTextColor color, String command) {
        return Component.text(text, color).clickEvent(ClickEvent.runCommand(command));
    }

    private int port(String server) throws IOException {
        String port = load(dataDirectory.resolve("rcon.properties")).getProperty(server);
        if (port == null) {
            throw new IOException("no rcon port for " + server);
        }
        return Integer.parseInt(port.trim());
    }

    private String password() throws IOException {
        return Files.readString(dataDirectory.resolve("rcon.password")).strip();
    }

    private static Properties load(Path file) throws IOException {
        Properties properties = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            }
        }
        return properties;
    }
}
