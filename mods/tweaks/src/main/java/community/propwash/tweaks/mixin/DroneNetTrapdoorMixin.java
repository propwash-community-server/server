package community.propwash.tweaks.mixin;

import community.propwash.tweaks.DroneNets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Flat drone nets (copper trapdoors, DroneNets) never open: not by hand, wind charges or redstone. */
@Mixin(TrapDoorBlock.class)
abstract class DroneNetTrapdoorMixin {
    @Inject(method = "useWithoutItem", at = @At("HEAD"), cancellable = true)
    private void tweaks$noHand(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit,
            CallbackInfoReturnable<InteractionResult> cir) {
        if (DroneNets.isNet(state)) {
            cir.setReturnValue(InteractionResult.PASS);
        }
    }

    @Inject(method = "toggle", at = @At("HEAD"), cancellable = true)
    private void tweaks$noToggle(BlockState state, Level level, BlockPos pos, Player player, CallbackInfo ci) {
        if (DroneNets.isNet(state)) {
            ci.cancel();
        }
    }

    @Inject(method = "neighborChanged", at = @At("HEAD"), cancellable = true)
    private void tweaks$noRedstone(BlockState state, Level level, BlockPos pos, Block block, Orientation orientation,
            boolean moved, CallbackInfo ci) {
        if (DroneNets.isNet(state)) {
            ci.cancel();
        }
    }
}
