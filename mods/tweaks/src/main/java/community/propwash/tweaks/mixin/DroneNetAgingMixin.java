package community.propwash.tweaks.mixin;

import community.propwash.tweaks.DroneNets;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.WeatheringCopperBarsBlock;
import net.minecraft.world.level.block.WeatheringCopperTrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drone nets don't age like copper (a worn net would turn into another kind of net). */
@Mixin({WeatheringCopperBarsBlock.class, WeatheringCopperTrapDoorBlock.class})
abstract class DroneNetAgingMixin {
    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void tweaks$noAging(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (DroneNets.isNet(state)) {
            ci.cancel();
        }
    }
}
