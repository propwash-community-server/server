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
 *
 * A bare /warp is /warp list (Essential Commands only has /warp list|tp|set|delete): whoever may use
 * /warp list may type /warp alone; /warp list itself is unchanged. Where there are no warps (plots), there's
 * no /warp list, and so no bare /warp either.
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
            // Merged into Essential Commands' /warp whichever registers first (Brigadier keeps the first
            // node's requirement and takes the command of whichever has one).
            dispatcher.register(Commands.literal("warp").requires(source -> {
                var warp = dispatcher.getRoot().getChild("warp");
                var list = warp == null ? null : warp.getChild("list");
                return list != null && list.canUse(source);
            }).executes(c -> run(c.getSource(), "warp list")));
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
