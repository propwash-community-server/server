package community.propwash.tweaks.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import community.propwash.tweaks.SecretMaps;
import de.lardtom.propwash.race.RaceServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Propwash tells the pilots near a new lap or class record about it ("X sets a lap record on course Y");
 * on secret maps (SecretMaps) only the pilot who set it hears. Only applied when Propwash is loaded
 * (TweaksMixinPlugin).
 */
@Mixin(value = RaceServer.class, remap = false)
abstract class RaceAnnounceMixin {
    @WrapOperation(method = "announce", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;sendSystemMessage(Lnet/minecraft/network/chat/Component;)V"))
    private static void tweaks$secretRecord(ServerPlayer to, Component message, Operation<Void> original,
            @Local(argsOnly = true) ServerPlayer pilot) {
        if (to != pilot && SecretMaps.hidden(pilot.level().dimension().identifier().toString())) {
            return;
        }
        original.call(to, message);
    }
}
