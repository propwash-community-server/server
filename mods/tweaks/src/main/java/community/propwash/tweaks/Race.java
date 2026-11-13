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
import java.util.Properties;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
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
 *
 * The list is grouped by the course's map's ranking (Beginner, Intermediate, Advanced, Pro), from
 * config/tweaks.properties lines "rank.<namespace>.<path> = <tier>" for the map's dimension (the deploy
 * writes them from the maps' rank in servers/<server>/maps/<map>.toml); courses on unranked maps come last.
 * A ranked world without any course (e.g. a /world create world) is listed itself, as its
 * "title.<namespace>.<path>" (else its name) by its "author.<namespace>.<path>", and /race takes you to the ground at 0 0 there, or to its
 * /race setstart spot (kept by dimension instead of course number). A course without a name (or owner) on a map
 * with such title (author) lines is listed under them, and so is the only course on a map with a title line. A map made by more than one person has
 * "credits.<namespace>.<path> = Uncrashed map by SHO|Adapted by pincil" (the deploy writes them from the map's credits):
 * /race shows those lines under its courses instead of "by <owner>".
 *
 * With "map = race" in config/tweaks.properties (the race server), /map is the same as /race, and /rtp (also
 * /map rtp, and so the rtp mannequin) takes you to the start of a random course from the /race list, another map than
 * the one you're on when there is one; that server then has no Melius Commands /map or /rtp. With "race = map" (freestyle) it's the other way
 * round: /race is Melius Commands' /map, and there's no course list.
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

    /** The map rankings, easiest first: name and colour. */
    private enum Tier {
        BEGINNER("Beginner", ChatFormatting.GREEN),
        INTERMEDIATE("Intermediate", ChatFormatting.YELLOW),
        ADVANCED("Advanced", ChatFormatting.GOLD),
        PRO("Pro", ChatFormatting.RED);

        final String title;
        final ChatFormatting color;

        Tier(String title, ChatFormatting color) {
            this.title = title;
            this.color = color;
        }

        static Tier parse(String text) {
            for (Tier t : values()) {
                if (t.title.equalsIgnoreCase(text.strip())) {
                    return t;
                }
            }
            return null;
        }
    }

    /** A Propwash course, or (id 0) a ranked world without courses, listed as its title. */
    private record Course(int id, String name, String owner, String dimension, double x, double y, double z, float yaw) {
        boolean world() {
            return id == 0;
        }

        /** Its /race setstart spot's key: the course number, or the world's dimension. */
        String startKey() {
            return world() ? dimension : String.valueOf(id);
        }

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
        if (Portals.hasCommand("race")) {
            return; // the hub's /race goes to the race server (Portals)
        }
        if (setting("race").equals("map")) {
            // Freestyle, where the maps aren't race tracks: /race is /map (Melius Commands), run as the player.
            CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                    dispatcher.register(Commands.literal("race")
                            .executes(c -> map(c.getSource(), ""))
                            .then(Commands.argument("map", StringArgumentType.greedyString())
                                    .suggests((c, b) -> {
                                        var map = c.getSource().getServer().getCommands().getDispatcher().getRoot().getChild("map");
                                        if (map != null) {
                                            String typed = b.getRemaining().toLowerCase(Locale.ROOT);
                                            map.getChildren().stream().map(n -> n.getName())
                                                    .filter(n -> n.startsWith(typed)).forEach(b::suggest);
                                        }
                                        return b.buildFuture();
                                    })
                                    .executes(c -> map(c.getSource(), StringArgumentType.getString(c, "map"))))));
            return;
        }
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("race")
                        .executes(c -> list(c.getSource()))
                        .then(Commands.literal("setstart").requires(s -> Tweaks.permitted(s, SETSTART))
                                .then(courseArgument().executes(c -> setStart(c.getSource(), StringArgumentType.getString(c, "course"), true))))
                        .then(Commands.literal("clearstart").requires(s -> Tweaks.permitted(s, SETSTART))
                                .then(courseArgument().executes(c -> setStart(c.getSource(), StringArgumentType.getString(c, "course"), false))))
                        .then(courseArgument().executes(c -> go(c.getSource(), StringArgumentType.getString(c, "course"))))));
        if (setting("map").equals("race")) {
            // The race server, where every map is a race track: /map is /race, and /rtp (/map rtp) a random race.
            CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
                dispatcher.register(Commands.literal("map")
                        .executes(c -> list(c.getSource()))
                        .then(Commands.literal("rtp").executes(c -> random(c.getSource())))
                        .then(courseArgument().executes(c -> go(c.getSource(), StringArgumentType.getString(c, "course")))));
                dispatcher.register(Commands.literal("rtp").executes(c -> random(c.getSource())));
            });
        }
    }

    /** /map [args], as whoever typed /race. */
    private static int map(CommandSourceStack source, String args) {
        source.getServer().getCommands().performPrefixedCommand(source, args.isBlank() ? "map" : "map " + args.strip());
        return 1;
    }

    /** A plain config/tweaks.properties setting, read once at start ("" when it isn't there). */
    private static String setting(String key) {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("tweaks.properties");
        Properties properties = new Properties();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                properties.load(reader);
            } catch (IOException e) {
                return "";
            }
        }
        return properties.getProperty(key, "").strip();
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
                .filter(c -> c.key().equalsIgnoreCase(want) || c.name().equalsIgnoreCase(want) || (!c.world() && String.valueOf(c.id()).equals(want)))
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
        JsonElement e = starts(server).get(course.startKey());
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
        String id = course.startKey();
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
            source.sendFailure(Component.literal(course.title() + " has no start spot set; /race goes " + (course.world() ? "to its spawn." : "in front of its gate.")));
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
                : "/race " + course.key() + (course.world() ? " goes to its spawn again." : " goes in front of its start gate again.")).withStyle(ChatFormatting.GRAY), true);
        return 1;
    }

    /** config/tweaks.properties lines "<prefix><namespace>.<path> = <value>", by dimension ("maps:laminar1"); read every time. */
    private static java.util.Map<String, String> byDimension(String prefix) {
        java.util.Map<String, String> values = new java.util.HashMap<>();
        Path file = FabricLoader.getInstance().getConfigDir().resolve("tweaks.properties");
        if (!Files.exists(file)) {
            return values;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        } catch (IOException e) {
            return values;
        }
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            // "rank.maps.laminar1": a dimension's namespace, then its path ("." for its ":").
            String dimension = key.substring(prefix.length());
            int dot = dimension.indexOf('.');
            if (dot > 0) {
                values.put(dimension.substring(0, dot) + ":" + dimension.substring(dot + 1), properties.getProperty(key).strip());
            }
        }
        return values;
    }

    /** Each ranked map's tier, by dimension. */
    private static java.util.Map<String, Tier> ranks() {
        java.util.Map<String, Tier> ranks = new java.util.HashMap<>();
        byDimension("rank.").forEach((dimension, text) -> {
            Tier tier = Tier.parse(text);
            if (tier != null) {
                ranks.put(dimension, tier);
            }
        });
        return ranks;
    }

    /** "flippy_flop" -> "Flippy Flop". */
    private static String pretty(String path) {
        StringBuilder out = new StringBuilder();
        for (String word : path.substring(path.lastIndexOf('/') + 1).split("_")) {
            if (!word.isEmpty()) {
                out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return out.toString();
    }

    private static List<Course> courses(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("data/propwash_race.json");
        List<Course> courses = new ArrayList<>();
        java.util.Map<String, String> titles = byDimension("title.");
        java.util.Map<String, String> authors = byDimension("author.");
        if (Files.exists(file)) try (Reader reader = Files.newBufferedReader(file)) {
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
                // An unnamed course (or one without an owner) takes its map's title (author) lines, if any.
                String dimension = text(c, "dimension");
                String name = text(c, "name").isBlank() ? titles.getOrDefault(dimension, "") : text(c, "name");
                String owner = text(c, "ownerName").isBlank() ? authors.getOrDefault(dimension, "") : text(c, "ownerName");
                courses.add(new Course(c.get("id").getAsInt(), name, owner, dimension,
                        start.get("x").getAsDouble(), start.get("y").getAsDouble(), start.get("z").getAsDouble(),
                        start.get("yaw").getAsFloat()));
            }
        } catch (IOException | RuntimeException e) {
            // as far as it got
        }
        // The only course on a map with a title line goes by the map's title ("Laminar I", not Propwash's
        // "Laminar I - SHO"); where a map has several courses, they keep their own names.
        java.util.Map<String, Long> perMap = courses.stream()
                .collect(java.util.stream.Collectors.groupingBy(Course::dimension, java.util.stream.Collectors.counting()));
        courses.replaceAll(c -> titles.containsKey(c.dimension()) && perMap.get(c.dimension()) == 1
                ? new Course(c.id(), titles.get(c.dimension()), c.owner(), c.dimension(), c.x(), c.y(), c.z(), c.yaw())
                : c);
        // Ranked worlds on this server without a course: the world itself.
        Set<String> withCourses = new java.util.HashSet<>();
        courses.forEach(c -> withCourses.add(c.dimension()));
        for (String dimension : ranks().keySet()) {
            if (!withCourses.contains(dimension) && server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension))) != null) {
                String title = titles.getOrDefault(dimension, pretty(Identifier.parse(dimension).getPath()));
                courses.add(new Course(0, title, authors.getOrDefault(dimension, ""), dimension, 0, 0, 0, 0));
            }
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
        // By ranking, easiest first (every ranking shows, "none yet" when empty); then the unranked
        // ones. One course a line: its name (click to go), then who built it.
        java.util.Map<String, Tier> ranks = ranks();
        java.util.Map<String, List<String>> credits = credits();
        MutableComponent line = Component.literal("Race courses (click one to go there):").withStyle(ChatFormatting.GRAY);
        for (Tier tier : Tier.values()) {
            List<Course> these = courses.stream().filter(c -> ranks.get(c.dimension()) == tier).toList();
            line.append(Component.literal("\n" + tier.title).withStyle(tier.color, ChatFormatting.BOLD));
            if (these.isEmpty()) {
                line.append(Component.literal("\n » none yet").withStyle(ChatFormatting.DARK_GRAY));
            }
            these.forEach(c -> courseLine(line, c, credits));
        }
        List<Course> unranked = courses.stream().filter(c -> !ranks.containsKey(c.dimension())).toList();
        if (!unranked.isEmpty()) {
            line.append(Component.literal("\nUnranked").withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD));
            unranked.forEach(c -> courseLine(line, c, credits));
        }
        source.sendSuccess(() -> line, false);
        return courses.size();
    }

    private static void courseLine(MutableComponent line, Course c, java.util.Map<String, List<String>> credits) {
        // The whole entry (arrow, name and credits) is one click target; hovering shows the command
        // and, for maps credited to more than one person, who did what.
        List<String> roles = credits.get(c.dimension());
        MutableComponent hover = Component.literal("/race " + c.key()).withStyle(ChatFormatting.GRAY);
        if (roles != null) {
            roles.forEach(r -> hover.append(Component.literal("\n")).append(credit(r)));
        }
        MutableComponent entry = Component.empty().withStyle(s -> s
                .withClickEvent(new ClickEvent.RunCommand("/race " + c.key()))
                .withHoverEvent(new HoverEvent.ShowText(hover)));
        entry.append(Component.literal(" » ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(c.title()).withStyle(ChatFormatting.WHITE));
        if (roles != null) {
            // One line: "by Skinmanjoe & Itsj5j6j7", the names in Propwash Yellow.
            List<String> names = roles.stream().map(r -> {
                int by = r.lastIndexOf(" by ");
                return by < 0 ? r : r.substring(by + 4);
            }).distinct().toList();
            entry.append(Component.literal(" by ").withStyle(ChatFormatting.GRAY));
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    entry.append(Component.literal(" & ").withStyle(ChatFormatting.GRAY));
                }
                entry.append(Component.literal(names.get(i)).withStyle(s -> s.withColor(NAME_COLOR)));
            }
        } else if (!c.owner().isBlank()) {
            entry.append(Component.literal(" by " + c.owner()).withStyle(ChatFormatting.GRAY));
        }
        line.append(Component.literal("\n")).append(entry);
    }

    /** Propwash Yellow, for the names in credits. */
    private static final int NAME_COLOR = 0xFFBB00;

    /** "Uncrashed map by SHO": the role grey, the name (after the last " by ") in Propwash Yellow. */
    private static MutableComponent credit(String text) {
        int by = text.lastIndexOf(" by ");
        if (by < 0) {
            return Component.literal(text).withStyle(ChatFormatting.GRAY);
        }
        return Component.literal(text.substring(0, by + 4)).withStyle(ChatFormatting.GRAY)
                .append(Component.literal(text.substring(by + 4)).withStyle(s -> s.withColor(NAME_COLOR)));
    }

    /** Maps credited to more than one person, by dimension: "credits.<namespace>.<path> = <line>|<line>...". */
    private static java.util.Map<String, List<String>> credits() {
        java.util.Map<String, List<String>> credits = new java.util.HashMap<>();
        byDimension("credits.").forEach((dimension, text) -> {
            List<String> lines = java.util.Arrays.stream(text.split("\\|")).map(String::strip).filter(l -> !l.isEmpty()).toList();
            if (!lines.isEmpty()) {
                credits.put(dimension, lines);
            }
        });
        return credits;
    }

    /** /rtp on the race server: a random course from the /race list, on another map than the player's if there is one. */
    private static int random(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        List<Course> courses = courses(source.getServer());
        String here = player.level().dimension().identifier().toString();
        List<Course> elsewhere = courses.stream().filter(c -> !c.dimension().equals(here)).toList();
        List<Course> pool = elsewhere.isEmpty() ? courses : elsewhere;
        if (pool.isEmpty()) {
            source.sendFailure(Component.literal("No race courses here yet."));
            return 0;
        }
        return go(source, pool.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(pool.size())));
    }

    private static int go(CommandSourceStack source, String typed) {
        Course course = find(source.getServer(), typed);
        if (course == null) {
            source.sendFailure(Component.literal("There's no race course called " + typed.strip() + " here. /race lists them."));
            return 0;
        }
        return go(source, course);
    }

    private static int go(CommandSourceStack source, Course course) {
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
        Start start = start(source.getServer(), course);
        ServerLevel level = source.getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                Identifier.parse(start != null ? start.dimension() : course.dimension())));
        if (level == null) {
            source.sendFailure(Component.literal("That course is in a world that isn't loaded."));
            return 0;
        }
        if (start != null) {
            player.teleportTo(level, start.x(), start.y(), start.z(), Set.of(), start.yaw(), start.pitch(), true);
        } else if (course.world()) {
            // The ground at 0 0, where /world create worlds have their spawn. Load the chunk first: an
            // unloaded chunk's height is the world's bottom.
            level.getChunk(0, 0);
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0);
            player.teleportTo(level, 0.5, y, 0.5, Set.of(), 0, 0, true);
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
        List<String> roles = credits().get(course.dimension());
        MutableComponent arrived = Component.literal("At the start of " + (roles == null ? course.label() : course.title()) + ". Good luck!")
                .withStyle(ChatFormatting.GRAY);
        if (roles != null) {
            roles.forEach(r -> arrived.append(Component.literal("\n")).append(credit(r)));
        }
        source.sendSuccess(() -> arrived, false);
        return 1;
    }
}
