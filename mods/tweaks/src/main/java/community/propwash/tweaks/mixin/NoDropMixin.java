package community.propwash.tweaks.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A dropped item (Q, out of the inventory, from the creative menu) goes back into the inventory
 * instead of landing on the ground: it has already left the inventory when this runs. The selected
 * slot comes first, so Q puts it back where it was; what doesn't fit is deleted. Players tagged
 * propwash.exempt (admins: /tag @s add propwash.exempt) drop items as usual.
 */
@Mixin(ServerPlayer.class)
abstract class NoDropMixin {
    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"), cancellable = true)
    private void tweaks$returnDrop(ItemStack stack, boolean around, Prediction prediction, CallbackInfoReturnable<ItemEntity> cir) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (player.entityTags().contains("propwash.exempt")) {
            return;
        }
        // Not placeItemBackInInventory: it drops what doesn't fit, which would come back here.
        Inventory inventory = player.getInventory();
        if (!stack.isEmpty() && inventory.getSelectedItem().isEmpty()) {
            inventory.setSelectedItem(stack.copyAndClear());
        } else if (!stack.isEmpty()) {
            inventory.add(stack);
        }
        cir.setReturnValue(null);
    }
}
