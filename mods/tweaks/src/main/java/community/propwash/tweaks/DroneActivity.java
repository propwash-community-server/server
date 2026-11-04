package community.propwash.tweaks;

import de.lardtom.propwash.drone.DronePiloting;
import de.lardtom.propwash.drone.QuadcopterEntity;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/**
 * Flying a drone counts as being active: a pilot hardly moves their own body, so the AFK checks,
 * which look at the body, took them for AFK. Once a second, a player whose Propwash drone moved
 * more than half a block since the last check is active:
 * - Essential Commands' automatic AFK (after 15 minutes) starts counting again, and its AFK ends;
 * - their score in the dummy objective propwash.drone goes up by one. The proxy's /afk
 *   (mods/crossserver Afk) reads it over rcon and ends /afk when it changes.
 * Only with Propwash loaded; Essential Commands is looked up by name and is optional.
 */
final class DroneActivity {
    static final String OBJECTIVE = "propwash.drone";
    private static final int EVERY_TICKS = 20;
    /** Farther than this (blocks) since the last check counts as moving. */
    private static final double MOVED = 0.5;

    /** Where each pilot's drone was at the last check. */
    private static final Map<UUID, Vec3> last = new HashMap<>();
    private static int ticks;

    private DroneActivity() {
    }

    static void init() {
        if (!FabricLoader.getInstance().isModLoaded("propwash")) {
            return;
        }
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++ticks % EVERY_TICKS == 0) {
                check(server);
            }
        });
    }

    private static void check(MinecraftServer server) {
        Map<UUID, Vec3> now = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            QuadcopterEntity drone = DronePiloting.pilotedBy(player.getUUID()).filter(QuadcopterEntity::isAlive).orElse(null);
            if (drone == null) {
                continue;
            }
            Vec3 pos = drone.position();
            now.put(player.getUUID(), pos);
            Vec3 before = last.get(player.getUUID());
            if (before != null && before.distanceTo(pos) > MOVED) {
                active(server, player);
            }
        }
        last.clear();
        last.putAll(now);
    }

    private static void active(MinecraftServer server, ServerPlayer player) {
        EssentialCommands.active(player);
        Scoreboard scoreboard = server.getScoreboard();
        Objective objective = scoreboard.getObjective(OBJECTIVE);
        if (objective == null) {
            objective = scoreboard.addObjective(OBJECTIVE, ObjectiveCriteria.DUMMY, Component.literal("Drone flying"),
                    ObjectiveCriteria.RenderType.INTEGER, false, null);
        }
        scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly(player.getGameProfile().name()), objective).increment();
    }

    /** Essential Commands' PlayerData, by name: updateLastActionTick(), and setAfk(false) if AFK. */
    private static final class EssentialCommands {
        private static final MethodHandle PLAYER_DATA, UPDATE, IS_AFK, SET_AFK;

        static {
            MethodHandle playerData = null, update = null, isAfk = null, setAfk = null;
            try {
                ClassLoader loader = DroneActivity.class.getClassLoader();
                Class<?> access = Class.forName("com.fibermc.essentialcommands.access.ServerPlayerEntityAccess", true, loader);
                Class<?> data = Class.forName("com.fibermc.essentialcommands.playerdata.PlayerData", true, loader);
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                playerData = lookup.findVirtual(access, "ec$getPlayerData", MethodType.methodType(data));
                update = lookup.findVirtual(data, "updateLastActionTick", MethodType.methodType(void.class));
                isAfk = lookup.findVirtual(data, "isAfk", MethodType.methodType(boolean.class));
                setAfk = lookup.findVirtual(data, "setAfk", MethodType.methodType(void.class, boolean.class));
            } catch (ReflectiveOperationException | LinkageError e) {
                playerData = null; // no Essential Commands, or it changed: only the scoreboard
            }
            PLAYER_DATA = playerData;
            UPDATE = update;
            IS_AFK = isAfk;
            SET_AFK = setAfk;
        }

        static void active(ServerPlayer player) {
            if (PLAYER_DATA == null) {
                return;
            }
            try {
                Object data = PLAYER_DATA.invoke(player);
                if (data == null) {
                    return;
                }
                UPDATE.invoke(data);
                if ((boolean) IS_AFK.invoke(data)) {
                    SET_AFK.invoke(data, false);
                }
            } catch (Throwable ignored) {
                // Not an Essential Commands player (yet), or it changed.
            }
        }
    }
}
