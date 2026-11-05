package community.propwash.tweaks.mixin;

import com.mojang.brigadier.tree.CommandNode;
import java.util.function.Predicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets KillDrone open vanilla's /kill to staff for /kill drone only (see there). */
@Mixin(value = CommandNode.class, remap = false)
public interface CommandNodeRequirementAccessor<S> {
    @Mutable
    @Accessor("requirement")
    void tweaks$setRequirement(Predicate<S> requirement);
}
