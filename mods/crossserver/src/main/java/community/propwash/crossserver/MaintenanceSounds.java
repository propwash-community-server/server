package community.propwash.crossserver;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.slf4j.Logger;

/**
 * A sound with the Maintenance plugin's messages to everyone: each countdown line of /maintenance
 * starttimer, endtimer and scheduletimer, and maintenance turning on or off. Maintenance has no events
 * for its countdown, so this looks at it five times a second, by reflection on its plugin (5.1.0;
 * MaintenancePlugin.isMaintenance, getCurrentTask().getSecondsLeft()). Its timer broadcasts when the
 * seconds left are one of its timer-broadcast-for-seconds and then counts down by one, so seeing n
 * left means it has just broadcast for n + 1.
 */
final class MaintenanceSounds {
    private static final Sound COUNTDOWN = Sound.sound(Key.key("block.note_block.bit"), Sound.Source.MASTER, 1f, 1f);
    private static final Sound CHANGED = Sound.sound(Key.key("block.note_block.bell"), Sound.Source.MASTER, 1f, 1f);
    private static final long PERIOD_MS = 200;

    private final ProxyServer proxy;
    private final Object plugin;
    private final Logger logger;

    private ScheduledTask timer;
    private Object maintenance;
    private Method isMaintenance;
    private Method getCurrentTask;
    private Method getSecondsLeft;
    private Method getBroadcastIntervals;
    private Object settings;
    private Boolean wasOn;
    private int lastSeconds = -1;

    MaintenanceSounds(ProxyServer proxy, Object plugin, Logger logger) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.logger = logger;
    }

    void register() {
        maintenance = proxy.getPluginManager().getPlugin("maintenance").<Object>flatMap(p -> p.getInstance().map(i -> i)).orElse(null);
        if (maintenance == null) {
            return;
        }
        try {
            Class<?> type = maintenance.getClass();
            isMaintenance = type.getMethod("isMaintenance");
            getCurrentTask = type.getMethod("getCurrentTask");
            settings = type.getMethod("getSettings").invoke(maintenance);
            getBroadcastIntervals = settings.getClass().getMethod("getBroadcastIntervals");
        } catch (ReflectiveOperationException e) {
            logger.warn("Maintenance sounds off: this Maintenance version looks different", e);
            return;
        }
        timer = proxy.getScheduler().buildTask(plugin, this::check).repeat(PERIOD_MS, TimeUnit.MILLISECONDS).schedule();
    }

    private void check() {
        try {
            boolean on = (Boolean) isMaintenance.invoke(maintenance);
            if (wasOn != null && on != wasOn) {
                play(CHANGED);
            }
            wasOn = on;
            Object task = getCurrentTask.invoke(maintenance);
            if (task == null) {
                lastSeconds = -1;
                return;
            }
            if (getSecondsLeft == null) {
                getSecondsLeft = task.getClass().getMethod("getSecondsLeft");
            }
            int seconds = (Integer) getSecondsLeft.invoke(task);
            if (seconds != lastSeconds && ((Set<?>) getBroadcastIntervals.invoke(settings)).contains(seconds + 1)) {
                play(COUNTDOWN);
            }
            lastSeconds = seconds;
        } catch (ReflectiveOperationException | ClassCastException e) {
            logger.warn("Maintenance sounds off", e);
            timer.cancel();
        }
    }

    private void play(Sound sound) {
        // Velocity can only play a sound at the player themselves.
        proxy.getAllPlayers().forEach(p -> p.playSound(sound, Sound.Emitter.self()));
    }
}
