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
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Blocks broken in FabricPlots' /plot admin mode, for the proxy's staff watch (mods/staffwatch):
 * {"t":..., "e":"admin_break", "uuid":..., "name":..., "server":"plots", "block":..., "x":..., "y":...,
 * "z":..., "plot":"Steve's plot" or "road"} per line in plots-YYYY-MM.jsonl, in the staffwatch-folder
 * of config/tweaks.properties (none: off). FabricPlots is reached by name, so tweaks still runs
 * without it (on fpv) and nothing breaks if it changes.
 */
final class StaffWatch {
    private static final Logger LOGGER = LoggerFactory.getLogger("tweaks");
    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("tweaks.properties");
    private static Path folder;
    private static Method isBuildAdmin;
    private static Method owningPlot;
    private static Field owner;
    private static Field ownerName;

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
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayer sp && "fabricplots:plots".equals(world.dimension().identifier().toString())
                    && buildAdmin(sp)) {
                write(sp, pos, state);
            }
        });
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

    private static synchronized void write(ServerPlayer player, BlockPos pos, BlockState state) {
        JsonObject o = new JsonObject();
        o.addProperty("t", Instant.now().toString());
        o.addProperty("e", "admin_break");
        o.addProperty("uuid", player.getUUID().toString());
        o.addProperty("name", player.getGameProfile().name());
        o.addProperty("server", "plots");
        o.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        o.addProperty("x", pos.getX());
        o.addProperty("y", pos.getY());
        o.addProperty("z", pos.getZ());
        o.addProperty("plot", plot(pos));
        Path file = folder.resolve("plots-" + YearMonth.now(ZoneOffset.UTC) + ".jsonl");
        try {
            Files.createDirectories(folder);
            Files.writeString(file, o + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.warn("Couldn't write to {}", file, e);
        }
    }
}
