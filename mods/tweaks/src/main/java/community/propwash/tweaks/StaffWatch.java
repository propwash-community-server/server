package community.propwash.tweaks;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Blocks broken and placed in FabricPlots' /plot admin mode, for the proxy's staff watch
 * (mods/staffwatch): {"t":..., "e":"admin_break" or "admin_place", "uuid":..., "name":..., "server":"plots",
 * "block":..., "x":..., "y":..., "z":..., "plot":"Steve's plot" or "road"} per line in plots-YYYY-MM.jsonl,
 * in the staffwatch-folder of config/tweaks.properties (none: off). Placing is a block (StaffWatchPlaceMixin)
 * or a bucket of water, lava and the like ("block" is then the bucket). The lines are written on a thread
 * of their own, so the server never waits for the disk. FabricPlots is reached by name, so tweaks still
 * runs without it (on fpv) and nothing breaks if it changes.
 */
public final class StaffWatch {
    private static final Logger LOGGER = LoggerFactory.getLogger("tweaks");
    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("tweaks.properties");
    private static Path folder;
    private static Method isBuildAdmin;
    private static Method owningPlot;
    private static Field owner;
    private static Field ownerName;
    private static ExecutorService writer;

    private StaffWatch() {}

    static void init() {
        Properties properties = new Properties();
        if (Files.exists(CONFIG)) {
            try (Reader reader = Files.newBufferedReader(CONFIG, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException e) {
                LOGGER.warn("Couldn't read {}", CONFIG, e);
            }
        }
        String path = properties.getProperty("staffwatch-folder", "").strip();
        if (path.isEmpty()) {
            return;
        }
        try {
            ClassLoader loader = StaffWatch.class.getClassLoader();
            isBuildAdmin = Class.forName("com.fabricplots.protect.PlotProtection", false, loader)
                    .getMethod("isBuildAdmin", ServerPlayer.class);
            owningPlot = Class.forName("com.fabricplots.core.PlotManager", false, loader)
                    .getMethod("owningPlot", int.class, int.class);
            Class<?> data = Class.forName("com.fabricplots.core.PlotData", false, loader);
            owner = data.getField("owner");
            ownerName = data.getField("ownerName");
        } catch (ReflectiveOperationException | LinkageError e) {
            return; // no FabricPlots here: no plot admin mode
        }
        folder = Path.of(path);
        // A daemon thread (it must never keep the server from exiting), emptied when the server stops.
        writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "tweaks staff watch");
            thread.setDaemon(true);
            return thread;
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            writer.shutdown();
            try {
                writer.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayer sp && watched(sp, world)) {
                write(sp, "admin_break", pos, BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            }
        });
    }

    /** StaffWatchPlaceMixin: a block or a bucket's contents placed; what is the block's or the bucket's id. */
    public static void placed(ServerPlayer player, Level level, BlockPos pos, String what) {
        if (watched(player, level)) {
            write(player, "admin_place", pos, what);
        }
    }

    /** In the plot world and in /plot admin mode (the cheap checks first: this runs for every block). */
    private static boolean watched(ServerPlayer player, Level level) {
        return folder != null && "fabricplots:plots".equals(level.dimension().identifier().toString()) && buildAdmin(player);
    }

    private static boolean buildAdmin(ServerPlayer player) {
        try {
            return (boolean) isBuildAdmin.invoke(null, player);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** "Steve's plot", "a server plot" or "road" (not part of a plot). */
    private static String plot(BlockPos pos) {
        try {
            Object plot = owningPlot.invoke(null, pos.getX(), pos.getZ());
            if (plot == null) {
                return "road";
            }
            if (new UUID(0L, 0L).equals(owner.get(plot))) {
                return "a server plot";
            }
            return ownerName.get(plot) + "'s plot";
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "?";
        }
    }

    /** The line is made here (the plot lookup needs the server thread), written on the writer's thread. */
    private static void write(ServerPlayer player, String event, BlockPos pos, String what) {
        JsonObject o = new JsonObject();
        o.addProperty("t", Instant.now().toString());
        o.addProperty("e", event);
        o.addProperty("uuid", player.getUUID().toString());
        o.addProperty("name", player.getGameProfile().name());
        o.addProperty("server", "plots");
        o.addProperty("block", what);
        o.addProperty("x", pos.getX());
        o.addProperty("y", pos.getY());
        o.addProperty("z", pos.getZ());
        o.addProperty("plot", plot(pos));
        String line = o + "\n";
        Path file = folder.resolve("plots-" + YearMonth.now(ZoneOffset.UTC) + ".jsonl");
        try {
            writer.execute(() -> {
                try {
                    Files.createDirectories(folder);
                    Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException e) {
                    LOGGER.warn("Couldn't write to {}", file, e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // the server is stopping
        }
    }
}
