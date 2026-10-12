package community.propwash.quiet.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * "X joined the game": only to the server log, not to the players. A wrapper (not a redirect), so it
 * works next to other mods wrapping the same call, like Vanish (all three hooks here do it this way).
 */
@Mixin(PlayerList.class)
abstract class PlayerListMixin {
    @WrapOperation(method = "placeNewPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
    private void quiet$logJoin(PlayerList players, Component message, boolean overlay, Operation<Void> original) {
        players.getServer().sendSystemMessage(message);
    }
}
