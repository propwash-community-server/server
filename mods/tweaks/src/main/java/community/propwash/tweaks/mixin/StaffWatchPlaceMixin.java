package community.propwash.tweaks.mixin;

import community.propwash.tweaks.StaffWatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A block a player placed, for the staff watch (StaffWatch: only in /plot admin mode). */
@Mixin(BlockItem.class)
abstract class StaffWatchPlaceMixin {
    @Inject(method = "placeBlock", at = @At("RETURN"))
    private void tweaks$staffWatch(BlockPlaceContext context, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && context.getPlayer() instanceof ServerPlayer player) {
            StaffWatch.placed(player, context.getLevel(), context.getClickedPos(),
                    BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        }
    }
}
