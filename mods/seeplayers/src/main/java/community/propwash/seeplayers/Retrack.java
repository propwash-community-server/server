package community.propwash.seeplayers;

import net.minecraft.server.level.ServerPlayer;

/** ChunkMap.TrackedEntity (package-private in Minecraft), through TrackedEntityMixin. */
public interface Retrack {
    /** Sends the entity to viewer afresh: removed on their client, then added again if in range. */
    void seeplayers$retrack(ServerPlayer viewer);
}
