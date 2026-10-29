package community.propwash.plotedit;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.event.platform.CommandEvent;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WorldEdit for players in the plot world, only where FabricPlots lets them build (their own and
 * trusted plots; never roads, the bedrock floor or other plots), at most max-blocks-per-command
 * blocks per command and max-blocks-per-window blocks per window-seconds per player
 * (config/plotedit.properties, reread when it changes); region commands are refused on selections
 * bigger than max-selection-volume (WorldEdit visits every position, and //copy holds them all in
 * memory, even where nothing may change). Players with propwash.plotedit.bypass
 * (staff) aren't limited, anywhere; with propwash.plotedit.staff (mods) they get the staff.* limits
 * instead, still only where they may build. Which WorldEdit commands players get is LuckPerms
 * (permissions.toml).
 */
public final class PlotEdit implements ModInitializer {
    static final Logger LOGGER = LoggerFactory.getLogger("plotedit");
    private static final String PLOTS = "fabricplots:plots";
    private static final String BYPASS = "propwash.plotedit.bypass";
    private static final String STAFF = "propwash.plotedit.staff";
    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("plotedit.properties");

    static volatile int perCommand = 60_000;
    static volatile int perWindow = 60_000;
    static volatile long windowMs = 30_000;
    static volatile long maxSelection = 1_000_000;
    static volatile int staffPerCommand = 80_000;
    static volatile int staffPerWindow = 80_000;
    // Commands that work on the whole selection (without their slashes).
    private static final Set<String> REGION_COMMANDS = Set.of("set", "replace", "rep", "overlay", "center", "middle",
            "walls", "faces", "outline", "hollow", "line", "curve", "move", "stack", "smooth", "naturalize",
            "copy", "cut", "count", "distr", "regen", "deform", "forest", "flora", "snowsmooth", "setbiome");
    private static volatile long configModified = -1;

    // FabricPlots' own build check and WorldEdit's Fabric adapter, by name: neither is a compile dependency.
    private static MethodHandle canBuild;
    private static Object adapter;
    private static MethodHandle toNativeWorld;
    private static MethodHandle fromNativePlayer;

    private static final Map<UUID, Window> windows = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                canBuild = lookup.findStatic(Class.forName("com.fabricplots.protect.PlotProtection"), "canModifyEntity",
                        MethodType.methodType(boolean.class, ServerPlayer.class, BlockPos.class));
                Class<?> fabricAdapter = Class.forName("com.sk89q.worldedit.fabric.FabricAdapter");
                adapter = fabricAdapter.getMethod("get").invoke(null);
                toNativeWorld = lookup.findVirtual(fabricAdapter, "toNativeWorld",
                        MethodType.methodType(ServerLevel.class, com.sk89q.worldedit.world.World.class));
                fromNativePlayer = lookup.findVirtual(fabricAdapter, "fromNativePlayer",
                        MethodType.methodType(com.sk89q.worldedit.entity.Player.class, ServerPlayer.class));
            } catch (ReflectiveOperationException | LinkageError e) {
                // Without FabricPlots' check nobody could be limited correctly, so players get nothing.
                LOGGER.error("FabricPlots or WorldEdit not found; WorldEdit edits by players in the plot world are blocked", e);
            }
            readConfig();
            WorldEdit.getInstance().getEventBus().register(this);
        });
        ServerTickEvents.START_SERVER_TICK.register(server -> Monitor.tickStart());
        ServerTickEvents.END_SERVER_TICK.register(Monitor::tickEnd);
    }

    /** Staff, for the monitor's notices: those with the bypass (WorldEdit's permission check, so LuckPerms). */
    static boolean isStaff(ServerPlayer player) {
        if (fromNativePlayer == null) {
            return false;
        }
        try {
            return ((com.sk89q.worldedit.entity.Player) fromNativePlayer.invoke(adapter, player)).hasPermission(BYPASS);
        } catch (Throwable e) {
            return false;
        }
    }

    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        // Before history: blocks skipped here (not their plot, past a limit) are never recorded, so //undo and
        // //redo replay exactly what changed.
        if (event.getStage() != EditSession.Stage.BEFORE_HISTORY) {
            return;
        }
        Actor actor = event.getActor();
        if (actor == null || !actor.isPlayer() || actor.hasPermission(BYPASS)) {
            return;
        }
        ServerLevel level = nativeWorld(event.getWorld());
        if (level == null || !PLOTS.equals(level.dimension().identifier().toString())) {
            return;
        }
        readConfig();
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(actor.getUniqueId());
        Window window = windows.computeIfAbsent(actor.getUniqueId(), id -> new Window());
        // //undo always goes through right after an edit: no per-command limit (it can't be bigger than an
        // edit that was allowed), and the window may go up to twice the limit for it. It still counts, so a
        // run of undos (or undo/redo) is limited like anything else. //redo is a normal edit.
        String command = Monitor.takeCommand(actor.getUniqueId());
        String name = command == null ? "" : command.replaceFirst("^/+", "").split(" ", 2)[0].toLowerCase().replaceFirst("^worldedit:", "");
        boolean free = name.equals("undo");
        boolean staff = actor.hasPermission(STAFF);
        event.setExtent(new PlotExtent(event.getExtent(), player, actor, window, free,
                staff ? staffPerCommand : perCommand, staff ? staffPerWindow : perWindow));
    }

    // Before WorldEdit runs a region command: the selection must not be bigger than max-selection-volume.
    @Subscribe
    public void onCommand(CommandEvent event) {
        Actor actor = event.getActor();
        if (actor == null || !actor.isPlayer() || actor.hasPermission(BYPASS)) {
            return;
        }
        Monitor.command(actor.getUniqueId(), "/" + event.getArguments().trim().replaceFirst("^/+", "/"));
        String command = event.getArguments().trim().replaceFirst("^/+", "").split(" ", 2)[0].toLowerCase();
        if (!REGION_COMMANDS.contains(command)) {
            return;
        }
        LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);
        com.sk89q.worldedit.world.World world = session.getSelectionWorld();
        ServerLevel level = world == null ? null : nativeWorld(world);
        if (level == null || !PLOTS.equals(level.dimension().identifier().toString())) {
            return;
        }
        readConfig();
        try {
            long volume = session.getSelection(world).getVolume();
            if (volume > maxSelection) {
                event.setCancelled(true);
                Monitor.forget(actor.getUniqueId());
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(actor.getUniqueId());
                if (player != null) {
                    Monitor.limited(actor.getUniqueId(), actor.getName(), "selection of " + volume + " blocks", level.getServer());
                }
                actor.printError(TextComponent.of("Your selection is " + volume + " blocks; on plots, //" + command
                        + " works on selections up to " + maxSelection + ". Select less (//contract, //pos1, //pos2)."));
            }
        } catch (IncompleteRegionException e) {
            // No selection yet: WorldEdit says so itself.
        }
    }

    private static ServerLevel nativeWorld(com.sk89q.worldedit.world.World world) {
        if (toNativeWorld == null) {
            return null;
        }
        try {
            return (ServerLevel) toNativeWorld.invoke(adapter, world);
        } catch (Throwable e) {
            return null;
        }
    }

    static boolean allowed(ServerPlayer player, BlockVector3 pos) {
        if (player == null || canBuild == null) {
            return false;
        }
        try {
            return (boolean) canBuild.invokeExact(player, new BlockPos(pos.x(), pos.y(), pos.z()));
        } catch (Throwable e) {
            return false;
        }
    }

    /** The limits from config/plotedit.properties, written with the defaults when missing. */
    private static void readConfig() {
        try {
            if (!Files.exists(CONFIG)) {
                Files.writeString(CONFIG, "# WorldEdit limits for players on plots (staff with propwash.plotedit.bypass aren't limited).\n"
                        + "max-blocks-per-command=60000\nmax-blocks-per-window=60000\nwindow-seconds=30\n"
                        + "max-selection-volume=1000000\n"
                        + "# With propwash.plotedit.staff.\n"
                        + "staff.max-blocks-per-command=80000\nstaff.max-blocks-per-window=80000\n"
                        + "# Monitor (logs/plotedit.log): log ticks slower than lag-log-ms during WorldEdit, tell staff\n"
                        + "# above lag-alert-ms, and when a player hits a limit limit-alert-count times in 10 minutes.\n"
                        + "lag-log-ms=250\nlag-alert-ms=1000\nlimit-alert-count=5\n");
            }
            long modified = Files.getLastModifiedTime(CONFIG).toMillis();
            if (modified == configModified) {
                return;
            }
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(CONFIG)) {
                p.load(in);
            }
            perCommand = Math.max(1, Integer.parseInt(p.getProperty("max-blocks-per-command", "60000").trim()));
            perWindow = Math.max(1, Integer.parseInt(p.getProperty("max-blocks-per-window", "60000").trim()));
            windowMs = Math.max(1, Integer.parseInt(p.getProperty("window-seconds", "30").trim())) * 1000L;
            maxSelection = Math.max(1, Long.parseLong(p.getProperty("max-selection-volume", "1000000").trim()));
            staffPerCommand = Math.max(1, Integer.parseInt(p.getProperty("staff.max-blocks-per-command", "80000").trim()));
            staffPerWindow = Math.max(1, Integer.parseInt(p.getProperty("staff.max-blocks-per-window", "80000").trim()));
            Monitor.lagLogMs = Math.max(1, Long.parseLong(p.getProperty("lag-log-ms", "250").trim()));
            Monitor.lagAlertMs = Math.max(1, Long.parseLong(p.getProperty("lag-alert-ms", "1000").trim()));
            Monitor.limitAlertCount = Math.max(1, Integer.parseInt(p.getProperty("limit-alert-count", "5").trim()));
            configModified = modified;
            LOGGER.info("Limits: {} blocks per command, {} per {} s, selections up to {}; staff {}, {}",
                    perCommand, perWindow, windowMs / 1000, maxSelection, staffPerCommand, staffPerWindow);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't read {}; keeping the limits as they were", CONFIG, e);
        }
    }

    /** A player's changed blocks over the last window, in one-second buckets. */
    static final class Window {
        private final long[] counts = new long[3600];
        private final long[] seconds = new long[3600];

        synchronized long used(long now) {
            long second = now / 1000;
            long span = Math.min(counts.length, (windowMs + 999) / 1000);
            long total = 0;
            for (int i = 0; i < span; i++) {
                int slot = (int) ((second - i) % counts.length);
                if (seconds[slot] == second - i) {
                    total += counts[slot];
                }
            }
            return total;
        }

        synchronized void add(long now) {
            long second = now / 1000;
            int slot = (int) (second % counts.length);
            if (seconds[slot] != second) {
                seconds[slot] = second;
                counts[slot] = 0;
            }
            counts[slot]++;
        }

        private long pausedUntil;

        synchronized boolean paused(long now) {
            return now < pausedUntil;
        }

        /** A whole window without edits; afterwards it starts empty. */
        synchronized void pause(long now) {
            pausedUntil = now + windowMs;
            java.util.Arrays.fill(seconds, -1);
        }

        synchronized long secondsLeft(long now) {
            return Math.max(0, (pausedUntil - now + 999) / 1000);
        }
    }

    /**
     * Lets changes through only where the player may build, and counts them against the limits. Past a
     * limit the rest of the edit is skipped (not aborted with an exception: WorldEdit sends the changed
     * blocks to players at the end of an edit, and an abort there left clients with stale blocks).
     */
    static final class PlotExtent extends AbstractDelegateExtent {
        private final ServerPlayer player;
        private final Actor actor;
        private final Window window;
        private final boolean free;
        /** This player's limits: max-blocks-per-command and -per-window, or the staff.* ones. */
        private final int commandLimit;
        private final int windowLimit;
        private int changed;
        private boolean stopped;

        PlotExtent(Extent extent, ServerPlayer player, Actor actor, Window window, boolean free,
                   int commandLimit, int windowLimit) {
            super(extent);
            this.player = player;
            this.actor = actor;
            this.window = window;
            this.free = free;
            this.commandLimit = commandLimit;
            this.windowLimit = windowLimit;
        }

        /** The first time a limit is reached in this edit: tell the player and the monitor. */
        private void stop(String what, String message) {
            if (stopped) {
                return;
            }
            stopped = true;
            actor.printError(TextComponent.of(message));
            if (player != null) {
                Monitor.limited(actor.getUniqueId(), actor.getName(), what, player.level().getServer());
            }
        }

        @Override
        public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 position, T block) throws WorldEditException {
            if (stopped) {
                return false;
            }
            if (!allowed(player, position)) {
                Monitor.skipped(actor.getUniqueId(), actor.getName());
                return false;
            }
            // Already that block: nothing to change, so not counted (a second //set on the same selection
            // goes on where the first stopped). Blocks with data (chests, signs) always count as a change.
            if (!(block instanceof com.sk89q.worldedit.world.block.BaseBlock base && base.getNbtId() != null && !base.getNbtId().isEmpty())
                    && getExtent().getBlock(position).equals(block.toImmutableState())) {
                return false;
            }
            long now = System.currentTimeMillis();
            if (!free && changed >= commandLimit) {
                stop(commandLimit + " per command", "This edit reached the limit of " + commandLimit
                        + " blocks per command; the rest wasn't changed (//undo takes back what was).");
                return false;
            }
            // Past the window's limit a player is paused for a whole window (no trickle of partial edits as
            // the oldest changes age out); //undo still works then.
            if (!free && window.paused(now)) {
                stop("paused", "You've reached the limit of " + windowLimit + " blocks per " + windowMs / 1000
                        + " seconds: you can use WorldEdit again in " + window.secondsLeft(now)
                        + " s. Nothing was changed (//undo still works).");
                return false;
            }
            if (window.used(now) >= (free ? 2L * windowLimit : windowLimit)) {
                if (!free) {
                    window.pause(now);
                }
                stop(windowLimit + " per " + windowMs / 1000 + " s", "You've changed " + windowLimit + " blocks in "
                        + windowMs / 1000 + " seconds, so the rest of this edit wasn't changed: you can use WorldEdit"
                        + " again in " + Math.max(1, window.secondsLeft(now)) + " s (//undo still works).");
                return false;
            }
            boolean set = super.setBlock(position, block);
            if (set) {
                changed++;
                window.add(now);
                Monitor.changed(actor.getUniqueId(), actor.getName());
            }
            return set;
        }

        @Override
        public boolean setBiome(BlockVector3 position, BiomeType biome) {
            return allowed(player, position) && super.setBiome(position, biome);
        }

        @Override
        public Entity createEntity(Location location, BaseEntity entity) {
            return allowed(player, location.toVector().toBlockPoint()) ? super.createEntity(location, entity) : null;
        }
    }
}
