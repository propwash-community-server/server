package community.propwash.voicebody;

import de.lardtom.propwash.drone.DronePiloting;
import de.lardtom.propwash.drone.PilotMode;
import de.lardtom.propwash.drone.QuadcopterEntity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
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
    /** /voicemute: until when only admins may speak (Long.MAX_VALUE for until turned off); 0 = off. */
    private static volatile long muteUntil;
    /** Players with PERMISSION, read each tick while muted: they keep speaking while everyone else is muted. */
    private static volatile Set<UUID> admins = Set.of();
    /** /voicemute, and still being heard while it's on (LuckPerms; admins have it through "*"). */
    static final String PERMISSION = "propwash.voicemute";

    @Override
    public void onInitialize() {
        readConfig();
        ServerTickEvents.END_SERVER_TICK.register(VoiceBody::tick);
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(
                Commands.literal("voicemute").requires(s -> permitted(s, PERMISSION))
                        .then(Commands.literal("on")
                                .executes(c -> mute(c.getSource(), 0))
                                .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 1440))
                                        .executes(c -> mute(c.getSource(), IntegerArgumentType.getInteger(c, "minutes")))))
                        .then(Commands.literal("off").executes(c -> unmute(c.getSource())))
                        .executes(c -> status(c.getSource()))));
    }

    /** Whether only admins may speak right now. */
    static boolean muted() {
        return muteUntil != 0 && System.currentTimeMillis() < muteUntil;
    }

    static boolean isAdmin(UUID id) {
        return admins.contains(id);
    }

    private static int mute(CommandSourceStack source, int minutes) {
        muteUntil = minutes == 0 ? Long.MAX_VALUE : System.currentTimeMillis() + minutes * 60_000L;
        source.sendSuccess(() -> Component.literal(minutes == 0
                ? "Voice chat muted for everyone but admins. /voicemute off to undo."
                : "Voice chat muted for everyone but admins for " + minutes + " min."), true);
        LOGGER.info("Voice chat muted for non-admins by {} ({} min)", source.getTextName(), minutes);
        return 1;
    }

    private static int unmute(CommandSourceStack source) {
        muteUntil = 0;
        source.sendSuccess(() -> Component.literal("Voice chat unmuted."), true);
        LOGGER.info("Voice chat unmuted by {}", source.getTextName());
        return 1;
    }

    private static int status(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(muted() ? "Voice chat is muted for non-admins." : "Voice chat is not muted."), false);
        return 1;
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
        Set<UUID> op = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.level();
            if (muted() && permitted(player.createCommandSourceStack(), PERMISSION)) {
                op.add(player.getUUID());
            }
            Vec3 camera = PROPWASH ? camera(player, level) : null;
            now.put(player.getUUID(), new Spot(player.getUUID(), level, player.position(), camera));
        }
        spots = now;
        admins = op;
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

    // LuckPerms through the fabric-permissions-api (Vanilla Permissions brings it), looked up by
    // reflection so the mod also runs without it; then only ops (level 2) have the permission.
    private static java.lang.invoke.MethodHandle permissionCheck;

    static {
        try {
            permissionCheck = java.lang.invoke.MethodHandles.publicLookup().findStatic(
                    Class.forName("me.lucko.fabric.api.permissions.v0.Permissions"), "check",
                    java.lang.invoke.MethodType.methodType(boolean.class,
                            net.minecraft.commands.SharedSuggestionProvider.class, String.class, boolean.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            permissionCheck = null;
        }
    }

    static boolean permitted(CommandSourceStack source, String permission) {
        if (permissionCheck == null) {
            return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        }
        try {
            return (boolean) permissionCheck.invokeExact((net.minecraft.commands.SharedSuggestionProvider) source, permission, false);
        } catch (Throwable e) {
            return false;
        }
    }
}
