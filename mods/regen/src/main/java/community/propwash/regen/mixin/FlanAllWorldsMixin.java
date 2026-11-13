package community.propwash.regen.mixin;

import io.github.flemmli97.flan.config.Config;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Flan's world rules (globalDefaultPerms) hold only for the worlds named in its config; any other world
 * has none, so anyone can build there. The deploy names the vanilla worlds and the server's maps, but not
 * worlds made later (/world create, worlds:*, or maps added by hand). Here a world without its own entry
 * gets a copy of the overworld's rules the first time Flan asks, so every world is protected the same
 * way. Applied only where Flan runs (RegenMixinPlugin); a server whose overworld has no rules is unchanged.
 */
@Mixin(value = Config.class, remap = false)
abstract class FlanAllWorldsMixin {
    @Shadow @Final protected Map<String, Map<Identifier, Config.GlobalType>> globalDefaultPerms;

    @Inject(method = "getGlobal", at = @At("HEAD"))
    private void regen$overworldRules(ServerLevel level, Identifier permission, CallbackInfoReturnable<Config.GlobalType> cir) {
        regen$fill(level);
    }

    @Inject(method = "getGloballyDefinedVals", at = @At("HEAD"))
    private void regen$overworldRulesListed(ServerLevel level, CallbackInfoReturnable<Stream<Map.Entry<Identifier, Config.GlobalType>>> cir) {
        regen$fill(level);
    }

    private void regen$fill(ServerLevel level) {
        String dim = level.dimension().identifier().toString();
        if (globalDefaultPerms.containsKey(dim)) return;
        Map<Identifier, Config.GlobalType> overworld = globalDefaultPerms.get("minecraft:overworld");
        if (overworld != null) globalDefaultPerms.put(dim, new HashMap<>(overworld));
    }
}
