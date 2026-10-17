package community.propwash.tweaks;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/**
 * /mydrone: brings the player's own Propwash drone to them. No arguments, so nothing to inject: it
 * only looks for drones tagged with the player's own name (Propwash's pw_owner_<name>, the name from
 * the server), in the dimension (map) they're in and loaded, the nearest one, never while it's
 * flying, at most every 5 seconds.
 */
public final class Tweaks implements ModInitializer {
    private static final long COOLDOWN_MS = 5000;
    private static final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("mydrone").executes(context -> myDrone(context.getSource()))));
    }

    private static int myDrone(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        Long last = lastUse.get(player.getUUID());
        if (last != null && now - last < COOLDOWN_MS) {
            source.sendFailure(Component.literal("Wait a few seconds before using /mydrone again."));
            return 0;
        }
        lastUse.put(player.getUUID(), now);

        ServerLevel level = player.level();
        String owner = "pw_owner_" + player.getGameProfile().name();
        Entity nearest = null;
        for (Entity entity : level.getAllEntities()) {
            if (entity.isAlive() && entity.entityTags().contains(owner)
                    && "propwash:drone".equals(EntityType.getKey(entity.getType()).toString())
                    && (nearest == null || entity.distanceToSqr(player) < nearest.distanceToSqr(player))) {
                nearest = entity;
            }
        }
        if (nearest == null) {
            source.sendFailure(Component.literal("No drone of yours here (it has to be on this map and loaded)."));
            return 0;
        }
        if (nearest.entityTags().contains("pw_flying")) {
            source.sendFailure(Component.literal("Your drone is flying; land it first."));
            return 0;
        }
        // About a block and a half in front of the player, at rest.
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        flat = flat.lengthSqr() < 1.0E-4 ? Vec3.ZERO : flat.normalize().scale(1.5);
        Vec3 to = player.position().add(flat).add(0, 0.5, 0);
        nearest.teleportTo(level, to.x, to.y, to.z, Set.of(), player.getYRot(), 0, true);
        nearest.setDeltaMovement(Vec3.ZERO);
        source.sendSuccess(() -> Component.literal("Your drone is here."), false);
        return 1;
    }
}
