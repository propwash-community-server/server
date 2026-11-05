package community.propwash.tweaks;

import com.mojang.brigadier.CommandDispatcher;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * /dronetp: takes the player to their own Propwash drone (a player has one drone, tagged
 * pw_owner_<name>), on any map of this server, as long as it's not flying.
 * /dronetp <player> (propwash.dronetp.others, staff): to that player's drone, same rules.
 *
 * A drone landed far from its pilot's body is in a chunk the server has unloaded, so it isn't among
 * the entities: where each drone was when its chunk unloaded is kept (until it loads again or its
 * owner leaves), and /dronetp goes there, which loads it. /kill drone (KillDrone) uses the same.
 */
final class DroneTp {
    static final String OTHERS = "propwash.dronetp.others";

    /** Where an owner's drone was when its chunk unloaded. */
    record Spot(ResourceKey<Level> level, Vec3 pos, boolean flying) {
    }

    /** By owner name, for drones in unloaded chunks. */
    static final Map<String, Spot> unloaded = new ConcurrentHashMap<>();
    /** Owners whose unloaded drone /kill drone removed: it's discarded when it loads. */
    static final Set<String> killWhenLoaded = ConcurrentHashMap.newKeySet();

    private DroneTp() {
    }

    static void init() {
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            String owner = owner(entity);
            Entity.RemovalReason reason = entity.getRemovalReason();
            if (owner != null && (reason == null || !reason.shouldDestroy())) {
                unloaded.put(owner, new Spot(level.dimension(), entity.position(), entity.entityTags().contains("pw_flying")));
            }
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            String owner = owner(entity);
            if (owner == null) {
                return;
            }
            unloaded.remove(owner);
            if (killWhenLoaded.remove(owner)) {
                level.getServer().execute(entity::discard); // not while the chunk is still loading
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            String name = handler.getPlayer().getGameProfile().name();
            unloaded.remove(name);
            killWhenLoaded.remove(name);
        });
    }

    /** The owner's name of a Propwash drone, else null. */
    static String owner(Entity entity) {
        if (!Tweaks.isDrone(entity)) {
            return null;
        }
        for (String tag : entity.entityTags()) {
            if (tag.startsWith(Tweaks.OWNER_TAG)) {
                return tag.substring(Tweaks.OWNER_TAG.length());
            }
        }
        return null;
    }

    /** The owner's drone among the loaded entities of every map, else null. */
    static Entity loaded(MinecraftServer server, String name) {
        String tag = Tweaks.OWNER_TAG + name;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity.isAlive() && Tweaks.isDrone(entity) && entity.entityTags().contains(tag)) {
                    return entity;
                }
            }
        }
        return null;
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("dronetp")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayer();
                    return player == null ? 0 : go(context.getSource(), player, player);
                })
                .then(Commands.argument("player", EntityArgument.player())
                        .requires(source -> Tweaks.permitted(source, OTHERS))
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayer();
                            return player == null ? 0
                                    : go(context.getSource(), player, EntityArgument.getPlayer(context, "player"));
                        })));
    }

    private static int go(CommandSourceStack source, ServerPlayer player, ServerPlayer owner) {
        boolean own = owner == player;
        String name = owner.getGameProfile().name();
        MinecraftServer server = source.getServer();
        ServerLevel level = null;
        Vec3 pos = null;
        boolean flying = false;
        Entity drone = loaded(server, name);
        if (drone != null) {
            level = (ServerLevel) drone.level();
            pos = drone.position();
            flying = drone.entityTags().contains("pw_flying");
        } else {
            Spot spot = unloaded.get(name);
            if (spot != null && !killWhenLoaded.contains(name)) {
                level = server.getLevel(spot.level());
                pos = spot.pos();
                flying = spot.flying();
            }
        }
        if (level == null) {
            source.sendFailure(Component.literal(own ? "You have no drone out." : name + " has no drone out."));
            return 0;
        }
        if (flying) {
            source.sendFailure(Component.literal(own
                    ? "Your drone is flying; land it first."
                    : name + "'s drone is flying; wait until it lands."));
            return 0;
        }
        player.teleportTo(level, pos.x, pos.y, pos.z, Set.of(), player.getYRot(), player.getXRot(), true);
        source.sendSuccess(() -> Component.literal(own ? "You're at your drone." : "You're at " + name + "'s drone."), false);
        return 1;
    }
}
