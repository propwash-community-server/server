package community.propwash.tweaks.mixin;

import community.propwash.tweaks.DroneNets;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Drone nets can't be waxed: there are no waxed nets. */
@Mixin(HoneycombItem.class)
abstract class DroneNetWaxMixin {
    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void tweaks$noWax(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (DroneNets.isNet(context.getLevel().getBlockState(context.getClickedPos()))) {
            cir.setReturnValue(InteractionResult.PASS);
        }
    }
}
