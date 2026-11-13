package community.propwash.seeplayers.mixin;

import community.propwash.seeplayers.SeePlayers;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Un-vanishing (the Vanish mod) heals too. By name, so this builds and runs without Vanish; require = 0:
 * if a Vanish update changes setVanished, only the heal after /v goes (the one after joins stays).
 */
@Pseudo
@Mixin(targets = "me.drex.vanish.util.VanishManager")
abstract class VanishManagerMixin {
    @Inject(method = "setVanished", at = @At("RETURN"), require = 0)
    private static void seeplayers$unvanished(NameAndId profile, MinecraftServer server, boolean vanish, CallbackInfoReturnable<Boolean> cir) {
        if (!vanish && cir.getReturnValueZ()) {
            SeePlayers.unvanished(profile.id());
        }
    }
}
