package community.propwash.announcements;

import com.google.inject.Inject;
import com.moandjiezana.toml.Toml;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

/**
 * Every few minutes one message from plugins/announcements/announcements.toml, picked at random by
 * weight (70 and 30: that one 70% of the time on average), goes to the servers listed there: as a chat
 * message or as a boss bar for a few seconds, after the file's prefix (e.g. [Propwash]). The file is
 * read again whenever it changes, so edits apply at the next announcement. /announce (propwash.announce)
 * sends one now; /announce <message> sends that (MiniMessage) after the prefix, in chat on every server.
 *
 * Separately, players who haven't linked their Discord account (no propwash.linked, which the Discord bot
 * sets on /link) get the file's [link] reminder in chat on its own timer and a boss bar that stays up,
 * on every server but those the [link] section leaves out; both stop within seconds of linking.
 */
@Plugin(id = "announcements", name = "Announcements", version = "1.2.0",
        description = "Occasional announcements on chosen servers")
public final class Announcements {
    private static final String PERMISSION = "propwash.announce";
    private static final String LINKED = "propwash.linked";

    private record Message(String text, double weight) {
    }

    private record Config(long everySeconds, long bossBarSeconds, BossBar.Color bossBarColor,
                          Map<String, String> servers, List<Message> messages, String prefix, Link link) {
    }

    /** The [link] section; text or bossBar null = that part is off. */
    private record Link(long everySeconds, String text, String bossBar, BossBar.Color bossBarColor,
                        Set<String> notOn) {
    }

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path file;
    private Config config;
    private long configModified = -1;
    private ScheduledTask task;
    private long scheduledEvery;
    private ScheduledTask linkTask;
    private long scheduledLinkEvery;
    // One link boss bar shared by everyone who sees it (its text and color follow the file).
    private final BossBar linkBar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
    private final Set<UUID> seeingLinkBar = ConcurrentHashMap.newKeySet();

    @Inject
    public Announcements(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.file = dataDirectory.resolve("announcements.toml");
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        reload();
        schedule();
        // Every few seconds, so the bar goes soon after linking (LuckPerms passes the bot's change on).
        proxy.getScheduler().buildTask(this, this::updateLinkBars).repeat(5, TimeUnit.SECONDS).schedule();
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("announce").plugin(this).build(),
                new SimpleCommand() {
                    @Override
                    public void execute(Invocation invocation) {
                        reload();
                        String text = String.join(" ", invocation.arguments()).strip();
                        if (text.isEmpty()) {
                            announce();
                        } else {
                            announceText(text);
                        }
                    }

                    @Override
                    public boolean hasPermission(Invocation invocation) {
                        return invocation.source().hasPermission(PERMISSION);
                    }
                });
    }

    @Subscribe
    public void onServerConnected(ServerPostConnectEvent event) {
        updateLinkBar(event.getPlayer());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        seeingLinkBar.remove(event.getPlayer().getUniqueId());
    }

    /** (Re)starts the timers, so a changed every_minutes applies from now on. */
    private void schedule() {
        if (config == null) {
            return;
        }
        if (config.link().everySeconds() != scheduledLinkEvery) {
            if (linkTask != null) {
                linkTask.cancel();
            }
            scheduledLinkEvery = config.link().everySeconds();
            linkTask = proxy.getScheduler().buildTask(this, () -> {
                reload();
                schedule();
                remindToLink();
            }).delay(scheduledLinkEvery, TimeUnit.SECONDS).repeat(scheduledLinkEvery, TimeUnit.SECONDS).schedule();
        }
        if (config.everySeconds() == scheduledEvery) {
            return;
        }
        if (task != null) {
            task.cancel();
        }
        scheduledEvery = config.everySeconds();
        task = proxy.getScheduler().buildTask(this, () -> {
            reload();
            schedule();
            announce();
        }).delay(scheduledEvery, TimeUnit.SECONDS).repeat(scheduledEvery, TimeUnit.SECONDS).schedule();
    }

    private synchronized void announce() {
        Config c = config;
        if (c == null || c.messages().isEmpty()) {
            return;
        }
        Message m = pick(c.messages());
        Component text = MiniMessage.miniMessage().deserialize(c.prefix() + m.text().strip());
        for (Player player : proxy.getAllPlayers()) {
            String server = player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(null);
            String style = server == null ? null : c.servers().get(server);
            if ("chat".equals(style)) {
                player.sendMessage(text);
            } else if ("bossbar".equals(style)) {
                BossBar bar = BossBar.bossBar(text, 1f, c.bossBarColor(), BossBar.Overlay.PROGRESS);
                player.showBossBar(bar);
                proxy.getScheduler().buildTask(this, () -> player.hideBossBar(bar))
                        .delay(c.bossBarSeconds(), TimeUnit.SECONDS).schedule();
            }
        }
    }

    /** Whether the [link] reminder is for this player now: not linked, and on a server it covers. */
    private boolean needsLink(Player player, Link link) {
        String server = player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(null);
        return server != null && !link.notOn().contains(server) && !player.hasPermission(LINKED);
    }

    private synchronized void remindToLink() {
        Config c = config;
        if (c == null || c.link().text() == null) {
            return;
        }
        Component text = MiniMessage.miniMessage().deserialize(c.prefix() + c.link().text().strip());
        for (Player player : proxy.getAllPlayers()) {
            if (needsLink(player, c.link())) {
                player.sendMessage(text);
            }
        }
    }

    private void updateLinkBars() {
        reload();
        proxy.getAllPlayers().forEach(this::updateLinkBar);
    }

    /** Shows or hides the link boss bar for the player, as needsLink says. */
    private void updateLinkBar(Player player) {
        Config c = config;
        boolean show = c != null && c.link().bossBar() != null && needsLink(player, c.link());
        if (show && seeingLinkBar.add(player.getUniqueId())) {
            player.showBossBar(linkBar);
        } else if (!show && seeingLinkBar.remove(player.getUniqueId())) {
            player.hideBossBar(linkBar);
        }
    }

    /** A message of one's own (/announce <message>): grey like the tips, after the prefix, in chat to everyone on every server. */
    private synchronized void announceText(String text) {
        Config c = config;
        Component message = MiniMessage.miniMessage().deserialize((c == null ? "" : c.prefix()) + "<gray>" + text + "</gray>");
        proxy.getAllPlayers().forEach(p -> p.sendMessage(message));
        logger.info("Announced: {}", text);
    }

    private static Message pick(List<Message> messages) {
        double total = messages.stream().mapToDouble(Message::weight).sum();
        double r = ThreadLocalRandom.current().nextDouble(total);
        for (Message m : messages) {
            r -= m.weight();
            if (r < 0) {
                return m;
            }
        }
        return messages.getLast();
    }

    /** Reads the file again if it changed; a broken file keeps the last good settings. */
    private synchronized void reload() {
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            if (modified == configModified) {
                return;
            }
            Toml toml = new Toml().read(Files.readString(file));
            List<Message> messages = new ArrayList<>();
            for (Toml m : toml.getTables("messages")) {
                double weight = number(m, "weight", 1);
                if (m.getString("text") != null && weight > 0) {
                    messages.add(new Message(m.getString("text"), weight));
                }
            }
            Map<String, String> servers = new java.util.HashMap<>();
            Toml table = toml.getTable("servers");
            if (table != null) {
                for (var e : table.entrySet()) {
                    servers.put(e.getKey(), String.valueOf(e.getValue()));
                }
            }
            Toml l = toml.getTable("link");
            Link link = l == null ? new Link(600, null, null, BossBar.Color.YELLOW, Set.of()) : new Link(
                    Math.max(10, Math.round(number(l, "every_minutes", 10) * 60)),
                    l.getString("text"), l.getString("bossbar"),
                    BossBar.Color.NAMES.valueOr(l.getString("bossbar_color", "yellow"), BossBar.Color.YELLOW),
                    Set.copyOf(l.<String>getList("not_on", List.of())));
            if (link.bossBar() != null) {
                linkBar.name(MiniMessage.miniMessage().deserialize(link.bossBar().strip()));
                linkBar.color(link.bossBarColor());
            }
            config = new Config(
                    Math.max(10, Math.round(number(toml, "every_minutes", 10) * 60)),
                    Math.max(1, Math.round(number(toml, "bossbar_seconds", 15))),
                    BossBar.Color.NAMES.valueOr(toml.getString("bossbar_color", "blue"), BossBar.Color.BLUE),
                    servers, messages, toml.getString("prefix", ""), link);
            configModified = modified;
            logger.info("{} announcements every {} s on {}", messages.size(), config.everySeconds(), servers);
        } catch (IOException | RuntimeException e) {
            logger.warn("Can't read {}; keeping the last settings", file, e);
        }
    }

    /** A number from the file, whole (10) or not (0.5); toml4j keeps those apart. */
    private static double number(Toml toml, String key, double fallback) {
        Object v = toml.toMap().get(key);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }
}
