package community.propwash.seeplayers.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    /** Entity id -> its TrackedEntity (a Retrack). */
    @Accessor("entityMap")
    Int2ObjectMap<?> seeplayers$entityMap();
}
