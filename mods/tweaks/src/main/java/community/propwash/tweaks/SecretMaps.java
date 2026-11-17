package community.propwash.tweaks;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

/**
 * Secret maps: config/tweaks.properties lines "hidden.<namespace>.<path> = true" (the deploy writes them from
 * hidden = true in servers/<server>/maps/<map>.toml), read every time, so no restart is needed. Their courses:
 * <ul>
 * <li>are not in /race, /map, /rtp or their suggestions (Race); /race <course> still goes there;</li>
 * <li>have their lap and class records told only to whoever set them, not to the other pilots on the map
 * (RaceAnnounceMixin);</li>
 * <li>are not sent to the Discord bot: no records, results or leaderboards (BridgeHiddenMixin).</li>
 * </ul>
 */
public final class SecretMaps {
    private static volatile MinecraftServer server;

    private SecretMaps() {
    }

    static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> server = null);
    }

    /** The secret maps' dimensions ("maps:flowmap"). */
    public static Set<String> dimensions() {
        Set<String> hidden = new HashSet<>();
        Path file = FabricLoader.getInstance().getConfigDir().resolve("tweaks.properties");
        if (!Files.exists(file)) {
            return hidden;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        } catch (IOException e) {
            return hidden;
        }
        for (String key : properties.stringPropertyNames()) {
            // "hidden.maps.flowmap": the dimension's namespace, then its path ("." for its ":").
            if (key.startsWith("hidden.") && "true".equalsIgnoreCase(properties.getProperty(key).strip())) {
                String dimension = key.substring("hidden.".length());
                int dot = dimension.indexOf('.');
                if (dot > 0) {
                    hidden.add(dimension.substring(0, dot) + ":" + dimension.substring(dot + 1));
                }
            }
        }
        return hidden;
    }

    public static boolean hidden(String dimension) {
        return dimensions().contains(dimension);
    }

    /**
     * The ids (UUIDs, as text) of the courses on secret maps, from Propwash; empty before the server started.
     * Only call where Propwash is loaded (the bridge mixin): it's the only thing here that touches Propwash.
     */
    public static Set<String> courseIds() {
        MinecraftServer s = server;
        Set<String> hidden = dimensions();
        return s == null || hidden.isEmpty() ? new HashSet<>() : PropwashCourses.on(s, hidden);
    }
}
