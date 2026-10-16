package community.propwash.tweaks.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A dropped item (Q, out of the inventory, from the creative menu) is deleted instead of landing on
 * the ground: it has already left the inventory when this runs. Players tagged propwash.exempt
 * (admins: /tag @s add propwash.exempt) drop items as usual.
 */
@Mixin(ServerPlayer.class)
abstract class NoDropMixin {
    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"), cancellable = true)
    private void tweaks$deleteDrop(ItemStack stack, boolean around, Prediction prediction, CallbackInfoReturnable<ItemEntity> cir) {
        if (!((ServerPlayer) (Object) this).entityTags().contains("propwash.exempt")) {
            cir.setReturnValue(null);
        }
    }
}
