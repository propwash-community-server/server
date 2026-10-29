package community.propwash.tweaks;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
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
 * flying, at most every 5 seconds. Right-clicking an entity (an armor stand, a mannequin...) tagged
 * propwash.map.<name> runs /map <name> for the player, before claim protection refuses the click. /debugstick gives a debug stick in the plot world, where everyone
 * may use one (DebugStickMixin), only one at a time. /flyspeed <0-10> sets the player's flying speed
 * (up to the highest propwash.flyspeed.<1-10> the player has). /enderchest, /ec, /echest open the
 * player's ender chest (propwash.enderchest).
 *
 * Drones go with their owner: when a player leaves this server (logs off or switches server), their
 * drones (pw_owner_<name>) are removed from every loaded map, and a drone that loads later (a chunk
 * that wasn't loaded then) is removed if its owner isn't on this server.
 *
 * Staff watch: with staffwatch-folder in config/tweaks.properties (the proxy's plugins/staffwatch,
 * mods/staffwatch), every block a player breaks or places (buckets too) in the plot world while in
 * FabricPlots' /plot admin mode is added to plots-YYYY-MM.jsonl there, with whose plot it was.
 */
public final class Tweaks implements ModInitializer {
    private static final long COOLDOWN_MS = 5000;
    /** Ticks between firework rockets for one player (FireworkCooldownMixin). */
    public static final int FIREWORK_COOLDOWN_TICKS = 40;
    private static final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        // Map signposts: handled before Flan (which refuses touching armor stands on fpv), and only for tagged entities.
        Identifier early = Identifier.fromNamespaceAndPath("tweaks", "map_stands");
        UseEntityCallback.EVENT.addPhaseOrdering(early, Event.DEFAULT_PHASE);
        // Flan checks in its own early phase; ours goes first (no effect without Flan).
        UseEntityCallback.EVENT.addPhaseOrdering(early, Identifier.fromNamespaceAndPath("flan", "events"));
        UseEntityCallback.EVENT.register(early, (player, world, hand, entity, hit) -> mapStand(player, hand, entity));
        StaffWatch.init();
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> stopWorldEditTimer());
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                {
                    dispatcher.register(Commands.literal("mydrone").executes(context -> myDrone(context.getSource())));
                    dispatcher.register(Commands.literal("debugstick").executes(context -> debugStick(context.getSource())));
                    dispatcher.register(Commands.literal("flyspeed").requires(source -> maxFlySpeed(source) > 0)
                            .executes(context -> flySpeed(context.getSource(), 1))
                            .then(Commands.argument("speed", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(0, 10))
                                    .executes(context -> flySpeed(context.getSource(),
                                            com.mojang.brigadier.arguments.FloatArgumentType.getFloat(context, "speed")))));
                    var enderChest = dispatcher.register(Commands.literal("enderchest")
                            .requires(source -> permitted(source, ENDERCHEST))
                            .executes(context -> enderChest(context.getSource())));
                    for (String alias : new String[] {"ec", "echest"}) {
                        dispatcher.register(Commands.literal(alias).requires(enderChest.getRequirement())
                                .executes(context -> enderChest(context.getSource())));
                    }
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
    private static final String MAP_TAG = "propwash.map.";
    private static final Map<UUID, Long> lastStand = new ConcurrentHashMap<>();

    /** Right-click on an entity tagged propwash.map.<name>: /map <name> for that player (main hand, every 2 s at most). */
    private static InteractionResult mapStand(net.minecraft.world.entity.player.Player player, InteractionHand hand, Entity entity) {
        String map = null;
        for (String tag : entity.entityTags()) {
            if (tag.startsWith(MAP_TAG) && tag.substring(MAP_TAG.length()).matches("[a-z0-9_]+")) {
                map = tag.substring(MAP_TAG.length());
            }
        }
        if (map == null || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.PASS;
        }
        if (hand == InteractionHand.MAIN_HAND) {
            long now = System.currentTimeMillis();
            Long last = lastStand.get(sp.getUUID());
            if (last == null || now - last >= 2000) {
                lastStand.put(sp.getUUID(), now);
                sp.level().getServer().getCommands().performPrefixedCommand(sp.createCommandSourceStack(), "map " + map);
            }
        }
        return InteractionResult.SUCCESS; // the stand itself isn't touched (no taking armor, no Flan message)
    }

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

    /**
     * /flyspeed <0-10>, like the hub's (EssentialsX): 1 is the normal flying speed, 10 ten times it;
     * no number: back to 1. Only changes the speed (flying itself is creative or /fly). Up to the
     * highest propwash.flyspeed.<1-10> the player has (LuckPerms, through the fabric-permissions-api
     * Vanilla Permissions brings); none: no /flyspeed.
     */
    private static int flySpeed(CommandSourceStack source, float speed) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        int max = maxFlySpeed(source);
        if (speed > max) {
            source.sendFailure(Component.literal("You can fly at up to /flyspeed " + max + "."));
            return 0;
        }
        player.getAbilities().setFlyingSpeed(NORMAL_FLYING_SPEED * speed);
        player.onUpdateAbilities();
        String shown = speed == Math.round(speed) ? String.valueOf(Math.round(speed)) : String.valueOf(speed);
        source.sendSuccess(() -> Component.literal("Flying speed " + shown + (speed == 1 ? " (normal)." : ". /flyspeed 1 for normal.")), false);
        return 1;
    }

    private static final String FLYSPEED = "propwash.flyspeed.";
    private static final String ENDERCHEST = "propwash.enderchest";

    /** /enderchest, /ec, /echest: the player's own ender chest, anywhere (propwash.enderchest). */
    private static int enderChest(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (id, inventory, p) -> net.minecraft.world.inventory.ChestMenu.threeRows(id, inventory, p.getEnderChestInventory()),
                Component.translatable("container.enderchest")));
        return 1;
    }
    private static final float NORMAL_FLYING_SPEED = 0.05f;
    private static java.lang.invoke.MethodHandle permissionCheck;

    static {
        try {
            permissionCheck = java.lang.invoke.MethodHandles.publicLookup().findStatic(
                    Class.forName("me.lucko.fabric.api.permissions.v0.Permissions"), "check",
                    java.lang.invoke.MethodType.methodType(boolean.class,
                            net.minecraft.commands.SharedSuggestionProvider.class, String.class, boolean.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            permissionCheck = null; // no permissions mod: only ops (level 2) get /flyspeed
        }
    }

    /** The highest propwash.flyspeed.<n> (1 to 10) the source has, 0 for none. */
    private static int maxFlySpeed(CommandSourceStack source) {
        for (int n = 10; n >= 1; n--) {
            if (permitted(source, FLYSPEED + n)) {
                return n;
            }
        }
        return 0;
    }

    private static boolean permitted(CommandSourceStack source, String permission) {
        if (permissionCheck == null) {
            return source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
        }
        try {
            return (boolean) permissionCheck.invokeExact((net.minecraft.commands.SharedSuggestionProvider) source, permission, false);
        } catch (Throwable e) {
            return false;
        }
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
