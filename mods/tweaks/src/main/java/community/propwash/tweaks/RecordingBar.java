package community.propwash.tweaks;

import de.lardtom.propwash.drone.DronePiloting;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.bossevents.CustomBossEvent;
import net.minecraft.server.level.ServerPlayer;

/**
 * The /record boss bar (mods/crossserver Recording) with the server address: the proxy tags players
 * in recording mode propwash.recording and makes the boss bar propwash:recording over rcon. Every tick
 * it goes to exactly the tagged players who aren't flying their drone, so it stays out of the drone
 * camera's picture.
 */
final class RecordingBar {
    private static final Identifier BAR = Identifier.parse("propwash:recording");
    private static final String TAG = "propwash.recording";

    private RecordingBar() {
    }

    static void init() {
        boolean drones = FabricLoader.getInstance().isModLoaded("propwash");
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            CustomBossEvent bar = server.getCustomBossEvents().get(BAR);
            if (bar == null) {
                return;
            }
            List<ServerPlayer> shown = server.getPlayerList().getPlayers().stream()
                    .filter(p -> p.entityTags().contains(TAG))
                    .filter(p -> !drones || DronePiloting.pilotedBy(p.getUUID()).isEmpty())
                    .toList();
            bar.setPlayers(shown);
        });
    }
}
