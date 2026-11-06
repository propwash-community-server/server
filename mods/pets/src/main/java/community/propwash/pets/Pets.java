package community.propwash.pets;

import com.mojang.brigadier.arguments.StringArgumentType;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

/**
 * Cosmetic pets: /pet <name> picks one, /pet off puts it away, /pet lists the ones you may have:
 * propwash.pets.all (every pet; supporters), propwash.pets.animals, propwash.pets.drones, or one pet
 * at a time with propwash.pets.<name> (propwash.pets.fox). A pet is a baby vanilla animal that walks
 * just behind you, or one of Propwash's preset drones, complete, hovering at your shoulder (an item
 * display; only where Propwash is installed). Nothing on the
 * client: they are ordinary entities the server moves every tick. They can't be hurt, used or pushed,
 * make only the quiet sounds this mod plays for them (see sound(); /pet sound off), aren't kept in the world (removed when you leave, and any found on loading), and hide
 * while you're vanished, in spectator or dead. The choice is LuckPerms meta (propwash-pet), so it
 * follows you to every server that has this mod, and after relogging.
 *
 * <p>Custom drones (propwash.pets.code, admins for now): /pet code <share code> turns a Propwash share
 * code into your pet, "custom" in /pet afterwards; the code is kept in a shared folder (ShareDrones).
 */
public final class Pets implements ModInitializer {
    private static final String PERMISSION = "propwash.pets.";
    private static final String META = "propwash-pet";
    /** LuckPerms meta "off" when the player switched circling off (/pet orbit off); absent: on. */
    private static final String ORBIT_META = "propwash-pet-orbit";
    /** LuckPerms meta "off" when the player muted their pet (/pet sound off); absent: on. */
    private static final String SOUND_META = "propwash-pet-sound";
    private static final String TAG = "propwash.pet";
    /** On drone pets, followed by the owner's UUID: how the Trails mod finds whose trail to draw. */
    private static final String DRONE_OWNER_TAG = "propwash.pet.drone.";
    private static final String TEAM = "propwash_pets";
    /** The pet name of a player's own share-code drone, and who may make one. */
    private static final String CUSTOM = "custom";
    private static final String CODE_PERMISSION = "propwash.pets.code";

    /** Baby animals: entity ids (only those that are ageable here are offered). */
    private static final List<String> BABIES = List.of("armadillo", "axolotl", "bee", "camel", "cat", "chicken", "cow",
            "donkey", "fox", "goat", "happy_ghast", "horse", "llama", "mooshroom", "mule", "ocelot", "panda", "pig",
            "polar_bear", "rabbit", "sheep", "sniffer", "strider", "turtle", "wolf");
    private static final float DRONE_SCALE = 0.6f;
    /** Standing still this long (ticks), pets circle: drones around the head, animals around the feet. */
    private static final int ORBIT_AFTER = 40;
    /** Orbit radius (blocks) and speed (radians a tick): drones a lap in about 4 s, animals at a walk. */
    private static final double DRONE_ORBIT = 0.9, DRONE_ORBIT_SPEED = 0.08, ANIMAL_ORBIT = 1.3, ANIMAL_ORBIT_SPEED = 0.04;
    /** While circling, a pause starts on average every PAUSE_CHANCE ticks and lasts PAUSE_MIN to PAUSE_MAX ticks. */
    private static final int PAUSE_CHANCE = 120, PAUSE_MIN = 20, PAUSE_MAX = 60;
    private static final float ANIMAL_VOLUME = 0.35f;
    /** Animals make their own ambient sound on average every ANIMAL_CHANCE ticks, never within ANIMAL_GAP of the last. */
    private static final int ANIMAL_CHANCE = 400, ANIMAL_GAP = 160;

    /** What a pet name stands for: a baby of this entity type, or a full drone (a Propwash preset's drone item). */
    private record Kind(EntityType<?> baby, ItemStack drone) {
    }

    private final Map<String, Kind> kinds = new LinkedHashMap<>();
    /** Each online player's chosen pet name (absent: none). */
    private final Map<UUID, String> chosen = new HashMap<>();
    private final Map<UUID, Entity> pets = new HashMap<>();
    /** Each online player's share-code drone, if they have one (and it decodes here). */
    private final Map<UUID, Kind> custom = new HashMap<>();
    /** Each pet owner's last position and how many ticks they've stood still there. */
    private final Map<UUID, Vec3> lastPos = new HashMap<>();
    private final Map<UUID, Integer> stillTicks = new HashMap<>();
    /** Where on its circle each orbiting pet is (radians; absent: not orbiting). */
    private final Map<UUID, Double> orbit = new HashMap<>();
    /** The tick until which each circling pet stays put (a pause). */
    private final Map<UUID, Long> pausedUntil = new HashMap<>();
    /** Players who switched circling off. */
    private final java.util.Set<UUID> noOrbit = new java.util.HashSet<>();
    /** Players who muted their pet. */
    private final java.util.Set<UUID> noSound = new java.util.HashSet<>();
    /** Each pet's next allowed sound tick. */
    private final Map<UUID, Long> nextSound = new HashMap<>();
    /** Mob.getAmbientSound (protected); null if it can't be reached (then animals stay quiet). */
    private static final java.lang.reflect.Method AMBIENT = ambientMethod();
    private MethodHandle isVanished;
    private long ticks;

    @Override
    public void onInitialize() {
        try {
            isVanished = MethodHandles.publicLookup().findStatic(Class.forName("me.drex.vanish.api.VanishAPI"),
                    "isVanished", MethodType.methodType(boolean.class, Entity.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            isVanished = null; // no Vanish mod
        }
        ServerLifecycleEvents.SERVER_STARTED.register(this::findKinds);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> List.copyOf(pets.keySet()).forEach(this::remove));
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(
                Commands.literal("pet")
                        .executes(c -> list(c.getSource()))
                        .then(Commands.literal("code")
                                .requires(s -> s.getPlayer() != null && Perms.has(s.getPlayer(), CODE_PERMISSION))
                                .executes(c -> choose(c.getSource(), CUSTOM))
                                .then(Commands.argument("code", StringArgumentType.greedyString())
                                        .executes(c -> setCode(c.getSource(), StringArgumentType.getString(c, "code")))))
                        .then(Commands.literal("orbit")
                                .then(Commands.literal("on").executes(c -> setOrbit(c.getSource(), true)))
                                .then(Commands.literal("off").executes(c -> setOrbit(c.getSource(), false))))
                        .then(Commands.literal("sound")
                                .then(Commands.literal("on").executes(c -> setSound(c.getSource(), true)))
                                .then(Commands.literal("off").executes(c -> setSound(c.getSource(), false))))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    ServerPlayer player = c.getSource().getPlayer();
                                    if (player != null) {
                                        available(player).forEach(b::suggest);
                                    }
                                    b.suggest("off");
                                    return b.buildFuture();
                                })
                                .executes(c -> choose(c.getSource(), StringArgumentType.getString(c, "name").toLowerCase())))));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            String name = Perms.meta(handler.player, META);
            if (name != null) {
                chosen.put(handler.player.getUUID(), name);
            }
            String code = ShareDrones.load(handler.player.getUUID());
            if (code != null) {
                ShareDrones.Result result = ShareDrones.decode(code);
                if (result.drone() != null) {
                    custom.put(handler.player.getUUID(), new Kind(null, result.drone()));
                }
            }
            if ("off".equals(Perms.meta(handler.player, ORBIT_META))) {
                noOrbit.add(handler.player.getUUID());
            }
            if ("off".equals(Perms.meta(handler.player, SOUND_META))) {
                noSound.add(handler.player.getUUID());
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            remove(handler.player.getUUID());
            chosen.remove(handler.player.getUUID());
            custom.remove(handler.player.getUUID());
            lastPos.remove(handler.player.getUUID());
            stillTicks.remove(handler.player.getUUID());
            orbit.remove(handler.player.getUUID());
            pausedUntil.remove(handler.player.getUUID());
            noOrbit.remove(handler.player.getUUID());
            noSound.remove(handler.player.getUUID());
            nextSound.remove(handler.player.getUUID());
        });
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        // A pet left in the world (a crash, an older chunk): gone as soon as it loads.
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity.entityTags().contains(TAG) && !pets.containsValue(entity)) {
                entity.discard();
            }
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                entity.entityTags().contains(TAG) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                entity.entityTags().contains(TAG) ? InteractionResult.FAIL : InteractionResult.PASS);
    }

    /** The pets this server can show: babies that really are ageable, and every Propwash drone preset. */
    private void findKinds(MinecraftServer server) {
        for (String id : BABIES) {
            BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.withDefaultNamespace(id)).ifPresent(type -> {
                Entity test = type.create(server.overworld(), EntitySpawnReason.COMMAND);
                if (test instanceof AgeableMob) {
                    kinds.put(id, new Kind(type, null));
                }
            });
        }
        kinds.putAll(presetDrones());
        org.slf4j.LoggerFactory.getLogger("pets").info("Pets: {}", kinds.keySet());
    }

    /**
     * Propwash's drone presets as full drone items (frame, motors, props, paint), by preset name (the id's
     * path): PropwashApi.builds().presets() and createDrone(preset.data()). By reflection, so this mod
     * runs without Propwash (then: no drone pets). Custom model data "blur" makes Propwash (0.6 on) draw
     * the props spinning, as in flight; older Propwash ignores it and draws them still.
     */
    private static Map<String, Kind> presetDrones() {
        Map<String, Kind> drones = new LinkedHashMap<>();
        try {
            Object builds = Class.forName("de.lardtom.propwash.api.PropwashApi").getMethod("builds").invoke(null);
            java.lang.reflect.Method create = null;
            for (Object preset : (List<?>) builds.getClass().getMethod("presets").invoke(builds)) {
                Object data = preset.getClass().getMethod("data").invoke(preset);
                Identifier id = (Identifier) preset.getClass().getMethod("id").invoke(preset);
                if (create == null) {
                    create = java.util.Arrays.stream(builds.getClass().getMethods())
                            .filter(m -> m.getName().equals("createDrone") && m.getParameterCount() == 1).findFirst().orElseThrow();
                }
                ItemStack drone = (ItemStack) create.invoke(builds, data);
                if (!drone.isEmpty()) {
                    drone.set(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA,
                            new net.minecraft.world.item.component.CustomModelData(List.of(), List.of(), List.of("blur"), List.of()));
                    drones.put(id.getPath(), new Kind(null, drone));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            org.slf4j.LoggerFactory.getLogger("pets").info("No drone pets (Propwash presets not available): {}", e.toString());
        }
        return drones;
    }

    /** May the player have this pet: propwash.pets.all, .animals or .drones, or .<name> (no LuckPerms: ops). */
    private boolean permitted(ServerPlayer player, String name) {
        if (CUSTOM.equals(name)) {
            return custom.containsKey(player.getUUID()) && Perms.has(player, CODE_PERMISSION);
        }
        Kind kind = kinds.get(name);
        if (kind == null) {
            return false;
        }
        return Perms.has(player, PERMISSION + "all")
                || Perms.has(player, PERMISSION + (kind.baby() != null ? "animals" : "drones"))
                || Perms.has(player, PERMISSION + name);
    }

    /** The pets this player may have here, in /pet's order. */
    private List<String> available(ServerPlayer player) {
        List<String> names = new ArrayList<>(kinds.keySet().stream().filter(name -> permitted(player, name)).toList());
        if (permitted(player, CUSTOM)) {
            names.addFirst(CUSTOM);
        }
        return names;
    }

    /** What the pet name stands for, for this player ("custom": their share-code drone); null: nothing. */
    private Kind kind(UUID player, String name) {
        return CUSTOM.equals(name) ? custom.get(player) : name == null ? null : kinds.get(name);
    }

    private int list(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        List<String> names = new ArrayList<>(available(player));
        if (names.isEmpty()) {
            source.sendFailure(Component.literal("Pets are a supporter perk: ko-fi.com/propwashfpv"));
            return 0;
        }
        String mine = chosen.get(player.getUUID());
        MutableComponent line = Component.literal("Pets (click one): ").withStyle(ChatFormatting.GRAY);
        boolean first = true;
        names.add("off");
        for (String name : names) {
            if (!first) {
                line.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
            }
            first = false;
            line.append(Component.literal(name.replace('_', ' '))
                    .withStyle(name.equals(mine) ? ChatFormatting.GOLD : ChatFormatting.YELLOW)
                    .withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand("/pet " + name))));
        }
        source.sendSuccess(() -> line, false);
        return 1;
    }

    private int choose(CommandSourceStack source, String name) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        boolean off = name.equals("off");
        if (!off && kind(player.getUUID(), name) == null) {
            source.sendFailure(Component.literal(CUSTOM.equals(name)
                    ? "You have no custom drone here. /pet code <share code> makes one."
                    : "There's no pet called " + name + " here. /pet lists them."));
            return 0;
        }
        if (!off && !permitted(player, name)) {
            source.sendFailure(Component.literal(available(player).isEmpty()
                    ? "Pets are a supporter perk: ko-fi.com/propwashfpv"
                    : "You can't have that pet. /pet lists yours."));
            return 0;
        }
        UUID id = player.getUUID();
        remove(id);
        if (off) {
            chosen.remove(id);
        } else {
            chosen.put(id, name);
        }
        Perms.setMeta(id, META, off ? null : name);
        source.sendSuccess(() -> Component.literal(off ? "Your pet is away. /pet brings one back."
                : "Your pet: " + name.replace('_', ' ') + ". /pet off puts it away.").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    /** /pet code <share code>: the drone from a Propwash share code becomes the player's pet ("custom"). */
    private int setCode(CommandSourceStack source, String code) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        ShareDrones.Result result = ShareDrones.decode(code);
        if (result.drone() == null) {
            source.sendFailure(Component.literal(result.error()));
            return 0;
        }
        try {
            ShareDrones.save(player.getUUID(), code);
        } catch (java.io.IOException e) {
            org.slf4j.LoggerFactory.getLogger("pets").warn("Saving {}'s share code failed", player.getScoreboardName(), e);
            source.sendFailure(Component.literal("Couldn't save that code: " + e.getMessage()));
            return 0;
        }
        custom.put(player.getUUID(), new Kind(null, result.drone()));
        remove(player.getUUID()); // a new look, if the custom drone was already out
        if (!result.unknownParts().isEmpty()) {
            source.sendSuccess(() -> Component.literal("Parts this server doesn't have were left out: "
                    + result.unknownParts()).withStyle(ChatFormatting.GRAY), false);
        }
        return choose(source, CUSTOM);
    }

    /** /pet orbit on|off: whether the pet circles you when you stand still; saved in LuckPerms meta. */
    private int setOrbit(CommandSourceStack source, boolean on) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        UUID id = player.getUUID();
        if (on) {
            noOrbit.remove(id);
        } else {
            noOrbit.add(id);
        }
        Perms.setMeta(id, ORBIT_META, on ? null : "off");
        source.sendSuccess(() -> Component.literal(on ? "Your pet circles you when you stand still. /pet orbit off to stop."
                : "Your pet stays put when you stand still. /pet orbit on to circle again.").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    /** /pet sound on|off: whether your pet makes sounds (for everyone near it); saved in LuckPerms meta. */
    private int setSound(CommandSourceStack source, boolean on) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        UUID id = player.getUUID();
        if (on) {
            noSound.remove(id);
        } else {
            noSound.add(id);
        }
        Perms.setMeta(id, SOUND_META, on ? null : "off");
        source.sendSuccess(() -> Component.literal(on ? "Your pet makes sounds. /pet sound off to mute it."
                : "Your pet is quiet. /pet sound on to hear it again.").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static java.lang.reflect.Method ambientMethod() {
        try {
            java.lang.reflect.Method m = net.minecraft.world.entity.Mob.class.getDeclaredMethod("getAmbientSound");
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger("pets").info("Animal pets stay quiet: {}", e.toString());
            return null;
        }
    }

    /**
     * Pet sounds, played by the server at the pet (the pets themselves are Silent): an animal now and then
     * makes its own ambient sound at a baby's pitch, quietly, under "Friendly creatures". Drones stay quiet:
     * Propwash's motor sound is synthesized on the client for real drone entities, and its one server-side
     * sound (drone.motor_sample, a 2 s loop replayed) didn't sound good.
     */
    private void sound(ServerPlayer player, Entity pet, Kind kind, Vec3 step) {
        UUID id = player.getUUID();
        if (noSound.contains(id) || nextSound.getOrDefault(id, 0L) > ticks) {
            return;
        }
        if (kind.baby() == null) {
            return;
        }
        ServerLevel level = player.level();
        var random = java.util.concurrent.ThreadLocalRandom.current();
        if (AMBIENT == null || !(pet instanceof net.minecraft.world.entity.Mob mob) || random.nextInt(ANIMAL_CHANCE) != 0) {
            return;
        }
        try {
            if (AMBIENT.invoke(mob) instanceof net.minecraft.sounds.SoundEvent ambient) {
                level.playSound(null, pet.getX(), pet.getY(), pet.getZ(), ambient, net.minecraft.sounds.SoundSource.NEUTRAL,
                        ANIMAL_VOLUME, mob.getVoicePitch());
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return;
        }
        nextSound.put(id, ticks + ANIMAL_GAP);
    }

    private void tick(MinecraftServer server) {
        ticks++;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            Kind kind = kind(id, chosen.get(id));
            Entity pet = pets.get(id);
            if (kind == null || hidden(player)) {
                remove(id);
                continue;
            }
            if (pet != null && (pet.isRemoved() || pet.level() != player.level())) {
                remove(id);
                pet = null;
            }
            if (pet == null) {
                if (ticks % 20 != Math.floorMod(id.hashCode(), 20) || !permitted(player, chosen.get(id))) {
                    continue; // at most once a second per player, and only while allowed
                }
                pet = spawn(player, kind);
                if (pet == null) {
                    continue;
                }
            }
            follow(player, pet, kind);
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

    private Entity spawn(ServerPlayer player, Kind kind) {
        ServerLevel level = player.level();
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Invulnerable", true);
        tag.putBoolean("Silent", true);
        tag.putBoolean("NoGravity", true);
        EntityType<?> type;
        if (kind.baby() != null) {
            type = kind.baby();
            tag.putBoolean("NoAI", true);
            tag.putInt("Age", -24000);
            tag.putBoolean("AgeLocked", true);
        } else {
            type = EntityTypes.ITEM_DISPLAY;
            var item = ItemStack.CODEC.encodeStart(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), kind.drone())
                    .result().orElse(null);
            if (item == null) {
                return null;
            }
            tag.put("item", item);
            // "fixed" is how Propwash draws the whole built drone; it stands upright then, so tip it flat.
            tag.putString("item_display", "fixed");
            // The client glides between the positions this sends every tick.
            tag.putInt("teleport_duration", 2);
            CompoundTag transformation = new CompoundTag();
            transformation.put("translation", floats(0, 0, 0));
            float half = (float) Math.toRadians(-90) / 2;
            // Tipped flat (-90° about x), then turned round (180° about up), or its nose points back at you.
            transformation.put("left_rotation", floats(0, (float) Math.cos(half), (float) -Math.sin(half), 0));
            transformation.put("right_rotation", floats(0, 0, 0, 1));
            transformation.put("scale", floats(DRONE_SCALE, DRONE_SCALE, DRONE_SCALE));
            tag.put("transformation", transformation);
        }
        Entity pet = EntityType.loadEntityRecursive(type, tag, level, EntitySpawnReason.COMMAND, e -> e);
        if (pet == null) {
            return null;
        }
        pet.addTag(TAG);
        if (kind.drone() != null) {
            // The Trails mod draws the owner's drone trail behind a drone pet, found by this tag.
            pet.addTag(DRONE_OWNER_TAG + player.getUUID());
        }
        Vec3 at = target(player, pet, kind);
        pet.snapTo(at.x, at.y, at.z, player.getYRot(), 0);
        pets.put(player.getUUID(), pet); // before adding, so ENTITY_LOAD knows it
        level.addFreshEntity(pet);
        Scoreboard scoreboard = level.getServer().getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(TEAM);
        if (team == null) {
            team = scoreboard.addPlayerTeam(TEAM);
            team.setCollisionRule(Team.CollisionRule.NEVER);
        }
        scoreboard.addPlayerToTeam(pet.getScoreboardName(), team);
        return pet;
    }

    static ListTag floats(float... values) {
        ListTag list = new ListTag();
        for (float v : values) {
            list.add(FloatTag.valueOf(v));
        }
        return list;
    }

    private void remove(UUID id) {
        Entity pet = pets.remove(id);
        if (pet != null) {
            Scoreboard scoreboard = pet.level().getServer().getScoreboard();
            PlayerTeam team = scoreboard.getPlayerTeam(TEAM);
            if (team != null && team.getPlayers().contains(pet.getScoreboardName())) {
                scoreboard.removePlayerFromTeam(pet.getScoreboardName(), team);
            }
            pet.discard();
        }
    }

    /**
     * Where the pet wants to be: babies just behind and to the right on the ground, drones at the right
     * shoulder; when the player has stood still a while, going round them (drones at head height, animals
     * on the ground at their feet), starting from where the pet is.
     */
    private Vec3 target(ServerPlayer player, Entity pet, Kind kind) {
        UUID id = player.getUUID();
        Vec3 p = player.position();
        Vec3 last = lastPos.put(id, p);
        int still = last != null && last.distanceToSqr(p) < 0.0004 ? stillTicks.merge(id, 1, Integer::sum) : 0;
        if (still == 0) {
            stillTicks.put(id, 0);
            orbit.remove(id);
        }
        boolean drone = kind.drone() != null;
        double bob = drone ? Math.sin((ticks + player.getId()) / 12.0) * 0.08 : 0;
        if (still >= ORBIT_AFTER && !noOrbit.contains(id)) {
            // Not a perfect circle: speed, radius and (drones) height drift slowly, each owner's pet in its
            // own rhythm, and now and then it stops a moment.
            double seed = (id.hashCode() & 0xffff) / 1000.0, t = ticks / 20.0;
            long pause = pausedUntil.getOrDefault(id, 0L);
            if (pause <= ticks && orbit.containsKey(id)
                    && java.util.concurrent.ThreadLocalRandom.current().nextInt(PAUSE_CHANCE) == 0) {
                pause = ticks + java.util.concurrent.ThreadLocalRandom.current().nextInt(PAUSE_MIN, PAUSE_MAX + 1);
                pausedUntil.put(id, pause);
            }
            double speed = (drone ? DRONE_ORBIT_SPEED : ANIMAL_ORBIT_SPEED) * (1 + 0.25 * Math.sin(t * 0.7 + seed));
            double angle = !orbit.containsKey(id) ? Math.atan2(pet.getZ() - p.z, pet.getX() - p.x)
                    : orbit.get(id) + (pause > ticks ? 0 : speed);
            orbit.put(id, angle);
            double r = (drone ? DRONE_ORBIT : ANIMAL_ORBIT) * (1 + 0.12 * Math.sin(angle * 2 + seed) + 0.06 * Math.sin(t * 1.3 + seed * 2));
            double x = p.x + Math.cos(angle) * r, z = p.z + Math.sin(angle) * r;
            double lift = drone ? 0.12 * Math.sin(t * 0.9 + seed * 3) : 0;
            return drone ? new Vec3(x, p.y + 1.9 + bob + lift, z) : new Vec3(x, ground(player.level(), x, p.y, z), z);
        }
        orbit.remove(id);
        double yaw = Math.toRadians(player.yBodyRot);
        double forwardX = -Math.sin(yaw), forwardZ = Math.cos(yaw);
        double rightX = -forwardZ, rightZ = forwardX;
        if (drone) {
            return new Vec3(p.x + rightX * 0.7 + forwardX * 0.2, p.y + 1.9 + bob, p.z + rightZ * 0.7 + forwardZ * 0.2);
        }
        double x = p.x - forwardX * 1.1 + rightX * 0.6, z = p.z - forwardZ * 1.1 + rightZ * 0.6;
        return new Vec3(x, ground(player.level(), x, p.y, z), z);
    }

    /** The top of the ground near y at x, z (a block up to 4 below); y itself if there is none (flying). */
    private static double ground(ServerLevel level, double x, double y, double z) {
        BlockPos start = BlockPos.containing(x, y + 1, z);
        for (int dy = 0; dy <= 5; dy++) {
            BlockPos pos = start.below(dy);
            BlockState state = level.getBlockState(pos);
            var shape = state.getCollisionShape(level, pos);
            if (!shape.isEmpty()) {
                double top = pos.getY() + shape.max(Direction.Axis.Y);
                return top <= y + 1.0 ? top : y;
            }
        }
        return y;
    }

    private void follow(ServerPlayer player, Entity pet, Kind kind) {
        Vec3 to = target(player, pet, kind);
        Vec3 from = pet.position();
        // Close behind: most of the way there each tick, straight there when far (a teleport).
        Vec3 at = from.distanceToSqr(to) > 36 ? to : from.lerp(to, 0.4);
        // Circling, a pet keeps its heading while it pauses; otherwise it faces the way the player does.
        float yaw = orbit.containsKey(player.getUUID()) ? pet.getYRot() : player.yBodyRot;
        Vec3 step = at.subtract(from);
        // Animals face where they walk; drones too while circling.
        if ((kind.baby() != null || orbit.containsKey(player.getUUID())) && step.horizontalDistanceSqr() > 0.0004) {
            yaw = (float) Math.toDegrees(Math.atan2(-step.x, step.z));
        }
        pet.snapTo(at.x, at.y, at.z, yaw, 0);
        sound(player, pet, kind, step);
        pet.setYHeadRot(yaw);
        pet.setYBodyRot(yaw);
        if (kind.baby() != null && pet instanceof AgeableMob mob && !mob.isBaby()) {
            mob.setAge(-24000);
        }
    }
}
