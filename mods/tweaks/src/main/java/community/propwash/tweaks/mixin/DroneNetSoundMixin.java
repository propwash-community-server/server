package community.propwash.tweaks.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import community.propwash.tweaks.DroneNets;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Placing a drone net plays the maps pack's net sound (DroneNets) instead of the copper clink, for
 * everyone around, the placer too.
 */
@Mixin(BlockItem.class)
abstract class DroneNetSoundMixin {
    @WrapOperation(method = "place", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;playSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"))
    private void tweaks$netSound(Level level, Entity entity, BlockPos pos, SoundEvent sound, SoundSource source, float volume,
            float pitch, Operation<Void> original) {
        if (!level.isClientSide() && DroneNets.isNet(level.getBlockState(pos))) {
            original.call(level, null, pos, DroneNets.PLACE, source, 1.0f, 0.9f + level.getRandom().nextFloat() * 0.2f);
        } else {
            original.call(level, entity, pos, sound, source, volume, pitch);
        }
    }
}
