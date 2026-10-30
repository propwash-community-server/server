package community.propwash.crossserver;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.slf4j.Logger;

/**
 * /vote across all servers: admins (propwash.vote) /vote start <question> a yes/no vote that everyone
 * answers with /vote yes or /vote no (clickable); it ends after 60 seconds or with /vote end, and
 * everyone gets the result. One vote at a time, one answer per player (changeable). The question is
 * shown as plain text, so it can't carry formatting or click actions. A chime sounds with the question
 * and with the result.
 */
final class Vote {
    private static final String ADMIN = "propwash.vote";
    private static final Duration LENGTH = Duration.ofSeconds(60);
    private static final Component PREFIX = Component.text("[Vote] ", NamedTextColor.GOLD, TextDecoration.BOLD);
    // Master volume, so players who turned block or player sounds down still hear it.
    private static final Sound START_SOUND = Sound.sound(Key.key("block.note_block.pling"), Sound.Source.MASTER, 1f, 1.2f);
    private static final Sound END_SOUND = Sound.sound(Key.key("block.note_block.chime"), Sound.Source.MASTER, 1f, 1f);

    private final ProxyServer proxy;
    private final Object plugin;
    private final Logger logger;

    private String question;
    private Instant endsAt;
    private ScheduledTask timer;
    private final Map<UUID, Boolean> answers = new ConcurrentHashMap<>();

    Vote(ProxyServer proxy, Object plugin, Logger logger) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.logger = logger;
    }

    void register() {
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("vote").plugin(plugin).build(),
                new SimpleCommand() {
                    @Override
                    public void execute(Invocation invocation) {
                        String[] args = invocation.arguments();
                        CommandSource source = invocation.source();
                        String sub = args.length == 0 ? "" : args[0].toLowerCase();
                        switch (sub) {
                            case "yes", "no" -> answer(source, sub.equals("yes"));
                            case "start" -> {
                                if (admin(source)) start(source, String.join(" ", List.of(args).subList(1, args.length)).strip());
                            }
                            case "end" -> {
                                if (admin(source)) end(true);
                            }
                            default -> status(source);
                        }
                    }

                    @Override
                    public List<String> suggest(Invocation invocation) {
                        if (invocation.arguments().length > 1) {
                            return List.of();
                        }
                        String typed = invocation.arguments().length == 0 ? "" : invocation.arguments()[0].toLowerCase();
                        List<String> options = invocation.source().hasPermission(ADMIN)
                                ? List.of("yes", "no", "start", "end") : List.of("yes", "no");
                        return options.stream().filter(o -> o.startsWith(typed)).toList();
                    }
                });
    }

    private boolean admin(CommandSource source) {
        if (source.hasPermission(ADMIN)) {
            return true;
        }
        source.sendMessage(PREFIX.append(Component.text("Only admins can do that.", NamedTextColor.RED)));
        return false;
    }

    private synchronized void start(CommandSource source, String text) {
        if (text.isEmpty()) {
            source.sendMessage(PREFIX.append(Component.text("Usage: /vote start <question>", NamedTextColor.RED)));
            return;
        }
        if (question != null) {
            source.sendMessage(PREFIX.append(Component.text("A vote is already running; /vote end ends it.", NamedTextColor.RED)));
            return;
        }
        question = text.length() > 200 ? text.substring(0, 200) : text;
        endsAt = Instant.now().plus(LENGTH);
        answers.clear();
        timer = proxy.getScheduler().buildTask(plugin, () -> end(false)).delay(LENGTH.toSeconds(), TimeUnit.SECONDS).schedule();
        logger.info("Vote started by {}: {}", name(source), question);
        broadcast(Component.text(question, NamedTextColor.WHITE)
                .append(Component.text("  "))
                .append(button("[Yes]", NamedTextColor.GREEN, "/vote yes"))
                .append(Component.text(" "))
                .append(button("[No]", NamedTextColor.RED, "/vote no"))
                .append(Component.text("  (" + LENGTH.toSeconds() + " s)", NamedTextColor.GRAY)), START_SOUND);
    }

    private synchronized void answer(CommandSource source, boolean yes) {
        if (!(source instanceof Player player)) {
            return;
        }
        if (question == null) {
            player.sendMessage(PREFIX.append(Component.text("There's no vote running.", NamedTextColor.GRAY)));
            return;
        }
        answers.put(player.getUniqueId(), yes);
        player.sendMessage(PREFIX.append(Component.text("You voted " + (yes ? "yes" : "no") + ".", NamedTextColor.GRAY)));
    }

    private synchronized void end(boolean early) {
        if (question == null) {
            return;
        }
        if (early && timer != null) {
            timer.cancel();
        }
        long yes = answers.values().stream().filter(b -> b).count();
        long no = answers.size() - yes;
        logger.info("Vote ended: {} (yes {}, no {})", question, yes, no);
        broadcast(Component.text("Result: ", NamedTextColor.GRAY)
                .append(Component.text(question, NamedTextColor.WHITE))
                .append(Component.text("  Yes " + yes, NamedTextColor.GREEN))
                .append(Component.text(", ", NamedTextColor.GRAY))
                .append(Component.text("No " + no, NamedTextColor.RED)), END_SOUND);
        question = null;
        timer = null;
        answers.clear();
    }

    private synchronized void status(CommandSource source) {
        if (question == null) {
            source.sendMessage(PREFIX.append(Component.text("There's no vote running.", NamedTextColor.GRAY)));
            return;
        }
        long left = Math.max(0, Duration.between(Instant.now(), endsAt).toSeconds());
        source.sendMessage(PREFIX.append(Component.text(question, NamedTextColor.WHITE))
                .append(Component.text("  "))
                .append(button("[Yes]", NamedTextColor.GREEN, "/vote yes"))
                .append(Component.text(" "))
                .append(button("[No]", NamedTextColor.RED, "/vote no"))
                .append(Component.text("  (" + left + " s left, " + answers.size() + " voted)", NamedTextColor.GRAY)));
    }

    private void broadcast(Component message, Sound sound) {
        Component line = PREFIX.append(message);
        proxy.getAllPlayers().forEach(p -> {
            p.sendMessage(line);
            // Velocity can only play a sound at the player themselves.
            p.playSound(sound, Sound.Emitter.self());
        });
    }

    private static Component button(String text, NamedTextColor color, String command) {
        return Component.text(text, color, TextDecoration.BOLD).clickEvent(ClickEvent.runCommand(command));
    }

    private static String name(CommandSource source) {
        return source instanceof Player player ? player.getUsername() : "the console";
    }
}
