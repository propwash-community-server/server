package community.propwash.startlock;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.kennytv.maintenance.api.MaintenanceProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;

/**
 * Maintenance for the first 80 seconds after the proxy starts, so the servers have time to load
 * (Maintenance's endtimer, so the server list counts down; staff and admin get in, maintenance.bypass).
 * If Maintenance was already on (someone closed the network), it stays on. /close and /lock are
 * /maintenance on, /open is /maintenance off, with Maintenance's permissions.
 */
@Plugin(id = "startlock", name = "StartLock", version = "1.0.0",
        description = "Maintenance for 80 seconds after the proxy starts; /open, /close, /lock",
        dependencies = {@Dependency(id = "maintenance")})
public final class StartLock {
    private static final String LOCK = "80s";
    private static final String PERMISSION = "maintenance.admin";
    // Set while the lock is ours: a restart within that time locks again instead of staying closed.
    private static final Duration MARKER_AGE = Duration.ofMinutes(10);

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path marker;

    @Inject
    public StartLock(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.marker = dataDirectory.resolve("startup-lock");
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) throws IOException {
        var maintenance = MaintenanceProvider.get();
        boolean ours = Files.exists(marker)
                && Files.getLastModifiedTime(marker).toInstant().plus(MARKER_AGE).isAfter(Instant.now());
        if (maintenance.isMaintenance() && !ours) {
            logger.info("Maintenance was on before the restart; it stays on (/open ends it)");
        } else {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "");
            maintenance.setMaintenance(true);
            run(proxy.getConsoleCommandSource(), "maintenance endtimer " + LOCK);
            logger.info("Maintenance for {} while the servers start (/open ends it)", LOCK);
        }
        register("open", "maintenance off");
        register("close", "maintenance on");
        register("lock", "maintenance on");
    }

    private void register(String name, String command) {
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder(name).plugin(this).build(),
                new SimpleCommand() {
                    @Override
                    public void execute(Invocation invocation) {
                        try {
                            Files.deleteIfExists(marker);
                        } catch (IOException e) {
                            logger.warn("Can't remove {}", marker, e);
                        }
                        if (MaintenanceProvider.get().isTaskRunning()) {
                            run(invocation.source(), "maintenance aborttimer");
                        }
                        run(invocation.source(), command);
                    }

                    @Override
                    public boolean hasPermission(Invocation invocation) {
                        return invocation.source().hasPermission(PERMISSION);
                    }
                });
    }

    private void run(CommandSource source, String command) {
        proxy.getCommandManager().executeImmediatelyAsync(source, command).join();
    }
}
