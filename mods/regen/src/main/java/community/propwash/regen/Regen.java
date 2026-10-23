package community.propwash.regen;

import de.lardtom.propwash.interact.DroneBreaks;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drones break glass on the maps (Propwash's propwash:drones_break_blocks game rule, which this lets
 * through Flan for drones only, see RegenBreakMixin), and every broken block comes back after a while:
 * seconds in config/regen.properties, seconds.<map> per map (maps:<map>), "off" for no breaking there.
 * On plots (fabricplots:plots) it's per plot and pilot instead: on plots with the drone-glass flag (everyone)
 * or, for the owner's and trusted players' drones, drone-glass-members, back
 * after that plot's /plot glassregen seconds, and only into the same plot (FabricPlots' droneGlassSeconds
 * and plotKey, by name). Only blocks without block entities break (a decorated pot's sherds couldn't come back). Blocks return
 * one by one with their sound, only into an empty spot, never into a player or drone; when the server
 * stops, all of them return first, and the list is saved in the world in case of a crash.
 */
public final class Regen implements ModInitializer {
    static final Logger LOGGER = LoggerFactory.getLogger("regen");
    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("regen.properties");
    private static final String MAPS = "maps";
    private static final int RESTORES_PER_TICK = 20;
    private static final long JITTER_MS = 5_000;
    private static final long BLOCKED_RETRY_MS = 5_000;

    private static volatile long defaultSeconds = 180;
    private static final Map<String, Long> mapSeconds = new ConcurrentHashMap<>(); // -1 = off
    private static volatile long configModified = -1;

    /** A broken block waiting to come back. */
    /** key: on plots, the plot it broke on (owner and position), "" elsewhere; it only goes back into that one. */
    record Pending(ResourceKey<Level> level, BlockPos pos, BlockState state, long dueMs, String key) {
    }

    private static final String PLOTS = "fabricplots:plots";
    private static java.lang.invoke.MethodHandle plotSeconds;
    private static java.lang.invoke.MethodHandle plotKey;

    static {
        try {
            Class<?> manager = Class.forName("com.fabricplots.core.PlotManager");
            var lookup = java.lang.invoke.MethodHandles.publicLookup();
            plotSeconds = lookup.findStatic(manager, "droneGlassSeconds",
                    java.lang.invoke.MethodType.methodType(int.class, int.class, int.class, java.util.UUID.class));
            plotKey = lookup.findStatic(manager, "plotKey",
                    java.lang.invoke.MethodType.methodType(String.class, int.class, int.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            // No FabricPlots (fpv): plots never come up.
            plotSeconds = null;
            plotKey = null;
        }
    }

    /** The plot at pos (owner and position), "" if none or not on plots. */
    static String keyAt(ServerLevel level, BlockPos pos) {
        if (plotKey == null || !PLOTS.equals(level.dimension().identifier().toString())) {
            return "";
        }
        try {
            return (String) plotKey.invokeExact(pos.getX(), pos.getZ());
        } catch (Throwable e) {
            return "";
        }
    }

    private static final List<Pending> pending = new ArrayList<>();
    private static final Map<java.util.UUID, Long> lastDenied = new ConcurrentHashMap<>();
    private static long lastSave;

    @Override
    public void onInitialize() {
        readConfig();
        if (FabricLoader.getInstance().isModLoaded("fabricplots")) {
            LOGGER.info(plotSeconds != null ? "FabricPlots found: glass breaks per plot (drone-glass flags)"
                    : "FabricPlots is here but its drone-glass lookups aren't (older FabricPlots?): no glass breaks on plots");
        }
        // No breaking where it's off, outside the maps, or for blocks that hold data.
        DroneBreaks.GUARDS.register((level, player, pos) -> allowed(level, player, pos));
        // Why Propwash refused a drone break, in the server log (at most every 5 s per player), to see
        // what stops it on a map.
        DroneBreaks.DENIED.register((drone, player, pos, outcome) -> {
            long now = System.currentTimeMillis();
            Long last = lastDenied.get(player.getUUID());
            if (last == null || now - last > 5000) {
                lastDenied.put(player.getUUID(), now);
                LOGGER.info("Drone break refused for {} at {} in {}: {}", player.getName().getString(), pos.toShortString(),
                        drone.level().dimension().identifier(), outcome);
            }
        });
        DroneBreaks.BROKEN.register((drone, player, pos, state, impact) -> {
            ServerLevel level = (ServerLevel) drone.level();
            long seconds = secondsFor(level, pos, player);
            if (seconds > 0) {
                long due = System.currentTimeMillis() + seconds * 1000 + ThreadLocalRandom.current().nextLong(JITTER_MS);
                synchronized (pending) {
                    pending.add(new Pending(level.dimension(), pos.immutable(), state, due, keyAt(level, pos)));
                }
            }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(Regen::load);
        ServerTickEvents.END_SERVER_TICK.register(Regen::tick);
        // Everything back before the worlds are saved, so a restart never leaves holes.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            restoreAll(server);
            save(server);
        });
    }

    /**
     * Whether the pilot's drone may break this block: on a map where breaking is on (on plots: where the
     * plot lets this pilot's drones), and not a block holding data.
     */
    public static boolean allowed(ServerLevel level, net.minecraft.world.entity.player.Player pilot, BlockPos pos) {
        readConfig();
        boolean ok = secondsFor(level, pos, pilot) > 0 && !level.getBlockState(pos).hasBlockEntity();
        // Propwash asks ahead and keeps quiet about a no, so on plots say why here (every 5 s per pilot at most).
        if (!ok && pilot != null && PLOTS.equals(level.dimension().identifier().toString())) {
            long now = System.currentTimeMillis();
            Long last = lastDenied.get(pilot.getUUID());
            if (last == null || now - last > 5000) {
                lastDenied.put(pilot.getUUID(), now);
                LOGGER.info("No drone glass for {} at {}: {}", pilot.getName().getString(), pos.toShortString(),
                        keyAt(level, pos).isEmpty() ? "not on a plot" : "the drone-glass flags of plot " + keyAt(level, pos) + " don't allow it");
            }
        }
        return ok;
    }

    /** Seconds until blocks come back on this level, 0 or less for no breaking there (not a map, or off). */
    static long secondsFor(ServerLevel level, BlockPos pos, net.minecraft.world.entity.player.Player pilot) {
        Identifier id = level.dimension().identifier();
        if (PLOTS.equals(id.toString())) {
            if (plotSeconds == null) {
                return 0;
            }
            // A UUID variable, not the expression: invokeExact needs the exact (int, int, UUID) type.
            java.util.UUID pilotId = pilot == null ? null : pilot.getUUID();
            try {
                return (int) plotSeconds.invokeExact(pos.getX(), pos.getZ(), pilotId);
            } catch (Throwable e) {
                LOGGER.warn("FabricPlots' droneGlassSeconds failed at {}", pos.toShortString(), e);
                return 0;
            }
        }
        if (!MAPS.equals(id.getNamespace())) {
            return 0;
        }
        return mapSeconds.getOrDefault(id.getPath(), defaultSeconds);
    }

    private static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        int done = 0;
        synchronized (pending) {
            Iterator<Pending> it = pending.iterator();
            List<Pending> later = new ArrayList<>();
            while (it.hasNext() && done < RESTORES_PER_TICK) {
                Pending p = it.next();
                if (p.dueMs() > now) {
                    continue;
                }
                ServerLevel level = server.getLevel(p.level());
                if (level == null) {
                    it.remove();
                    continue;
                }
                if (!level.isLoaded(p.pos())) {
                    continue; // comes back once someone is near again
                }
                if (!level.getEntities((net.minecraft.world.entity.Entity) null, new AABB(p.pos())).isEmpty()) {
                    it.remove();
                    later.add(new Pending(p.level(), p.pos(), p.state(), now + BLOCKED_RETRY_MS, p.key()));
                    continue;
                }
                it.remove();
                if (restore(level, p, true)) {
                    done++;
                }
            }
            pending.addAll(later);
        }
        if (now - lastSave > 60_000) {
            lastSave = now;
            save(server);
        }
    }

    /** Puts the block back if its spot is still empty (air, or the water a waterlogged pane leaves). */
    private static boolean restore(ServerLevel level, Pending p, boolean effects) {
        if (!p.key().equals(keyAt(level, p.pos()))) {
            return false; // that plot was cleared or has another owner now: nothing goes back into it
        }
        BlockState now = level.getBlockState(p.pos());
        if (!now.isAir() && !(now.getBlock() instanceof LiquidBlock)) {
            return false; // something else is there now: leave it
        }
        level.setBlock(p.pos(), p.state(), 3);
        if (effects) {
            level.playSound(null, p.pos(), p.state().getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.7f, 1.0f);
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, p.state()),
                    p.pos().getX() + 0.5, p.pos().getY() + 0.5, p.pos().getZ() + 0.5, 8, 0.3, 0.3, 0.3, 0.0);
        }
        return true;
    }

    private static void restoreAll(MinecraftServer server) {
        synchronized (pending) {
            for (Pending p : pending) {
                ServerLevel level = server.getLevel(p.level());
                if (level != null) {
                    restore(level, p, false);
                }
            }
            if (!pending.isEmpty()) {
                LOGGER.info("Put back {} broken blocks before stopping", pending.size());
            }
            pending.clear();
        }
    }

    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data/regen.dat");
    }

    private static void save(MinecraftServer server) {
        ListTag list = new ListTag();
        synchronized (pending) {
            for (Pending p : pending) {
                CompoundTag t = new CompoundTag();
                t.putString("level", p.level().identifier().toString());
                t.putString("key", p.key());
                t.putInt("x", p.pos().getX());
                t.putInt("y", p.pos().getY());
                t.putInt("z", p.pos().getZ());
                t.put("state", NbtUtils.writeBlockState(p.state()));
                list.add(t);
            }
        }
        CompoundTag root = new CompoundTag();
        root.put("pending", list);
        try {
            Path f = file(server);
            Files.createDirectories(f.getParent());
            NbtIo.writeCompressed(root, f);
        } catch (IOException e) {
            LOGGER.warn("Couldn't save the blocks waiting to come back", e);
        }
    }

    /** After a crash: blocks that were still waiting come back soon after the start. */
    private static void load(MinecraftServer server) {
        Path f = file(server);
        if (!Files.exists(f)) {
            return;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(f, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            ListTag list = root.getListOrEmpty("pending");
            long due = System.currentTimeMillis() + 10_000;
            var blocks = server.registryAccess().lookupOrThrow(Registries.BLOCK);
            synchronized (pending) {
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag t = list.getCompoundOrEmpty(i);
                    ResourceKey<Level> level = ResourceKey.create(Registries.DIMENSION, Identifier.parse(t.getStringOr("level", "")));
                    BlockPos pos = new BlockPos(t.getIntOr("x", 0), t.getIntOr("y", 0), t.getIntOr("z", 0));
                    BlockState state = NbtUtils.readBlockState(blocks, t.getCompoundOrEmpty("state"));
                    pending.add(new Pending(level, pos, state, due + i * 50L, t.getStringOr("key", "")));
                }
            }
            if (!list.isEmpty()) {
                LOGGER.info("{} broken blocks from before the last stop come back now", list.size());
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't read {}", f, e);
        }
    }

    /** config/regen.properties: seconds (default for every map), seconds.<map> = N or off. */
    static void readConfig() {
        try {
            if (!Files.exists(CONFIG)) {
                Files.writeString(CONFIG, "# Drones break glass on the fpv maps; broken blocks come back after this many seconds.\n"
                        + "seconds=180\n# Per map (maps:<map>): seconds.<map>=120, or seconds.<map>=off for no breaking there.\n");
            }
            long modified = Files.getLastModifiedTime(CONFIG).toMillis();
            if (modified == configModified) {
                return;
            }
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(CONFIG)) {
                p.load(in);
            }
            defaultSeconds = parse(p.getProperty("seconds", "180"));
            mapSeconds.clear();
            for (String key : p.stringPropertyNames()) {
                if (key.startsWith("seconds.")) {
                    mapSeconds.put(key.substring("seconds.".length()), parse(p.getProperty(key)));
                }
            }
            configModified = modified;
            LOGGER.info("Blocks broken by drones come back after {} s; per map: {}", defaultSeconds, mapSeconds);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't read {}; keeping the settings as they were", CONFIG, e);
        }
    }

    private static long parse(String value) {
        String v = value.trim();
        return v.equalsIgnoreCase("off") ? -1 : Math.max(1, Long.parseLong(v));
    }
}
