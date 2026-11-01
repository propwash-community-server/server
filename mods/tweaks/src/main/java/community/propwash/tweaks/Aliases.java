package community.propwash.tweaks;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.Map;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * Short forms of vanilla commands: /gm <survival|creative|adventure|spectator> (or 0, 1, 2, 3, spec),
 * /gmc, /gms for /gamemode, and /ci for /clear. Each runs the vanilla command as the player, so whoever
 * may use /gamemode or /clear (op, or LuckPerms through Vanilla Permissions) may use its short form,
 * and nobody else sees it.
 */
final class Aliases {
    private static final Map<String, String> GAME_MODES = Map.ofEntries(
            Map.entry("survival", "survival"), Map.entry("0", "survival"),
            Map.entry("creative", "creative"), Map.entry("1", "creative"),
            Map.entry("adventure", "adventure"), Map.entry("2", "adventure"),
            Map.entry("spectator", "spectator"), Map.entry("3", "spectator"), Map.entry("spec", "spectator"));

    private Aliases() {
    }

    static void init() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            LiteralArgumentBuilder<CommandSourceStack> gm = alias(dispatcher, "gm", "gamemode");
            GAME_MODES.forEach((typed, mode) -> gm.then(Commands.literal(typed).executes(c -> run(c.getSource(), "gamemode " + mode))));
            dispatcher.register(gm);
            dispatcher.register(alias(dispatcher, "gmc", "gamemode").executes(c -> run(c.getSource(), "gamemode creative")));
            dispatcher.register(alias(dispatcher, "gms", "gamemode").executes(c -> run(c.getSource(), "gamemode survival")));
            dispatcher.register(alias(dispatcher, "ci", "clear").executes(c -> run(c.getSource(), "clear")));
        });
    }

    /** Usable by whoever may use the vanilla command, checked when used (Vanilla Permissions changes it later). */
    private static LiteralArgumentBuilder<CommandSourceStack> alias(CommandDispatcher<CommandSourceStack> dispatcher,
            String name, String command) {
        return Commands.literal(name).requires(source -> {
            var node = dispatcher.getRoot().getChild(command);
            return node != null && node.canUse(source);
        });
    }

    private static int run(CommandSourceStack source, String command) {
        source.getServer().getCommands().performPrefixedCommand(source, command);
        return 1;
    }
}
