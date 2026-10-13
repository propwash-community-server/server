package community.propwash.staffhelp;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.command.CommandExecuteEvent.CommandResult;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

/**
 * The staff part of /help: after a server's own /help, staff and admin (propwash.help.staff) also get
 * plugins/staffhelp/help.txt (MiniMessage, read on every use; its blank first line separates it).
 * /help staff or /help admin shows only that part.
 */
@Plugin(id = "staffhelp", name = "StaffHelp", version = "1.0.0",
        description = "The staff part of /help")
public final class StaffHelp {
    private static final String PERMISSION = "propwash.help.staff";
    // The server's /help comes back through the proxy first; this lands after it.
    private static final long DELAY_MS = 300;

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path helpFile;

    @Inject
    public StaffHelp(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.helpFile = dataDirectory.resolve("help.txt");
    }

    @Subscribe
    public void onCommand(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player player) || !player.hasPermission(PERMISSION)) {
            return;
        }
        String command = event.getCommand().trim().toLowerCase();
        if (command.equals("help staff") || command.equals("help admin")) {
            event.setResult(CommandResult.denied());
            show(player);
        } else if (command.equals("help")) {
            proxy.getScheduler().buildTask(this, () -> show(player)).delay(DELAY_MS, TimeUnit.MILLISECONDS).schedule();
        }
    }

    private void show(Player player) {
        try {
            player.sendMessage(MiniMessage.miniMessage().deserialize(Files.readString(helpFile).stripTrailing()));
        } catch (IOException e) {
            logger.warn("Can't read {}", helpFile, e);
        }
    }
}
