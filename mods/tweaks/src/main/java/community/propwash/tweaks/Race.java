package community.propwash.tweaks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * /race lists this server's Propwash race courses (clickable); /race <course> (its name, or its number for
 * an unnamed one) takes you just in front of its start gate, facing through it, at most every 2 seconds. Propwash keeps the
 * courses in world/data/propwash_race.json and writes it when the world is saved, so a brand-new course
 * shows up after the next save (every few minutes); its API has no courses yet.
 */
final class Race {
    /** Blocks in front of the start gate where you land. */
    private static final double BEFORE_GATE = 3;
    /** Time between two teleports of one player (clicking through the list). */
    private static final long COOLDOWN_MS = 2000;
    private static final java.util.Map<java.util.UUID, Long> lastRace = new java.util.concurrent.ConcurrentHashMap<>();

    private record Course(int id, String name, String owner, String dimension, double x, double y, double z, float yaw) {
        /** What you type: the name, or the number when it has none. */
        String key() {
            return name.isBlank() ? String.valueOf(id) : name;
        }

        String title() {
            return name.isBlank() ? "Course " + id : name;
        }

        String label() {
            return title() + (owner.isBlank() ? "" : " by " + owner);
        }
    }

    private Race() {
    }

    static void init() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("race")
                        .executes(c -> list(c.getSource()))
                        .then(Commands.argument("course", StringArgumentType.greedyString())
                                .suggests((c, b) -> {
                                    String typed = b.getRemaining().toLowerCase(Locale.ROOT);
                                    courses(c.getSource().getServer()).stream().map(Course::key)
                                            .filter(k -> k.toLowerCase(Locale.ROOT).startsWith(typed)).forEach(b::suggest);
                                    return b.buildFuture();
                                })
                                .executes(c -> go(c.getSource(), StringArgumentType.getString(c, "course"))))));
    }

    private static List<Course> courses(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("data/propwash_race.json");
        List<Course> courses = new ArrayList<>();
        if (!Files.exists(file)) {
            return courses;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonArray all = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("courses");
            for (JsonElement e : all) {
                JsonObject c = e.getAsJsonObject();
                JsonObject start = null;
                for (JsonElement g : c.getAsJsonArray("gates")) {
                    JsonObject gate = g.getAsJsonObject();
                    if (start == null || gate.get("index").getAsInt() < start.get("index").getAsInt()) {
                        start = gate;
                    }
                }
                if (start == null) {
                    continue; // no gates yet
                }
                courses.add(new Course(c.get("id").getAsInt(), text(c, "name"), text(c, "ownerName"), text(c, "dimension"),
                        start.get("x").getAsDouble(), start.get("y").getAsDouble(), start.get("z").getAsDouble(),
                        start.get("yaw").getAsFloat()));
            }
        } catch (IOException | RuntimeException e) {
            return courses;
        }
        // Named ones first, by name; then the unnamed ones by number.
        courses.sort(Comparator.comparing((Course c) -> c.name().isBlank())
                .thenComparing(c -> c.name().toLowerCase(Locale.ROOT)).thenComparingInt(Course::id));
        return courses;
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static int list(CommandSourceStack source) {
        List<Course> courses = courses(source.getServer());
        if (courses.isEmpty()) {
            source.sendFailure(Component.literal("No race courses here yet."));
            return 0;
        }
        // One course a line: its name (click to go), then who built it.
        MutableComponent line = Component.literal("Race courses (click one to go there):").withStyle(ChatFormatting.GRAY);
        for (Course c : courses) {
            line.append(Component.literal("\n » ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(c.title()).withStyle(s -> s.withColor(ChatFormatting.WHITE)
                            .withClickEvent(new ClickEvent.RunCommand("/race " + c.key()))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal("/race " + c.key())))));
            if (!c.owner().isBlank()) {
                line.append(Component.literal(" by " + c.owner()).withStyle(ChatFormatting.GRAY));
            }
        }
        source.sendSuccess(() -> line, false);
        return courses.size();
    }

    private static int go(CommandSourceStack source, String typed) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        long last = lastRace.getOrDefault(player.getUUID(), 0L);
        if (now - last < COOLDOWN_MS) {
            source.sendFailure(Component.literal("Wait a moment before the next /race."));
            return 0;
        }
        String want = typed.strip();
        Course course = courses(source.getServer()).stream()
                .filter(c -> c.key().equalsIgnoreCase(want) || c.name().equalsIgnoreCase(want) || String.valueOf(c.id()).equals(want))
                .findFirst().orElse(null);
        if (course == null) {
            source.sendFailure(Component.literal("There's no race course called " + want + " here. /race lists them."));
            return 0;
        }
        ServerLevel level = source.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(course.dimension())));
        if (level == null) {
            source.sendFailure(Component.literal("That course is in a world that isn't loaded."));
            return 0;
        }
        // In front of the gate (against the way it faces), looking through it.
        double yaw = Math.toRadians(course.yaw());
        double x = course.x() + Math.sin(yaw) * BEFORE_GATE, z = course.z() - Math.cos(yaw) * BEFORE_GATE;
        player.teleportTo(level, x, course.y(), z, Set.of(), course.yaw(), 0, true);
        lastRace.put(player.getUUID(), now);
        source.sendSuccess(() -> Component.literal("At the start of " + course.label() + ". Good luck!").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }
}
