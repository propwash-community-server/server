package community.propwash.announcements;

import com.google.inject.Inject;
import com.moandjiezana.toml.Toml;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

/**
 * Every few minutes one message from plugins/announcements/announcements.toml, picked at random by
 * weight (70 and 30: that one 70% of the time on average), goes to the servers listed there: as a chat
 * message or as a boss bar for a few seconds. The file is read again whenever it changes, so edits
 * apply at the next announcement. /announce (propwash.announce) sends one now.
 */
@Plugin(id = "announcements", name = "Announcements", version = "1.0.0",
        description = "Occasional announcements on chosen servers")
public final class Announcements {
    private static final String PERMISSION = "propwash.announce";

    private record Message(String text, double weight) {
    }

    private record Config(long everySeconds, long bossBarSeconds, BossBar.Color bossBarColor,
                          Map<String, String> servers, List<Message> messages) {
    }

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path file;
    private Config config;
    private long configModified = -1;
    private ScheduledTask task;
    private long scheduledEvery;

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
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("announce").plugin(this).build(),
                new SimpleCommand() {
                    @Override
                    public void execute(Invocation invocation) {
                        reload();
                        announce();
                    }

                    @Override
                    public boolean hasPermission(Invocation invocation) {
                        return invocation.source().hasPermission(PERMISSION);
                    }
                });
    }

    /** (Re)starts the timer, so a changed every_minutes applies from now on. */
    private void schedule() {
        if (config == null || config.everySeconds() == scheduledEvery) {
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
        Component text = MiniMessage.miniMessage().deserialize(m.text().strip());
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
            config = new Config(
                    Math.max(10, Math.round(number(toml, "every_minutes", 10) * 60)),
                    Math.max(1, Math.round(number(toml, "bossbar_seconds", 15))),
                    BossBar.Color.NAMES.valueOr(toml.getString("bossbar_color", "blue"), BossBar.Color.BLUE),
                    servers, messages);
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
