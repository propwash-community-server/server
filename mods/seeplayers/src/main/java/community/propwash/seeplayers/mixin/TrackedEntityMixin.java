package community.propwash.seeplayers.mixin;

import community.propwash.seeplayers.Retrack;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
abstract class TrackedEntityMixin implements Retrack {
    @Shadow
    public abstract void removePlayer(ServerPlayer player);

    @Shadow
    public abstract void updatePlayer(ServerPlayer player);

    @Override
    public void seeplayers$retrack(ServerPlayer viewer) {
        removePlayer(viewer);
        updatePlayer(viewer);
    }
}
