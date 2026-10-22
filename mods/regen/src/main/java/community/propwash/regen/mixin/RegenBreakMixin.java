package community.propwash.regen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import community.propwash.regen.Regen;
import de.lardtom.propwash.interact.DroneBreaks;
import de.lardtom.propwash.platform.IPlatformHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Propwash's last check before a drone breaks a block is the platform's "may this player break it",
 * which Flan refuses everywhere on fpv: probeBlockBreak when a fast drone asks ahead (clearance; a
 * refusal there and the client never tries) and allowBlockBreak for the break itself (attempt). Where
 * Regen lets drones break (a map with breaking on, a block without data, in Propwash's drone_breakable
 * tag, which Propwash checked already), both pass; Flan still keeps players from breaking blocks by
 * hand. Everything else is asked as before.
 */
@Mixin(value = DroneBreaks.class, remap = false)
abstract class RegenBreakMixin {
    @WrapOperation(method = "attempt", at = @At(value = "INVOKE",
            target = "Lde/lardtom/propwash/platform/IPlatformHelper;allowBlockBreak(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;)Z"))
    private static boolean regen$allowOnMaps(IPlatformHelper platform, ServerLevel level, ServerPlayer player, BlockPos pos,
                                             BlockState state, BlockEntity blockEntity, Operation<Boolean> original) {
        return Regen.allowed(level, pos) || original.call(platform, level, player, pos, state, blockEntity);
    }

    @WrapOperation(method = "clearance", at = @At(value = "INVOKE",
            target = "Lde/lardtom/propwash/platform/IPlatformHelper;probeBlockBreak(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;)Z"))
    private static boolean regen$probeOnMaps(IPlatformHelper platform, ServerLevel level, ServerPlayer player, BlockPos pos,
                                             BlockState state, BlockEntity blockEntity, Operation<Boolean> original) {
        return Regen.allowed(level, pos) || original.call(platform, level, player, pos, state, blockEntity);
    }
}
