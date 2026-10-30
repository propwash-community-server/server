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
 * /help staff or /help admin shows only that part. Before it, players with propwash.help.supporter
 * get plugins/staffhelp/supporter.txt: no blank line, so it reads as more of the "On every server"
 * lines that end every /help. Mods (propwash.help.mod, not staff) get plugins/staffhelp/mod.txt
 * instead of the supporter part (it has the supporter commands they have, too); /help mod shows only it.
 * Helpers (propwash.help.helper, not mod or staff) likewise get plugins/staffhelp/helper.txt; /help helper
 * shows only it.
 */
@Plugin(id = "staffhelp", name = "StaffHelp", version = "1.3.0",
        description = "The supporter, helper, mod and staff parts of /help")
public final class StaffHelp {
    private static final String PERMISSION = "propwash.help.staff";
    private static final String SUPPORTER = "propwash.help.supporter";
    private static final String MOD = "propwash.help.mod";
    private static final String HELPER = "propwash.help.helper";
    // The server's /help comes back through the proxy first; this lands after it.
    private static final long DELAY_MS = 300;

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path helpFile;
    private final Path supporterFile;
    private final Path modFile;
    private final Path helperFile;

    @Inject
    public StaffHelp(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.helpFile = dataDirectory.resolve("help.txt");
        this.supporterFile = dataDirectory.resolve("supporter.txt");
        this.modFile = dataDirectory.resolve("mod.txt");
        this.helperFile = dataDirectory.resolve("helper.txt");
    }

    @Subscribe
    public void onCommand(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player player)) {
            return;
        }
        boolean staff = player.hasPermission(PERMISSION);
        // Admins' "*" includes the mod node: staff get the staff part, not the mod one.
        boolean mod = !staff && player.hasPermission(MOD);
        boolean helper = !staff && !mod && player.hasPermission(HELPER);
        boolean supporter = !mod && !helper && player.hasPermission(SUPPORTER);
        String command = event.getCommand().trim().toLowerCase();
        if (staff && (command.equals("help staff") || command.equals("help admin"))) {
            event.setResult(CommandResult.denied());
            show(player, helpFile);
        } else if (mod && command.equals("help mod")) {
            event.setResult(CommandResult.denied());
            show(player, modFile);
        } else if (helper && command.equals("help helper")) {
            event.setResult(CommandResult.denied());
            show(player, helperFile);
        } else if (command.equals("help") && (staff || mod || helper || supporter)) {
            proxy.getScheduler().buildTask(this, () -> {
                if (supporter) {
                    show(player, supporterFile);
                }
                if (helper) {
                    show(player, helperFile);
                }
                if (mod) {
                    show(player, modFile);
                }
                if (staff) {
                    show(player, helpFile);
                }
            }).delay(DELAY_MS, TimeUnit.MILLISECONDS).schedule();
        }
    }

    private void show(Player player, Path file) {
        try {
            if (Files.exists(file)) {
                player.sendMessage(MiniMessage.miniMessage().deserialize(Files.readString(file).stripTrailing()));
            }
        } catch (IOException e) {
            logger.warn("Can't read {}", file, e);
        }
    }
}
