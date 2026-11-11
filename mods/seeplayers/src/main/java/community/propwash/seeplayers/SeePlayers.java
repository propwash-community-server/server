package community.propwash.seeplayers;

import community.propwash.seeplayers.mixin.ChunkMapAccessor;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Players invisible to each other until a relog: the client only shows a player it has a tab list entry
 * for, and the proxy's TAB (global playerlist) sometimes takes an entry away while players switch
 * servers at the same moment (everyone kicked to the hub by a restart) or someone un-vanishes. The
 * server can't tell, so shortly after each join and un-vanish it hands both sides their entries again
 * and sends the player bodies afresh (a body sent without an entry is dropped by the client for good).
 * Vanish is respected: only pairs that may see each other (Entity.broadcastToPlayer, which the Vanish
 * mod decides for players).
 */
public final class SeePlayers implements DedicatedServerModInitializer {
    /** Ticks after a join to heal: soon, and once more after TAB has surely settled. */
    private static final int[] AFTER_JOIN = {40, 200};
    private static final int[] AFTER_UNVANISH = {20, 100};

    private record Pending(UUID player, long dueTick) {
    }

    private static final List<Pending> PENDING = new ArrayList<>();
    private static long tick;

    @Override
    public void onInitializeServer() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> schedule(handler.getPlayer().getUUID(), AFTER_JOIN));
        ServerTickEvents.END_SERVER_TICK.register(SeePlayers::tick);
    }

    /** For VanishManagerMixin: someone just became visible. */
    public static void unvanished(UUID player) {
        schedule(player, AFTER_UNVANISH);
    }

    private static synchronized void schedule(UUID player, int[] delays) {
        for (int delay : delays) {
            PENDING.add(new Pending(player, tick + delay));
        }
    }

    private static void tick(MinecraftServer server) {
        List<UUID> due = new ArrayList<>();
        synchronized (SeePlayers.class) {
            tick++;
            PENDING.removeIf(p -> {
                if (p.dueTick() > tick) {
                    return false;
                }
                due.add(p.player());
                return true;
            });
        }
        for (UUID id : due) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null && !player.hasDisconnected()) {
                heal(server, player);
            }
        }
    }

    /** Everyone and player get each other's entries and bodies again, where they may see each other. */
    private static void heal(MinecraftServer server, ServerPlayer player) {
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other != player && !other.hasDisconnected()) {
                resend(other, player);
                resend(player, other);
            }
        }
    }

    private static void resend(ServerPlayer viewer, ServerPlayer target) {
        if (!target.broadcastToPlayer(viewer)) {
            return; // vanished from them: no entry, no body
        }
        // ADD_PLAYER only adds a missing entry (an existing one, with TAB's name and all, stays as it is).
        viewer.connection.send(new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED),
                List.of(target)));
        if (viewer.level() == target.level()
                && ((ChunkMapAccessor) target.level().getChunkSource().chunkMap).seeplayers$entityMap().get(target.getId()) instanceof Retrack tracked) {
            tracked.seeplayers$retrack(viewer);
        }
    }
}
