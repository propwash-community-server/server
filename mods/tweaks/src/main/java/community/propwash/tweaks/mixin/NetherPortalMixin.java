package community.propwash.tweaks.mixin;

import community.propwash.tweaks.Portals;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Nether portal blocks inside one of Portals' portals only look the part: nobody goes to the nether through them. */
@Mixin(NetherPortalBlock.class)
abstract class NetherPortalMixin {
    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private void tweaks$serverPortal(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effects,
                                     boolean intersects, CallbackInfo ci) {
        if (Portals.at(level, pos) != null) {
            ci.cancel();
        }
    }
}
