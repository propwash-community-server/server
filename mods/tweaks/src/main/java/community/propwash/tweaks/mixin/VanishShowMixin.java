package community.propwash.tweaks.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Vanish mod updates the player list when someone vanishes or un-vanishes, but leaves the body to
 * vanilla tracking, which only re-checks who sees whom when someone moves into another 16-block
 * section: an un-vanished player stayed invisible to those near them. ChunkMap.move re-checks it for
 * everyone at once. By name, so this builds without Vanish. require = 0: if a Vanish update changes
 * setVanished, only the bug comes back (no crash when someone joins); check after updating Vanish.
 */
@Pseudo
@Mixin(targets = "me.drex.vanish.util.VanishManager")
abstract class VanishShowMixin {
    @Inject(method = "setVanished", at = @At("RETURN"), require = 0)
    private static void tweaks$show(NameAndId profile, MinecraftServer server, boolean vanish, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayer player = server.getPlayerList().getPlayer(profile.id());
        if (cir.getReturnValueZ() && player != null) {
            player.level().getChunkSource().chunkMap.move(player);
        }
    }
}
