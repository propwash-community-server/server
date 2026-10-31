package community.propwash.backpack;

import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.MetaNode;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A backpack on your back (propwash.backpack; for now only admins have it): /backpack toggles it, /backpack drone puts the item in
 * your hand (a Propwash drone) on the outside of it, /backpack drone off takes it off again. Both are
 * item displays the players' games are told ride the player (a set-passengers packet, sent again every
 * half second): in 26.3 nothing can really ride a player (startRiding refuses vehicles that can't be
 * saved, which players can't), so on the server they only follow the player's riding point every tick
 * and turn with their body. Nothing on the client. The
 * backpack's look is config/backpack.properties: an item (a vanilla one until the resource pack has a
 * backpack model, then item_model names that model), and where and how big both are. Hidden while you're
 * vanished (Vanish mod), in spectator or dead. On or off is LuckPerms meta (propwash-backpack), so it follows you to
 * every server; the drone on it is kept per server in config/backpacks/<uuid>.snbt (an item with all
 * its parts is too big for meta).
 */
public final class Backpack implements ModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("backpack");
    private static final String PERMISSION = "propwash.backpack";
    private static final String META = "propwash-backpack";
    static final String TAG = "propwash.backpack";

    private MethodHandle isVanished;
    private final Path dronesDir = FabricLoader.getInstance().getConfigDir().resolve("backpacks");
    private final Properties config = new Properties();
    private final java.util.Set<UUID> wearing = new java.util.HashSet<>();
    private final Map<UUID, ItemStack> drones = new HashMap<>();
    /** The displays on each player: [backpack, drone or null]. */
    private final Map<UUID, Entity[]> worn = new HashMap<>();

    @Override
    public void onInitialize() {
        try {
            isVanished = MethodHandles.publicLookup().findStatic(Class.forName("me.drex.vanish.api.VanishAPI"),
                    "isVanished", MethodType.methodType(boolean.class, Entity.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            isVanished = null; // no Vanish mod
        }
        loadConfig();
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(
                Commands.literal("backpack")
                        .executes(c -> toggle(c.getSource()))
                        .then(Commands.literal("drone")
                                .executes(c -> putDrone(c.getSource()))
                                .then(Commands.literal("off").executes(c -> takeDrone(c.getSource()))))));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            UUID id = handler.player.getUUID();
            LuckPerms lp = luckPerms();
            if (lp != null && "on".equals(lp.getPlayerAdapter(ServerPlayer.class).getMetaData(handler.player).getMetaValue(META))) {
                wearing.add(id);
            }
            ItemStack drone = readDrone(server, id);
            if (drone != null) {
                drones.put(id, drone);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            remove(handler.player.getUUID());
            wearing.remove(handler.player.getUUID());
            drones.remove(handler.player.getUUID());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> java.util.List.copyOf(worn.keySet()).forEach(this::remove));
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity.entityTags().contains(TAG) && worn.values().stream().noneMatch(w -> w[0] == entity || w[1] == entity)) {
                entity.discard();
            }
        });
    }

    private void loadConfig() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("backpack.properties");
        // What looked right on dev with the propwash_cosmetics:backpack model (server resource pack).
        config.setProperty("item", "minecraft:chest");
        config.setProperty("item_model", "propwash_cosmetics:backpack");
        config.setProperty("scale", "1.0");
        config.setProperty("offset", "0 -0.72 -0.25");
        config.setProperty("yaw", "180");
        config.setProperty("drone_scale", "0.5");
        config.setProperty("drone_offset", "0 -0.67 -0.47");
        config.setProperty("drone_pitch", "0");
        config.setProperty("drone_yaw", "180");
        config.setProperty("smoothing", "1");
        try {
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    config.load(in);
                }
            } else {
                try (OutputStream out = Files.newOutputStream(file)) {
                    config.store(out, "Backpack (mods/backpack). item: what the backpack shows; item_model: a model from the"
                            + " resource pack instead (e.g. propwash:backpack), empty for the item's own. offset: x y z in"
                            + " blocks from the top of the head, turned with the body (z negative = behind). drone_pitch, drone_yaw:"
                            + " degrees the drone is tipped forward, and turned round (180 = facing outwards)."
                            + " yaw: degrees the backpack is turned round (180 for the propwash_cosmetics model, whose outside is"
                            + " its north side). smoothing: ticks the game glides into each new angle (0 = none, snappier).");
                }
            }
        } catch (IOException e) {
            LOG.warn("Can't read or write {}", file, e);
        }
    }

    private static LuckPerms luckPerms() {
        try {
            return LuckPermsProvider.get();
        } catch (IllegalStateException | NoClassDefFoundError e) {
            return null;
        }
    }

    private boolean hidden(ServerPlayer player) {
        if (player.isSpectator() || !player.isAlive()) {
            return true;
        }
        try {
            return isVanished != null && (boolean) isVanished.invokeExact((Entity) player);
        } catch (Throwable e) {
            return false;
        }
    }

    private static ListTag floats(float... values) {
        ListTag list = new ListTag();
        for (float v : values) {
            list.add(FloatTag.valueOf(v));
        }
        return list;
    }

    private static boolean permitted(ServerPlayer player) {
        LuckPerms lp = luckPerms();
        return lp != null && lp.getPlayerAdapter(ServerPlayer.class).getPermissionData(player).checkPermission(PERMISSION).asBoolean();
    }

    private int toggle(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null || !allowed(source, player)) {
            return 0;
        }
        UUID id = player.getUUID();
        boolean on = !wearing.contains(id);
        if (on) {
            wearing.add(id);
        } else {
            wearing.remove(id);
            remove(id);
        }
        LuckPerms lp = luckPerms();
        if (lp != null) {
            lp.getUserManager().modifyUser(id, user -> {
                user.data().clear(NodeType.META.predicate(n -> n.getMetaKey().equals(META)));
                if (on) {
                    user.data().add(MetaNode.builder(META, "on").build());
                }
            });
        }
        source.sendSuccess(() -> Component.literal(on ? "Backpack on. /backpack drone puts the drone in your hand on it."
                : "Backpack off.").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private boolean allowed(CommandSourceStack source, ServerPlayer player) {
        if (permitted(player)) {
            return true;
        }
        source.sendFailure(Component.literal("You can't use /backpack yet."));
        return false;
    }

    private int putDrone(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null || !allowed(source, player)) {
            return 0;
        }
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty() || !"propwash".equals(held.getItem().builtInRegistryHolder().key().identifier().getNamespace())) {
            source.sendFailure(Component.literal("Hold a Propwash drone, then /backpack drone."));
            return 0;
        }
        UUID id = player.getUUID();
        ItemStack copy = held.copyWithCount(1);
        drones.put(id, copy);
        writeDrone(source.getServer(), id, copy);
        remove(id); // shown again next tick, with the drone
        source.sendSuccess(() -> Component.literal("Your drone is on your backpack. (It stays in your inventory too.)")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private int takeDrone(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        UUID id = player.getUUID();
        drones.remove(id);
        try {
            Files.deleteIfExists(dronesDir.resolve(id + ".snbt"));
        } catch (IOException e) {
            LOG.warn("Can't delete the backpack drone of {}", id, e);
        }
        remove(id);
        source.sendSuccess(() -> Component.literal("Drone off your backpack.").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private ItemStack readDrone(MinecraftServer server, UUID id) {
        Path file = dronesDir.resolve(id + ".snbt");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            CompoundTag tag = TagParser.parseCompoundFully(Files.readString(file));
            return ItemStack.CODEC.parse(server.registryAccess().createSerializationContext(NbtOps.INSTANCE), tag)
                    .resultOrPartial(e -> LOG.warn("Backpack drone of {}: {}", id, e)).orElse(null);
        } catch (IOException | com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            LOG.warn("Can't read {}", file, e);
            return null;
        }
    }

    private void writeDrone(MinecraftServer server, UUID id, ItemStack stack) {
        Tag tag = encode(server, stack);
        if (tag == null) {
            return;
        }
        try {
            Files.createDirectories(dronesDir);
            Files.writeString(dronesDir.resolve(id + ".snbt"), tag.toString());
        } catch (IOException e) {
            LOG.warn("Can't save the backpack drone of {}", id, e);
        }
    }

    private static Tag encode(MinecraftServer server, ItemStack stack) {
        return ItemStack.CODEC.encodeStart(server.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack)
                .resultOrPartial(e -> LOG.warn("Can't encode a backpack drone: {}", e)).orElse(null);
    }

    private void tick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            if (!wearing.contains(id) || hidden(player)) {
                remove(id);
                continue;
            }
            Entity[] w = worn.get(id);
            ItemStack drone = drones.get(id);
            boolean broken = w != null && (w[0].isRemoved() || w[0].level() != player.level()
                    || (w[1] != null && (w[1].isRemoved() || w[1].level() != player.level())) || (w[1] == null) != (drone == null));
            if (broken) {
                remove(id);
                w = null;
            }
            if (w == null) {
                if (server.getTickCount() % 20 != Math.floorMod(id.hashCode(), 20) || !permitted(player)) {
                    continue; // at most once a second per player, and only while allowed
                }
                w = wear(player, drone);
                if (w == null) {
                    continue;
                }
            }
            float yaw = player.yBodyRot;
            for (Entity e : w) {
                if (e != null) {
                    e.snapTo(player.getPassengerRidingPosition(e), yaw, 0);
                }
            }
            if (server.getTickCount() % 10 == 0) {
                ride(player, w); // for anyone who started seeing them since
            }
        }
    }

    /** Tells everyone who sees the player (and the player) that these ride them. */
    private static void ride(ServerPlayer player, Entity[] w) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeVarInt(player.getId());
        buf.writeVarIntArray(java.util.Arrays.stream(w).filter(java.util.Objects::nonNull).mapToInt(Entity::getId).toArray());
        player.level().getChunkSource().sendToTrackingPlayersAndSelf(player, ClientboundSetPassengersPacket.STREAM_CODEC.decode(buf));
    }

    private Entity[] wear(ServerPlayer player, ItemStack drone) {
        ServerLevel level = player.level();
        CompoundTag item = new CompoundTag();
        item.putString("id", config.getProperty("item", "minecraft:chest"));
        item.putInt("count", 1);
        String model = config.getProperty("item_model", "").trim();
        if (!model.isEmpty()) {
            CompoundTag components = new CompoundTag();
            components.putString("minecraft:item_model", model);
            item.put("components", components);
        }
        Entity pack = display(level, item, "none", number("scale", 1f), vector("offset"), 0, number("yaw", 180));
        if (pack == null) {
            return null;
        }
        Entity onTop = null;
        if (drone != null) {
            Tag droneTag = encode(level.getServer(), drone);
            if (droneTag instanceof CompoundTag droneItem) {
                onTop = display(level, droneItem, "fixed", number("drone_scale", 0.5f), vector("drone_offset"),
                        number("drone_pitch", 0), number("drone_yaw", 180));
            }
        }
        Entity[] w = {pack, onTop};
        worn.put(player.getUUID(), w); // before adding, so ENTITY_LOAD knows them
        for (Entity e : w) {
            if (e != null) {
                e.snapTo(player.getPassengerRidingPosition(e), player.yBodyRot, 0);
                level.addFreshEntity(e);
            }
        }
        ride(player, w);
        return w;
    }

    private Entity display(ServerLevel level, CompoundTag item, String context, float scale, float[] offset, float pitch, float yaw) {
        CompoundTag tag = new CompoundTag();
        tag.put("item", item);
        tag.putString("item_display", context);
        tag.putInt("teleport_duration", (int) number("smoothing", 1));
        CompoundTag t = new CompoundTag();
        t.put("translation", floats(offset[0], offset[1], offset[2]));
        // Turned around y by yaw, then tipped around x by pitch: the quaternion yaw * pitch.
        double hy = Math.toRadians(yaw) / 2, hp = Math.toRadians(pitch) / 2;
        float sy = (float) Math.sin(hy), cy = (float) Math.cos(hy), sp = (float) Math.sin(hp), cp = (float) Math.cos(hp);
        t.put("left_rotation", floats(cy * sp, sy * cp, -sy * sp, cy * cp));
        t.put("right_rotation", floats(0, 0, 0, 1));
        t.put("scale", floats(scale, scale, scale));
        tag.put("transformation", t);
        Entity e = EntityType.loadEntityRecursive(EntityTypes.ITEM_DISPLAY, tag, level, EntitySpawnReason.COMMAND, x -> x);
        if (e != null) {
            e.addTag(TAG);
        }
        return e;
    }

    private float number(String key, float fallback) {
        try {
            return Float.parseFloat(config.getProperty(key, "").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private float[] vector(String key) {
        String[] parts = config.getProperty(key, "0 0 0").trim().split("\\s+");
        float[] v = new float[3];
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                v[i] = Float.parseFloat(parts[i]);
            } catch (NumberFormatException ignored) {
                // stays 0
            }
        }
        return v;
    }

    private void remove(UUID id) {
        Entity[] w = worn.remove(id);
        if (w != null) {
            for (Entity e : w) {
                if (e != null) {
                    e.discard();
                }
            }
        }
    }
}
