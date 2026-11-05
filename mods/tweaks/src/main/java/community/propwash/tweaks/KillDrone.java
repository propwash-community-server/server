package community.propwash.tweaks;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import community.propwash.tweaks.mixin.CommandNodeRequirementAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * /kill drone <player> (propwash.dronekill, staff): removes that player's Propwash drone (one per
 * player, tagged pw_owner_<name>) wherever it is on this server, also while it's flying; one in an
 * unloaded chunk goes as soon as that loads (DroneTp keeps track of those).
 *
 * It sits in vanilla's /kill, which only ops (or minecraft.command.kill) may use. So /kill itself is
 * opened to propwash.dronekill too, and everything vanilla had under it (/kill, /kill <targets>) keeps
 * vanilla's own requirement: staff with only propwash.dronekill see and can use /kill drone and
 * nothing else. Vanilla Permissions wraps all of this afterwards (its phase runs after ours), so
 * minecraft.command.kill still works as before.
 */
final class KillDrone {
    static final String PERMISSION = "propwash.dronekill";

    private KillDrone() {
    }

    @SuppressWarnings("unchecked")
    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        CommandNode<CommandSourceStack> kill = dispatcher.getRoot().getChild("kill");
        if (kill != null) {
            Predicate<CommandSourceStack> vanilla = kill.getRequirement();
            List<CommandNode<CommandSourceStack>> children = new ArrayList<>(kill.getChildren());
            for (CommandNode<CommandSourceStack> child : children) {
                Predicate<CommandSourceStack> own = child.getRequirement();
                ((CommandNodeRequirementAccessor<CommandSourceStack>) child).tweaks$setRequirement(vanilla.and(own));
            }
            ((CommandNodeRequirementAccessor<CommandSourceStack>) kill)
                    .tweaks$setRequirement(vanilla.or(source -> Tweaks.permitted(source, PERMISSION)));
            Command<CommandSourceStack> self = kill.getCommand();
            if (self != null) {
                // Plain /kill (yourself) stays vanilla's.
                dispatcher.register(Commands.literal("kill").executes(context -> {
                    if (vanilla.test(context.getSource())) {
                        return self.run(context);
                    }
                    context.getSource().sendFailure(Component.literal("Usage: /kill drone <player>"));
                    return 0;
                }));
            }
        }
        dispatcher.register(Commands.literal("kill")
                .then(Commands.literal("drone")
                        .requires(source -> Tweaks.permitted(source, PERMISSION))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> run(context.getSource(), EntityArgument.getPlayer(context, "player"))))));
    }

    private static int run(CommandSourceStack source, ServerPlayer owner) {
        String name = owner.getGameProfile().name();
        Entity drone = DroneTp.loaded(source.getServer(), name);
        if (drone != null) {
            drone.discard();
        } else if (DroneTp.unloaded.remove(name) != null) {
            // In an unloaded chunk: it goes as soon as that loads.
            DroneTp.killWhenLoaded.add(name);
        } else {
            source.sendFailure(Component.literal(name + " has no drone out."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Removed " + name + "'s drone."), true);
        if (source.getPlayer() != owner) {
            owner.sendSystemMessage(Component.literal("Staff removed your drone."));
        }
        return 1;
    }
}
