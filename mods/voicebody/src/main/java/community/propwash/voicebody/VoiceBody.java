package community.propwash.voicebody;

import de.lardtom.propwash.drone.DronePiloting;
import de.lardtom.propwash.drone.PilotMode;
import de.lardtom.propwash.drone.QuadcopterEntity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where every player is, and where their camera is while they fly a drone in FPV, taken on the server
 * thread each tick for VoiceBodyPlugin, which runs on Simple Voice Chat's own thread.
 * config/voicebody.properties: enabled (true/false, for this server), read again when it changes.
 */
public final class VoiceBody implements ModInitializer {
    static final Logger LOGGER = LoggerFactory.getLogger("voicebody");
    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("voicebody.properties");

    /** A player's body, and their drone while it carries their camera (FPV; null otherwise). */
    record Spot(UUID id, ServerLevel level, Vec3 body, Vec3 camera) {
    }

    // Propwash is on every server with this mod, but isn't in fabric.mod.json: Propwash's join check
    // treats every mod that names it there (depends, recommends or suggests) as an add-on players must
    // install too, even a server-only one like this. Without Propwash nobody flies, so voices stay put.
    private static final boolean PROPWASH = FabricLoader.getInstance().isModLoaded("propwash");
    private static volatile Map<UUID, Spot> spots = Map.of();
    private static volatile boolean enabled = true;
    private static long configModified = -1;
    private static int ticks;

    @Override
    public void onInitialize() {
        readConfig();
        ServerTickEvents.END_SERVER_TICK.register(VoiceBody::tick);
    }

    static Map<UUID, Spot> spots() {
        return spots;
    }

    static boolean enabled() {
        return enabled;
    }

    private static void tick(MinecraftServer server) {
        if (++ticks % 100 == 0) {
            readConfig();
        }
        Map<UUID, Spot> now = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.level();
            Vec3 camera = PROPWASH ? camera(player, level) : null;
            now.put(player.getUUID(), new Spot(player.getUUID(), level, player.position(), camera));
        }
        spots = now;
    }

    // Where the player's camera is while they fly their drone in FPV, else null. Only called with
    // Propwash loaded (its classes are only looked up here).
    private static Vec3 camera(ServerPlayer player, ServerLevel level) {
        boolean fpv = DronePiloting.viewOf(player.getUUID()).map(view -> view.mode() == PilotMode.FPV).orElse(false);
        return !fpv ? null : DronePiloting.pilotedBy(player.getUUID())
                .filter(d -> d.isAlive() && d.level() == level)
                .map(QuadcopterEntity::position)
                .orElse(null);
    }

    private static void readConfig() {
        try {
            if (!Files.exists(CONFIG)) {
                return;
            }
            long modified = Files.getLastModifiedTime(CONFIG).toMillis();
            if (modified == configModified) {
                return;
            }
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(CONFIG)) {
                p.load(in);
            }
            enabled = Boolean.parseBoolean(p.getProperty("enabled", "true").trim());
            configModified = modified;
            LOGGER.info("Voice by the body in FPV {}", enabled ? "on" : "off");
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't read {}; keeping the settings as they were", CONFIG, e);
        }
    }
}
