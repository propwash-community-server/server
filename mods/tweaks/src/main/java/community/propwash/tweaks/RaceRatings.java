package community.propwash.tweaks;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Difficulty ratings for the race courses, from the players who fly them (race server only).
 *
 * Every lap Propwash's server accepts (RaceLapMixin: checked by the server, assisted or not) counts for
 * that player on that course. At the 10th, the player is asked once "How difficult would you rank the
 * map: <name>?" with five clickable stars (/race rate <course number> <1-5>). Rating needs those 10 laps
 * on that course; a player may rate again later (the stars are clickable as long as the line is in chat,
 * or type /race rate) and the newest rating counts. Staff see the averages with /race ratings
 * (propwash.race.ratings).
 *
 * The 10 can be changed with "ratings.laps = <n>" in config/tweaks.properties (a test server).
 *
 * Kept in world/data/propwash_race_ratings.json: "laps" (course number -> player UUID -> laps counted
 * since this was added), "asked" (course -> players already asked) and "ratings" (course -> player ->
 * {name, stars, date}).
 */
public final class RaceRatings {
    /** Laps of one course before a player is asked, and may rate it: "ratings.laps" in config/tweaks.properties, else 10. */
    static volatile int LAPS = 10;
    static final String VIEW = "propwash.race.ratings";
    private static final String[] WORDS = {"Very easy", "Easy", "Medium", "Hard", "Very hard"};
    /** Propwash Yellow. */
    private static final int STAR_COLOR = 0xFFBB00;
    /** Set on the race server only (Race, with "map = race"); elsewhere laps aren't counted. */
    static volatile boolean enabled;
    private static JsonObject data;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("tweaks");

    private RaceRatings() {
    }

    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data/propwash_race_ratings.json");
    }

    private static JsonObject data(MinecraftServer server) {
        if (data == null) {
            Path file = file(server);
            data = new JsonObject();
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file)) {
                    data = JsonParser.parseReader(reader).getAsJsonObject();
                } catch (IOException | RuntimeException e) {
                    LOG.warn("Couldn't read {}, starting over: {}", file, e.toString());
                }
            }
        }
        return data;
    }

    private static void save(MinecraftServer server) {
        Path file = file(server);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(data));
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.warn("Couldn't save {}: {}", file, e.toString());
        }
    }

    private static JsonObject section(JsonObject root, String key) {
        JsonElement e = root.get(key);
        if (e == null || !e.isJsonObject()) {
            JsonObject o = new JsonObject();
            root.add(key, o);
            return o;
        }
        return e.getAsJsonObject();
    }

    private static int laps(MinecraftServer server, int course, ServerPlayer player) {
        JsonElement e = section(section(data(server), "laps"), String.valueOf(course)).get(player.getUUID().toString());
        return e == null ? 0 : e.getAsInt();
    }

    /** A lap of course number {@code course} that Propwash's server accepted (RaceLapMixin). */
    public static synchronized void lap(ServerPlayer player, int course) {
        MinecraftServer server = player.level().getServer();
        if (!enabled || server == null) {
            return;
        }
        String id = String.valueOf(course);
        String uuid = player.getUUID().toString();
        JsonObject laps = section(section(data(server), "laps"), id);
        int n = (laps.has(uuid) ? laps.get(uuid).getAsInt() : 0) + 1;
        laps.addProperty(uuid, n);
        boolean ask = false;
        if (n >= LAPS && !section(section(data, "ratings"), id).has(uuid)) {
            JsonObject asked = section(section(data, "asked"), id);
            if (!asked.has(uuid)) {
                asked.addProperty(uuid, true);
                ask = true;
            }
        }
        save(server);
        if (ask) {
            String name = Race.title(server, course);
            player.sendSystemMessage(Component.literal("You've flown " + n + " laps of " + name + "!").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal("\nHow difficult would you rank the map: ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(name).withStyle(s -> s.withColor(STAR_COLOR)))
                    .append(Component.literal("?\n").withStyle(ChatFormatting.WHITE))
                    .append(stars(course, 0)));
        }
    }

    /** Five clickable stars (the first {@code chosen} filled), each running /race rate <course> <n>. */
    private static MutableComponent stars(int course, int chosen) {
        MutableComponent line = Component.literal(" ");
        for (int n = 1; n <= 5; n++) {
            int stars = n;
            MutableComponent hover = Component.literal("★".repeat(n)).withStyle(s -> s.withColor(STAR_COLOR))
                    .append(Component.literal("☆".repeat(5 - n)).withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(" " + n + " - " + WORDS[n - 1] + "\nClick to rate").withStyle(ChatFormatting.GRAY));
            line.append(Component.literal(n <= chosen ? "★" : "☆").withStyle(s -> s
                    .withColor(STAR_COLOR).withBold(true)
                    .withClickEvent(new ClickEvent.RunCommand("/race rate " + course + " " + stars))
                    .withHoverEvent(new HoverEvent.ShowText(hover))));
            line.append(Component.literal(" "));
        }
        return line.append(Component.literal(" (1 = very easy, 5 = very hard)").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** /race rate <course number> <1-5>. */
    static synchronized int rate(CommandSourceStack source, int course, int stars) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (!Race.exists(server, course)) {
            source.sendFailure(Component.literal("There's no race course number " + course + " here."));
            return 0;
        }
        String name = Race.title(server, course);
        int laps = laps(server, course, player);
        if (laps < LAPS) {
            source.sendFailure(Component.literal("Fly " + LAPS + " laps of " + name + " first (" + laps + " so far), then you can rate it."));
            return 0;
        }
        JsonObject rating = new JsonObject();
        rating.addProperty("name", player.getGameProfile().name());
        rating.addProperty("stars", stars);
        rating.addProperty("date", java.time.LocalDate.now().toString());
        section(section(data(server), "ratings"), String.valueOf(course)).add(player.getUUID().toString(), rating);
        save(server);
        source.sendSuccess(() -> Component.literal("Thanks! You rated " + name + " ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal("★".repeat(stars)).withStyle(s -> s.withColor(STAR_COLOR)))
                .append(Component.literal("☆".repeat(5 - stars)).withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(" (" + WORDS[stars - 1] + "). Click another star to change it:\n").withStyle(ChatFormatting.GRAY))
                .append(stars(course, stars)), false);
        return 1;
    }

    /** /race ratings: every rated course, its average and how many rated it. */
    static synchronized int list(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        record Row(String name, double average, int count) {
        }
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, JsonElement> course : section(data(server), "ratings").entrySet()) {
            JsonObject players = course.getValue().getAsJsonObject();
            if (players.isEmpty()) {
                continue;
            }
            double sum = 0;
            for (Map.Entry<String, JsonElement> p : players.entrySet()) {
                sum += p.getValue().getAsJsonObject().get("stars").getAsInt();
            }
            int id;
            try {
                id = Integer.parseInt(course.getKey());
            } catch (NumberFormatException e) {
                continue;
            }
            rows.add(new Row(Race.title(server, id), sum / players.size(), players.size()));
        }
        if (rows.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No difficulty ratings yet (players are asked after " + LAPS + " laps of a course).")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        rows.sort(Comparator.comparingDouble(Row::average).thenComparing(Row::name));
        MutableComponent out = Component.literal("Difficulty ratings (1 = very easy, 5 = very hard):").withStyle(ChatFormatting.GRAY);
        for (Row r : rows) {
            out.append(Component.literal("\n » ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(r.name()).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(String.format(java.util.Locale.ROOT, " %.1f★", r.average())).withStyle(s -> s.withColor(STAR_COLOR)))
                    .append(Component.literal(" (" + r.count() + (r.count() == 1 ? " rating)" : " ratings)")).withStyle(ChatFormatting.GRAY));
        }
        source.sendSuccess(() -> out, false);
        return rows.size();
    }
}
