package community.propwash.warmup;

import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.plugin.Plugin;
import java.time.Duration;
import java.time.Instant;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Nobody joins in the first two minutes after the proxy starts, so the servers have time to load.
 * warmup.bypass (staff, admin) gets in anyway.
 */
@Plugin(id = "warmup", name = "Warmup", version = "1.0.0",
        description = "No joining in the first two minutes after the proxy starts")
public final class Warmup {
    private static final Duration WARMUP = Duration.ofMinutes(2);
    private final Instant started = Instant.now();

    // After the default priority: LuckPerms has the player's permissions, LibertyBans has had its say.
    @Subscribe(priority = -100)
    public void onLogin(LoginEvent event) {
        long left = WARMUP.minus(Duration.between(started, Instant.now())).toSeconds();
        if (left <= 0 || !event.getResult().isAllowed() || event.getPlayer().hasPermission("warmup.bypass")) {
            return;
        }
        event.setResult(ResultedEvent.ComponentResult.denied(Component.text(
                "The server is starting. Try again in " + (left + 1) + " seconds.", NamedTextColor.YELLOW)));
    }
}
