package community.propwash.tweaks.mixin;

import community.propwash.tweaks.Tweaks;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Using a firework rocket while it's on cooldown (FireworkCooldownMixin) says how long is left, above
 * the hotbar: "You can use this item again in 2 seconds!".
 */
@Mixin(ServerPlayerGameMode.class)
abstract class FireworkCooldownMessageMixin {
    @Inject(method = "useItem", at = @At("HEAD"))
    private void tweaks$cooldownMessage(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
                                        CallbackInfoReturnable<InteractionResult> cir) {
        tweaks$message(player, stack);
    }

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void tweaks$cooldownMessageOnBlock(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
                                               BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        tweaks$message(player, stack);
    }

    private static void tweaks$message(ServerPlayer player, ItemStack stack) {
        if (!stack.is(Items.FIREWORK_ROCKET) || !player.getCooldowns().isOnCooldown(stack)) {
            return;
        }
        float left = player.getCooldowns().getCooldownPercent(stack, 0) * Tweaks.FIREWORK_COOLDOWN_TICKS / 20f;
        int seconds = Math.max(1, (int) Math.ceil(left));
        player.sendOverlayMessage(Component.literal(
                "You can use this item again in " + seconds + (seconds == 1 ? " second!" : " seconds!")).withStyle(ChatFormatting.RED));
    }
}
