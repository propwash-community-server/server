package community.propwash.tweaks.mixin;

import community.propwash.tweaks.StaffWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Water, lava and the like a player poured from a bucket, for the staff watch (only in /plot admin mode). */
@Mixin(BucketItem.class)
abstract class StaffWatchBucketMixin {
    @Inject(method = "emptyContents", at = @At("RETURN"))
    private void tweaks$staffWatch(LivingEntity entity, Level level, BlockPos pos, BlockHitResult hit,
                                   CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && entity instanceof ServerPlayer player) {
            StaffWatch.placed(player, level, pos, BuiltInRegistries.ITEM.getKey((BucketItem) (Object) this).toString());
        }
    }
}
