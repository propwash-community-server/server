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
 *
 * Where in front of the gate isn't always free, so /race lands you higher until it is; and whoever has
 * propwash.race.setstart can pin a course's landing spot where they stand with /race setstart <course>
 * (/race clearstart <course> undoes it). Those spots are kept in world/data/propwash_race_starts.json,
 * by course number.
 */
final class Race {
    /** Blocks in front of the start gate where you land. */
    private static final double BEFORE_GATE = 3;
    /** Time between two teleports of one player (clicking through the list). */
    private static final long COOLDOWN_MS = 2000;
    private static final java.util.Map<java.util.UUID, Long> lastRace = new java.util.concurrent.ConcurrentHashMap<>();
    private static final String SETSTART = "propwash.race.setstart";
    /** How far up /race looks for room to land when in front of the gate is inside blocks. */
    private static final int MAX_LIFT = 6;

    /** A landing spot pinned with /race setstart. */
    private record Start(String dimension, double x, double y, double z, float yaw, float pitch) {
    }

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
                        .then(Commands.literal("setstart").requires(s -> Tweaks.permitted(s, SETSTART))
                                .then(courseArgument().executes(c -> setStart(c.getSource(), StringArgumentType.getString(c, "course"), true))))
                        .then(Commands.literal("clearstart").requires(s -> Tweaks.permitted(s, SETSTART))
                                .then(courseArgument().executes(c -> setStart(c.getSource(), StringArgumentType.getString(c, "course"), false))))
                        .then(courseArgument().executes(c -> go(c.getSource(), StringArgumentType.getString(c, "course"))))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> courseArgument() {
        return Commands.argument("course", StringArgumentType.greedyString())
                .suggests((c, b) -> {
                    String typed = b.getRemaining().toLowerCase(Locale.ROOT);
                    courses(c.getSource().getServer()).stream().map(Course::key)
                            .filter(k -> k.toLowerCase(Locale.ROOT).startsWith(typed)).forEach(b::suggest);
                    return b.buildFuture();
                });
    }

    private static Course find(MinecraftServer server, String typed) {
        String want = typed.strip();
        return courses(server).stream()
                .filter(c -> c.key().equalsIgnoreCase(want) || c.name().equalsIgnoreCase(want) || String.valueOf(c.id()).equals(want))
                .findFirst().orElse(null);
    }

    private static Path startsFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data/propwash_race_starts.json");
    }

    private static JsonObject starts(MinecraftServer server) {
        Path file = startsFile(server);
        if (!Files.exists(file)) {
            return new JsonObject();
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            return new JsonObject();
        }
    }

    private static Start start(MinecraftServer server, Course course) {
        JsonElement e = starts(server).get(String.valueOf(course.id()));
        if (e == null || !e.isJsonObject()) {
            return null;
        }
        JsonObject o = e.getAsJsonObject();
        try {
            return new Start(o.get("dimension").getAsString(), o.get("x").getAsDouble(), o.get("y").getAsDouble(),
                    o.get("z").getAsDouble(), o.get("yaw").getAsFloat(), o.get("pitch").getAsFloat());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** /race setstart <course>: land there where you stand; /race clearstart <course>: in front of the gate again. */
    private static int setStart(CommandSourceStack source, String typed, boolean set) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        Course course = find(source.getServer(), typed);
        if (course == null) {
            source.sendFailure(Component.literal("There's no race course called " + typed.strip() + " here. /race lists them."));
            return 0;
        }
        JsonObject all = starts(source.getServer());
        String id = String.valueOf(course.id());
        if (set) {
            JsonObject o = new JsonObject();
            o.addProperty("dimension", player.level().dimension().identifier().toString());
            o.addProperty("x", player.getX());
            o.addProperty("y", player.getY());
            o.addProperty("z", player.getZ());
            o.addProperty("yaw", player.getYRot());
            o.addProperty("pitch", player.getXRot());
            o.addProperty("setBy", player.getGameProfile().name());
            all.add(id, o);
        } else if (all.remove(id) == null) {
            source.sendFailure(Component.literal(course.title() + " has no start spot set; /race goes in front of its gate."));
            return 0;
        }
        try {
            Path file = startsFile(source.getServer());
            Files.createDirectories(file.getParent());
            Files.writeString(file, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(all));
        } catch (IOException e) {
            source.sendFailure(Component.literal("Couldn't save the start spot: " + e.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(set
                ? "/race " + course.key() + " now lands here, facing this way."
                : "/race " + course.key() + " goes in front of its start gate again.").withStyle(ChatFormatting.GRAY), true);
        return 1;
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

    /** Whether the player (standing) fits at x y z without being inside blocks. */
    private static boolean fits(ServerLevel level, ServerPlayer player, double x, double y, double z) {
        net.minecraft.world.phys.AABB box = player.getType().getDimensions().makeBoundingBox(x, y, z);
        return level.noCollision(player, box);
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
        Course course = find(source.getServer(), typed);
        if (course == null) {
            source.sendFailure(Component.literal("There's no race course called " + typed.strip() + " here. /race lists them."));
            return 0;
        }
        Start start = start(source.getServer(), course);
        ServerLevel level = source.getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                Identifier.parse(start != null ? start.dimension() : course.dimension())));
        if (level == null) {
            source.sendFailure(Component.literal("That course is in a world that isn't loaded."));
            return 0;
        }
        if (start != null) {
            player.teleportTo(level, start.x(), start.y(), start.z(), Set.of(), start.yaw(), start.pitch(), true);
        } else {
            // In front of the gate (against the way it faces), looking through it; higher up if that's
            // inside blocks, or the gate itself if there's no room above either.
            double yaw = Math.toRadians(course.yaw());
            double x = course.x() + Math.sin(yaw) * BEFORE_GATE, z = course.z() - Math.cos(yaw) * BEFORE_GATE;
            double y = course.y();
            int lift = 0;
            while (lift <= MAX_LIFT && !fits(level, player, x, course.y() + lift, z)) {
                lift++;
            }
            if (lift <= MAX_LIFT) {
                y = course.y() + lift;
            } else {
                x = course.x();
                z = course.z();
            }
            player.teleportTo(level, x, y, z, Set.of(), course.yaw(), 0, true);
        }
        lastRace.put(player.getUUID(), now);
        source.sendSuccess(() -> Component.literal("At the start of " + course.label() + ". Good luck!").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }
}
