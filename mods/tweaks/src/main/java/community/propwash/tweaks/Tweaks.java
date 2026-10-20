package community.propwash.tweaks;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * /mydrone: brings the player's own Propwash drone to them. No arguments, so nothing to inject: it
 * only looks for drones tagged with the player's own name (Propwash's pw_owner_<name>, the name from
 * the server), in the dimension (map) they're in and loaded, the nearest one, never while it's
 * flying, at most every 5 seconds. /debugstick gives a debug stick in the plot world, where everyone
 * may use one (DebugStickMixin), only one at a time.
 *
 * Drones go with their owner: when a player leaves this server (logs off or switches server), their
 * drones (pw_owner_<name>) are removed from every loaded map, and a drone that loads later (a chunk
 * that wasn't loaded then) is removed if its owner isn't on this server.
 */
public final class Tweaks implements ModInitializer {
    private static final long COOLDOWN_MS = 5000;
    /** Ticks between firework rockets for one player (FireworkCooldownMixin). */
    public static final int FIREWORK_COOLDOWN_TICKS = 40;
    private static final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> stopWorldEditTimer());
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                {
                    dispatcher.register(Commands.literal("mydrone").executes(context -> myDrone(context.getSource())));
                    dispatcher.register(Commands.literal("debugstick").executes(context -> debugStick(context.getSource())));
                });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            String tag = OWNER_TAG + handler.getPlayer().getGameProfile().name();
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    if (isDrone(entity) && entity.entityTags().contains(tag)) {
                        server.execute(entity::discard);
                    }
                }
            }
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (!isDrone(entity)) {
                return;
            }
            MinecraftServer server = level.getServer();
            for (String tag : entity.entityTags()) {
                if (tag.startsWith(OWNER_TAG)
                        && server.getPlayerList().getPlayerByName(tag.substring(OWNER_TAG.length())) == null) {
                    // Not while the chunk is still loading its entities.
                    server.execute(entity::discard);
                    return;
                }
            }
        });
    }

    private static final String OWNER_TAG = "pw_owner_";

    /**
     * WorldEdit's progress messages ("still working...") run on a java.util.Timer made with new Timer(),
     * not a daemon thread, and never cancelled: once someone has used a slow WorldEdit command, Java can't
     * exit after the server has stopped, and systemd kills it after its stop timeout (2 minutes). Cancelled
     * here once everything is saved. By name, so nothing breaks without WorldEdit or if it changes.
     */
    private static void stopWorldEditTimer() {
        try {
            var field = Class.forName("com.sk89q.worldedit.command.util.FutureProgressListener", true,
                    Tweaks.class.getClassLoader()).getDeclaredField("timer");
            field.setAccessible(true);
            ((java.util.Timer) field.get(null)).cancel();
        } catch (ReflectiveOperationException | ClassCastException | NullPointerException | LinkageError ignored) {
            // No WorldEdit, or it no longer has this timer.
        }
    }

    private static boolean isDrone(Entity entity) {
        return "propwash:drone".equals(EntityType.getKey(entity.getType()).toString());
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
        String owner = OWNER_TAG + player.getGameProfile().name();
        Entity nearest = null;
        for (Entity entity : level.getAllEntities()) {
            if (entity.isAlive() && entity.entityTags().contains(owner) && isDrone(entity)
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

    private static int debugStick(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        if (!"fabricplots:plots".equals(player.level().dimension().identifier().toString())) {
            source.sendFailure(Component.literal("The debug stick only works in the plot world."));
            return 0;
        }
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).is(Items.DEBUG_STICK)) {
                source.sendFailure(Component.literal("You already have a debug stick."));
                return 0;
            }
        }
        inventory.add(new ItemStack(Items.DEBUG_STICK));
        source.sendSuccess(() -> Component.literal(
                "Debug stick: left-click a block on your plot to pick a property, right-click to change it."), false);
        return 1;
    }
}
