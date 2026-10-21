package community.propwash.plotedit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Watches players' WorldEdit edits on plots: one line per edit in logs/plotedit.log (player, command,
 * blocks changed and skipped, time, slowest tick, limit), a line for every tick slower than lag-log-ms
 * while someone was editing (whose edits ran in it), and a chat notice to staff (propwash.plotedit.bypass)
 * for ticks slower than lag-alert-ms and for players who hit a limit limit-alert-count times in 10 minutes.
 * Everything runs on the server thread, where WorldEdit edits too.
 */
final class Monitor {
    static volatile long lagLogMs = 250;
    static volatile long lagAlertMs = 1000;
    static volatile int limitAlertCount = 5;

    private static final long LIMIT_WINDOW_MS = 10 * 60_000;
    private static final Path LOG = FabricLoader.getInstance().getGameDir().resolve("logs/plotedit.log");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** An edit in progress: a player's changes in consecutive ticks. */
    private static final class Edit {
        final String name;
        final String command;
        final long start = System.currentTimeMillis();
        long last = start;
        long changed;
        long skipped;
        long slowestTickMs;
        long lastTick;
        String limit;

        Edit(String name, String command) {
            this.name = name;
            this.command = command;
        }
    }

    private static final Map<UUID, Edit> edits = new LinkedHashMap<>();
    // A player's last typed WorldEdit command, until the next edit takes it (WorldEdit runs it a bit later).
    private static final Map<UUID, String> pendingCommand = new LinkedHashMap<>();
    private static final Map<UUID, Long> pendingAt = new LinkedHashMap<>();
    // The label of the edit starting next, set as WorldEdit creates its edit session.
    private static final Map<UUID, String> nextLabel = new LinkedHashMap<>();
    private static final long COMMAND_MAX_AGE_MS = 10_000;
    private static final Map<UUID, Long> thisTick = new LinkedHashMap<>();
    private static final Map<UUID, Deque<Long>> limitHits = new LinkedHashMap<>();
    private static long tick;
    private static long tickStart;

    private Monitor() {
    }

    static void command(UUID player, String command) {
        pendingCommand.put(player, command);
        pendingAt.put(player, System.currentTimeMillis());
    }

    /** A command that won't run (refused): it mustn't label the next edit. */
    static void forget(UUID player) {
        pendingCommand.remove(player);
        pendingAt.remove(player);
    }

    /**
     * For a new edit session: the command it comes from (the last one typed, at most 10 s ago, and not
     * taken by an earlier edit), or null for tools and brushes.
     */
    static String takeCommand(UUID player) {
        String command = pendingCommand.remove(player);
        Long at = pendingAt.remove(player);
        String label = command != null && at != null && System.currentTimeMillis() - at <= COMMAND_MAX_AGE_MS ? command : null;
        nextLabel.put(player, label == null ? "(no command: a tool or brush)" : label);
        return label;
    }

    private static Edit edit(UUID player, String name) {
        Edit edit = edits.computeIfAbsent(player, id -> new Edit(name, nextLabel.getOrDefault(id, "?")));
        edit.lastTick = tick;
        edit.last = System.currentTimeMillis();
        return edit;
    }

    static void changed(UUID player, String name) {
        edit(player, name).changed++;
        thisTick.merge(player, 1L, Long::sum);
    }

    static void skipped(UUID player, String name) {
        edit(player, name).skipped++;
    }

    static void limited(UUID player, String name, String limit, MinecraftServer server) {
        edit(player, name).limit = limit;
        long now = System.currentTimeMillis();
        Deque<Long> hits = limitHits.computeIfAbsent(player, id -> new ArrayDeque<>());
        hits.addLast(now);
        while (!hits.isEmpty() && hits.peekFirst() < now - LIMIT_WINDOW_MS) {
            hits.removeFirst();
        }
        if (hits.size() >= limitAlertCount) {
            hits.clear(); // one notice per run of hits
            alert(server, name + " hit the WorldEdit limits " + limitAlertCount + " times in 10 minutes (on plots).");
        }
    }

    static void tickStart() {
        tickStart = System.nanoTime();
    }

    static void tickEnd(MinecraftServer server) {
        long ms = (System.nanoTime() - tickStart) / 1_000_000;
        if (!thisTick.isEmpty() && ms >= lagLogMs) {
            String who = thisTick.entrySet().stream()
                    .map(e -> edits.containsKey(e.getKey()) ? edits.get(e.getKey()).name + " " + e.getValue() + " blocks (" + edits.get(e.getKey()).command + ")" : e.getValue() + " blocks")
                    .collect(Collectors.joining(", "));
            write("LAG  " + ms + " ms tick during WorldEdit: " + who);
            PlotEdit.LOGGER.warn("{} ms tick during WorldEdit on plots: {}", ms, who);
            if (ms >= lagAlertMs) {
                alert(server, "Lag: a " + ms + " ms tick during WorldEdit on plots by " + who + ".");
            }
        }
        // Edits with changes in this tick are still going; the others are done.
        var it = edits.entrySet().iterator();
        while (it.hasNext()) {
            Edit edit = it.next().getValue();
            if (edit.lastTick == tick) {
                edit.slowestTickMs = Math.max(edit.slowestTickMs, ms);
            } else {
                write("EDIT " + edit.name + " | " + edit.command + " | " + edit.changed + " changed, " + edit.skipped
                        + " skipped (not their plot) | " + (edit.last - edit.start) + " ms, slowest tick "
                        + edit.slowestTickMs + " ms" + (edit.limit == null ? "" : " | stopped: " + edit.limit));
                it.remove();
            }
        }
        thisTick.clear();
        tick++;
    }

    private static void alert(MinecraftServer server, String text) {
        write("ALERT " + text);
        Component message = Component.literal("[PlotEdit] " + text).withStyle(ChatFormatting.GOLD);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (PlotEdit.isStaff(player)) {
                player.sendSystemMessage(message);
            }
        }
    }

    private static void write(String line) {
        try {
            Files.createDirectories(LOG.getParent());
            Files.writeString(LOG, LocalDateTime.now().format(TIME) + " " + line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            PlotEdit.LOGGER.warn("Couldn't write {}", LOG, e);
        }
    }
}
