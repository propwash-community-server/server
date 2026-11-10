package community.propwash.pets.mixin;

import community.propwash.pets.Pets;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A vanished owner's pet is sent to the owner only (entity tracking asks this before sending an entity). */
@Mixin(Entity.class)
abstract class EntityMixin {
    @Inject(method = "broadcastToPlayer", at = @At("HEAD"), cancellable = true)
    private void pets$onlyOwner(ServerPlayer viewer, CallbackInfoReturnable<Boolean> cir) {
        if (!Pets.visibleTo((Entity) (Object) this, viewer)) {
            cir.setReturnValue(false);
        }
    }
}
