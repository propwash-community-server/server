package community.propwash.quiet.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** "X joined the game": only to the server log, not to the players. */
@Mixin(PlayerList.class)
abstract class PlayerListMixin {
    @Redirect(method = "placeNewPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
    private void quiet$logJoin(PlayerList players, Component message, boolean overlay) {
        players.getServer().sendSystemMessage(message);
    }
}
