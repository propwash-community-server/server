package community.propwash.tweaks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.lardtom.propwash.drone.QuadcopterEntity;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.slf4j.LoggerFactory;

/**
 * Island maps: whoever falls below a map's line in config/tweaks.properties,
 * "below.<namespace>.<path> = <y>" (e.g. below.worlds.gilbert_pro = -75), is put back at that map's race
 * start, and so is a drone (flying or not; its pilot sees it there at once). Read on start.
 *
 * The race start is where /race takes you: the spot pinned with /race setstart (for the map itself or
 * for its course with the lowest number), else just in front of that course's start gate, else the
 * ground at 0 0. Spectators are left alone.
 */
final class VoidReturn {
    /** Ticks between two checks. */
    private static final int EVERY = 5;
    /** Blocks in front of the start gate (as /race). */
    private static final double BEFORE_GATE = 3;

    /** Where to go back to, and facing which way. */
    private record Spot(double x, double y, double z, float yaw, float pitch) {
    }

    private static final Map<ResourceKey<Level>, Double> limits = new HashMap<>();
    private static int ticks;

    private VoidReturn() {
    }

    static void init() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("tweaks.properties");
        if (!Files.exists(file)) {
            return;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        } catch (IOException e) {
            return;
        }
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith("below.")) {
                continue;
            }
            // "below.worlds.gilbert_pro": a dimension's namespace, then its path ("." for its ":").
            String dimension = key.substring("below.".length());
            int dot = dimension.indexOf('.');
            try {
                limits.put(ResourceKey.create(Registries.DIMENSION,
                                Identifier.parse(dimension.substring(0, dot) + ":" + dimension.substring(dot + 1))),
                        Double.parseDouble(properties.getProperty(key).strip()));
            } catch (RuntimeException e) {
                LoggerFactory.getLogger("tweaks").warn("tweaks.properties: {} isn't a map and a Y level", key);
            }
        }
        if (!limits.isEmpty()) {
            ServerTickEvents.END_SERVER_TICK.register(VoidReturn::tick);
        }
    }

    private static void tick(MinecraftServer server) {
        if (++ticks % EVERY != 0) {
            return;
        }
        limits.forEach((key, limit) -> {
            ServerLevel level = server.getLevel(key);
            if (level == null) {
                return;
            }
            List<ServerPlayer> players = new ArrayList<>();
            for (ServerPlayer p : level.players()) {
                if (p.getY() < limit && !p.isSpectator()) {
                    players.add(p);
                }
            }
            List<QuadcopterEntity> drones = new ArrayList<>();
            for (Entity e : level.getAllEntities()) {
                if (e.isAlive() && e.getY() < limit && e instanceof QuadcopterEntity drone) {
                    drones.add(drone);
                }
            }
            if (players.isEmpty() && drones.isEmpty()) {
                return;
            }
            Spot spot = spot(server, level);
            for (ServerPlayer p : players) {
                p.teleportTo(level, spot.x(), spot.y(), spot.z(), Set.of(), spot.yaw(), spot.pitch(), true);
                p.setDeltaMovement(Vec3.ZERO);
                p.resetFallDistance();
                p.sendSystemMessage(Component.literal("Back to the start.").withStyle(ChatFormatting.GRAY), true);
            }
            for (QuadcopterEntity drone : drones) {
                // Facing the start's way, level: Propwash's "forward" is -Z turned by the quaternion.
                Quaternionf facing = new Quaternionf().rotationY((float) Math.toRadians(180.0 - spot.yaw()));
                drone.resetTo(new Vec3(spot.x(), spot.y() + 0.5, spot.z()), facing);
                drone.pilot().map(server.getPlayerList()::getPlayer).ifPresent(pilot ->
                        pilot.sendSystemMessage(Component.literal("Your drone is back at the start.").withStyle(ChatFormatting.GRAY), true));
            }
        });
    }

    /** The map's race start, as /race lands you. */
    private static Spot spot(MinecraftServer server, ServerLevel level) {
        String dimension = level.dimension().identifier().toString();
        Path data = server.getWorldPath(LevelResource.ROOT).resolve("data");
        JsonObject starts = read(data.resolve("propwash_race_starts.json"));
        // The map's courses, lowest number first, with each one's first gate.
        int course = Integer.MAX_VALUE;
        JsonObject gate = null;
        JsonObject races = read(data.resolve("propwash_race.json"));
        if (races.get("courses") instanceof JsonArray all) {
            for (JsonElement e : all) {
                try {
                    JsonObject c = e.getAsJsonObject();
                    if (!dimension.equals(c.get("dimension").getAsString()) || c.get("id").getAsInt() >= course) {
                        continue;
                    }
                    JsonObject first = null;
                    for (JsonElement g : c.getAsJsonArray("gates")) {
                        JsonObject o = g.getAsJsonObject();
                        if (first == null || o.get("index").getAsInt() < first.get("index").getAsInt()) {
                            first = o;
                        }
                    }
                    if (first != null) {
                        course = c.get("id").getAsInt();
                        gate = first;
                    }
                } catch (RuntimeException ex) {
                    // not a course we can use
                }
            }
        }
        Spot pinned = pinned(starts.get(dimension), dimension);
        if (pinned == null && gate != null) {
            pinned = pinned(starts.get(String.valueOf(course)), dimension);
        }
        if (pinned != null) {
            return pinned;
        }
        if (gate != null) {
            float yaw = gate.get("yaw").getAsFloat();
            double rad = Math.toRadians(yaw);
            return new Spot(gate.get("x").getAsDouble() + Math.sin(rad) * BEFORE_GATE, gate.get("y").getAsDouble(),
                    gate.get("z").getAsDouble() - Math.cos(rad) * BEFORE_GATE, yaw, 0);
        }
        level.getChunk(0, 0);
        return new Spot(0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0), 0.5, 0, 0);
    }

    /** A /race setstart spot, if it's on this map. */
    private static Spot pinned(JsonElement e, String dimension) {
        if (e == null || !e.isJsonObject()) {
            return null;
        }
        JsonObject o = e.getAsJsonObject();
        try {
            if (!dimension.equals(o.get("dimension").getAsString())) {
                return null;
            }
            return new Spot(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble(),
                    o.get("yaw").getAsFloat(), o.get("pitch").getAsFloat());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static JsonObject read(Path file) {
        if (!Files.exists(file)) {
            return new JsonObject();
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            return new JsonObject();
        }
    }
}
