package community.propwash.tweaks.mixin;

import community.propwash.tweaks.DroneNets;
import net.minecraft.core.component.BlockTransformer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Axes don't scrape drone nets (that would turn one kind of net into another, or into copper). */
@Mixin(BlockTransformer.class)
abstract class DroneNetToolMixin {
    @Inject(method = "transformBlock", at = @At("HEAD"), cancellable = true)
    private void tweaks$noScrape(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (DroneNets.isNet(context.getLevel().getBlockState(context.getClickedPos()))) {
            cir.setReturnValue(InteractionResult.PASS);
        }
    }
}
