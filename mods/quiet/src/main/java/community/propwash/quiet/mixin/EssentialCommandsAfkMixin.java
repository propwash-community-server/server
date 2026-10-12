package community.propwash.quiet.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Essential Commands' "X is now AFK" / "X is no longer AFK": only to the server log, not to the
 * players. By name, so the mod builds without Essential Commands. require = 0: if an Essential
 * Commands update changes setAfk, the messages come back instead of the server crashing on join.
 */
@Pseudo
@Mixin(targets = "com.fibermc.essentialcommands.playerdata.PlayerData")
abstract class EssentialCommandsAfkMixin {
    @WrapOperation(method = "setAfk", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
    private void quiet$logAfk(PlayerList players, Component message, boolean overlay, Operation<Void> original) {
        players.getServer().sendSystemMessage(message);
    }
}
