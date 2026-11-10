package community.propwash.signs.mixin;

import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla only sets allow_op_features from NBT; without it a sign's click commands don't run. */
@Mixin(SignBlockEntity.class)
public interface SignBlockEntityAccessor {
    @Accessor("allowOpFeatures")
    void signs$setAllowOpFeatures(boolean allow);

    @Invoker("markUpdated")
    void signs$markUpdated();
}
