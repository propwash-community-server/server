package community.propwash.tweaks.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DebugStickItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The debug stick works for everyone in the plot world (fabricplots:plots), not only for ops.
 * FabricPlots still decides where: both of its clicks (left picks a property, right changes it) go
 * through its plot protection first, so only on plots the player may build on. It only changes block
 * states, never the block itself.
 */
@Mixin(DebugStickItem.class)
abstract class DebugStickMixin {
    @WrapOperation(method = "handleInteraction", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;canUseGameMasterBlocks()Z"))
    private boolean tweaks$plotWorld(ServerPlayer player, Operation<Boolean> original) {
        return original.call(player) || "fabricplots:plots".equals(player.level().dimension().identifier().toString());
    }
}
