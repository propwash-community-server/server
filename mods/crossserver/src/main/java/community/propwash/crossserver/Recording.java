package community.propwash.crossserver;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

/**
 * /record (propwash.record: creators and staff) toggles recording mode, kept across restarts until
 * /record again: no chat from anyone else (Carbon's channels and /msg), no join and leave messages
 * (NetworkJoinMessages' /njointoggle, run for them), no announcements or votes, and no /tpa
 * requests; their own chat and the answers to their own commands still come. A boss bar with the
 * server address (recording-bar in config.properties) shows instead, except while they fly their
 * drone (mods/tweaks RecordingBar). Staff (propwash.record.see) who run a command naming them (/msg,
 * /tp, /invsee, ...) are told they're recording; nobody else can tell, so nobody is drawn to bother
 * them: their /msg and /tpa look sent as usual but never arrive.
 */
final class Recording {
    static final String PERMISSION = "propwash.record";
    static final String SEE = "propwash.record.see";
    private static final long NOTE_MS = 1000;
    private static final String BAR = "propwash:recording";
    private static final String BAR_TAG = "propwash.recording";
    private static final long ARRIVAL_DELAY_MS = 1000;

    private final ProxyServer proxy;
    private final Object plugin;
    private final Logger logger;
    private final Path file;
    private final BiPredicate<Player, Player> visible;
    private final Set<UUID> recording = ConcurrentHashMap.newKeySet();
    private final Afk.Console console;
    /** The boss bar's text as a text component for the bossbar command. */
    private final String barText;
    // Who was last told about whom, and when: /msg is seen both as a command and by Carbon.
    private final Map<UUID, Map<UUID, Long>> noted = new ConcurrentHashMap<>();

    Recording(ProxyServer proxy, Object plugin, Logger logger, Path dataDirectory, String barText,
              Afk.Console console, BiPredicate<Player, Player> visible) throws IOException {
        this.proxy = proxy;
        this.plugin = plugin;
        this.logger = logger;
        this.file = dataDirectory.resolve("recording");
        this.visible = visible;
        this.console = console;
        this.barText = GsonComponentSerializer.gson().serialize(MiniMessage.miniMessage().deserialize(barText));
        if (Files.exists(file)) {
            Files.readAllLines(file).stream().filter(l -> !l.isBlank()).map(UUID::fromString).forEach(recording::add);
        }
    }

    void register() {
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("record").plugin(plugin).build(),
                new SimpleCommand() {
                    @Override
                    public void execute(Invocation invocation) {
                        if (invocation.source() instanceof Player player) {
                            toggle(player);
                        }
                    }

                    @Override
                    public boolean hasPermission(Invocation invocation) {
                        return invocation.source().hasPermission(PERMISSION);
                    }
                });
        proxy.getEventManager().register(plugin, this);
        if (proxy.getPluginManager().isLoaded("carbonchat")) {
            CarbonFilter.register(this);
        }
    }

    boolean isRecording(UUID player) {
        return recording.contains(player);
    }

    /** Whether the player gets messages meant for everyone (announcements, votes). */
    boolean hears(Player player) {
        return !recording.contains(player.getUniqueId());
    }

    private void toggle(Player player) {
        boolean on = recording.add(player.getUniqueId());
        if (!on) {
            recording.remove(player.getUniqueId());
        }
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, recording.stream().map(UUID::toString).toList());
        } catch (IOException e) {
            logger.warn("Saving {} failed", file, e);
        }
        joinMessages(player);
        player.getCurrentServer().ifPresent(s -> applyOn(player, s.getServerInfo().getName()));
        logger.info("{} {} recording mode", player.getUsername(), on ? "started" : "ended");
        player.sendMessage(Component.text(on
                ? "Recording mode: you won't see other players' chat, private messages, join messages,"
                        + " announcements or teleport requests. /record again to stop."
                : "Recording mode off: chat is back.", NamedTextColor.GRAY));
    }

    /** Join and leave messages off or on, as recording mode says (NetworkJoinMessages keeps it per visit). */
    private void joinMessages(Player player) {
        if (proxy.getPluginManager().isLoaded("networkjoinmessages")) {
            proxy.getCommandManager().executeAsync(proxy.getConsoleCommandSource(),
                    "njointoggle all " + (recording.contains(player.getUniqueId()) ? "off " : "on ") + player.getUsername());
        }
    }

    /**
     * The tag propwash.recording on the player there, as recording mode says, and the boss bar
     * propwash:recording for everyone tagged. mods/tweaks keeps it to those not flying their drone;
     * a server without it (limbo) just shows it. The tag lives with the player on each server, so it's
     * set again on every arrival.
     */
    private void applyOn(Player player, String server) {
        boolean on = recording.contains(player.getUniqueId());
        proxy.getScheduler().buildTask(plugin, () -> {
            try {
                console.run(server, "tag " + player.getUsername() + (on ? " add " : " remove ") + BAR_TAG);
                if (on) {
                    console.run(server, "bossbar add " + BAR + " \"\"");
                    console.run(server, "bossbar set " + BAR + " name " + barText);
                    console.run(server, "bossbar set " + BAR + " color white");
                }
                console.run(server, "bossbar set " + BAR + " players @a[tag=" + BAR_TAG + "]");
            } catch (IOException e) {
                logger.warn("Recording: setting {}'s boss bar on {} failed", player.getUsername(), server, e);
            }
        }).schedule();
    }

    @Subscribe
    public void onArrive(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        // NetworkJoinMessages: once per visit. The tag: on every server, after the hub's move to spawn.
        if (event.getPreviousServer() == null && recording.contains(player.getUniqueId())) {
            joinMessages(player);
        }
        player.getCurrentServer().ifPresent(s -> proxy.getScheduler()
                .buildTask(plugin, () -> applyOn(player, s.getServerInfo().getName()))
                .delay(ARRIVAL_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS).schedule());
    }

    @Subscribe
    public void onCommand(CommandExecuteEvent event) {
        if (recording.isEmpty() || !(event.getCommandSource() instanceof Player sender)) {
            return;
        }
        String[] words = event.getCommand().trim().split("\\s+");
        String command = words[0].substring(words[0].indexOf(':') + 1).toLowerCase();
        // Private messages are stopped here rather than by cancelling Carbon's event, after which Carbon
        // tells the sender "Failed to send private message". /r goes to whoever Carbon would reply to.
        if (REPLY.contains(command) && words.length > 1 && proxy.getPluginManager().isLoaded("carbonchat")) {
            UUID to = CarbonFilter.replyTarget(sender.getUniqueId());
            Player target = to == null ? null : proxy.getPlayer(to).orElse(null);
            if (target != null && recording.contains(to)) {
                stop(event, sender, target, String.join(" ", Arrays.copyOfRange(words, 1, words.length)));
            }
            return;
        }
        for (int i = 1; i < words.length; i++) {
            Player target = proxy.getPlayer(words[i]).orElse(null);
            if (target != null && target != sender && recording.contains(target.getUniqueId())) {
                if (WHISPER.contains(command) && i == 1 && words.length > 2) {
                    stop(event, sender, target, String.join(" ", Arrays.copyOfRange(words, 2, words.length)));
                } else {
                    note(sender, target);
                }
                return;
            }
        }
    }

    /** Carbon's /msg and /r with their aliases. */
    private static final Set<String> WHISPER = Set.of("whisper", "w", "message", "msg", "m", "tell");
    private static final Set<String> REPLY = Set.of("reply", "r");

    /**
     * A private message to a recording player isn't sent. Staff are told why; anyone else sees it as
     * sent, in Carbon's own format (whisper.to in its en_US locale).
     */
    private void stop(CommandExecuteEvent event, Player sender, Player target, String message) {
        if (!visible.test(sender, target)) {
            return;
        }
        event.setResult(CommandExecuteEvent.CommandResult.denied());
        if (sender.hasPermission(SEE)) {
            note(sender, target);
            return;
        }
        String name = target.getUsername();
        sender.sendMessage(Component.text()
                .append(Component.text("[", NamedTextColor.GOLD), Component.text("You", NamedTextColor.GREEN),
                        Component.text("] -> [", NamedTextColor.GOLD), Component.text(name, NamedTextColor.GREEN),
                        Component.text("] " + message, NamedTextColor.GOLD))
                .clickEvent(ClickEvent.suggestCommand("/whisper " + name + " "))
                .hoverEvent(HoverEvent.showText(Component.text("Click to start another message to " + name)))
                .build());
    }

    /** Tells staff (propwash.record.see) the target is recording, at most once a second, if they may see the target. */
    void note(Player sender, Player target) {
        if (!sender.hasPermission(SEE) || !visible.test(sender, target)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = noted.computeIfAbsent(sender.getUniqueId(), k -> new ConcurrentHashMap<>()).put(target.getUniqueId(), now);
        if (last == null || now - last > NOTE_MS) {
            sender.sendMessage(Component.text(target.getUsername()
                    + " is recording: they don't see chat, private messages or teleport requests.", NamedTextColor.GRAY));
        }
    }

    void note(UUID sender, UUID target) {
        proxy.getPlayer(sender).ifPresent(s -> proxy.getPlayer(target).ifPresent(t -> note(s, t)));
    }

    /** Carbon's events, in a class of their own so nothing loads Carbon's API when Carbon isn't there. */
    private static final class CarbonFilter {
        /** Who /r would go to for this player, or null. */
        static UUID replyTarget(UUID player) {
            try {
                return net.draycia.carbon.api.CarbonChatProvider.carbonChat().userManager().user(player)
                        .get(1, java.util.concurrent.TimeUnit.SECONDS).whisperReplyTarget();
            } catch (Exception e) {
                return null;
            }
        }

        static void register(Recording recording) {
            var events = net.draycia.carbon.api.CarbonChatProvider.carbonChat().eventHandler();
            // Channel chat: everyone recording but the sender is left out.
            events.subscribe(net.draycia.carbon.api.event.events.CarbonChatEvent.class, 0, false, event -> {
                UUID sender = event.sender().uuid();
                event.recipients().removeIf(r -> r instanceof net.draycia.carbon.api.users.CarbonPlayer p
                        && !p.uuid().equals(sender) && recording.isRecording(p.uuid()));
            });
            // Any other way to a private message (the commands are stopped earlier): not sent.
            events.subscribe(net.draycia.carbon.api.event.events.CarbonPrivateChatEvent.class, 0, false, event -> {
                UUID to = event.recipient().uuid();
                if (recording.isRecording(to) && !to.equals(event.sender().uuid())) {
                    event.cancelled(true);
                    recording.note(event.sender().uuid(), to);
                }
            });
        }
    }
}
