package community.propwash.warmup;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

/**
 * Nobody joins in the first two minutes after the proxy starts, so the servers have time to load,
 * or during a lockdown (/lockdown, which also kicks everyone). /open ends either. warmup.bypass
 * (staff, admin) gets in anyway and isn't kicked; the commands need warmup.admin.
 */
@Plugin(id = "warmup", name = "Warmup", version = "1.1.0",
        description = "No joining in the first two minutes after the proxy starts, or during /lockdown")
public final class Warmup {
    private static final Duration WARMUP = Duration.ofMinutes(2);
    private static final String BYPASS = "warmup.bypass";
    private static final String ADMIN = "warmup.admin";
    private static final Component LOCKED =
            Component.text("The server is temporarily closed to the public. Try again soon.", NamedTextColor.YELLOW);

    private final ProxyServer proxy;
    private final Logger logger;
    // Kept across restarts: a restart doesn't end a lockdown.
    private final Path lockFile;
    private volatile Instant openAt = Instant.now().plus(WARMUP);
    private volatile boolean lockdown;

    @Inject
    public Warmup(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.lockFile = dataDirectory.resolve("lockdown");
        this.lockdown = Files.exists(lockFile);
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        if (lockdown) {
            logger.info("In lockdown since before the restart; /open ends it");
        }
        register("lockdown", this::lockdown);
        register("open", this::open);
    }

    // After the default priority: LuckPerms has the player's permissions, LibertyBans has had its say.
    @Subscribe(priority = -100)
    public void onLogin(LoginEvent event) {
        if (!event.getResult().isAllowed() || event.getPlayer().hasPermission(BYPASS)) {
            return;
        }
        if (lockdown) {
            event.setResult(ResultedEvent.ComponentResult.denied(LOCKED));
            return;
        }
        long left = Duration.between(Instant.now(), openAt).toSeconds();
        if (left >= 0) {
            event.setResult(ResultedEvent.ComponentResult.denied(Component.text(
                    "The server is starting. Try again in " + (left + 1) + " seconds.", NamedTextColor.YELLOW)));
        }
    }

    private void lockdown(CommandSource source) {
        setLockdown(true);
        int kicked = 0;
        for (Player player : proxy.getAllPlayers()) {
            if (!player.hasPermission(BYPASS)) {
                player.disconnect(LOCKED);
                kicked++;
            }
        }
        logger.info("Lockdown by {}: {} player(s) kicked", name(source), kicked);
        source.sendMessage(Component.text("Lockdown: kicked " + kicked
                + " player(s); only staff can join until /open.", NamedTextColor.YELLOW));
    }

    private void open(CommandSource source) {
        boolean wasLocked = lockdown;
        setLockdown(false);
        openAt = Instant.now();
        logger.info("Opened by {}", name(source));
        source.sendMessage(Component.text(wasLocked ? "Lockdown over: everyone can join again."
                : "Everyone can join now.", NamedTextColor.GREEN));
    }

    private void setLockdown(boolean on) {
        lockdown = on;
        try {
            if (on) {
                Files.createDirectories(lockFile.getParent());
                Files.writeString(lockFile, "");
            } else {
                Files.deleteIfExists(lockFile);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void register(String name, java.util.function.Consumer<CommandSource> action) {
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder(name).plugin(this).build(),
                new SimpleCommand() {
                    @Override
                    public void execute(Invocation invocation) {
                        action.accept(invocation.source());
                    }

                    @Override
                    public boolean hasPermission(Invocation invocation) {
                        return invocation.source().hasPermission(ADMIN);
                    }
                });
    }

    private static String name(CommandSource source) {
        return source instanceof Player player ? player.getUsername() : "the console";
    }
}
