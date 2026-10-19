package community.propwash.tweaks.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Firework rockets get a 2-second item cooldown (the grey sweep, like ender pearls) after each use,
 * launched from the ground or for elytra boosting, except for players tagged propwash.exempt. The
 * player's game knows about the cooldown, so it doesn't even try: no boost from a rocket the
 * noprojectiles datapack would only remove a tick later. FireworkCooldownMessageMixin says how long is left.
 */
@Mixin(FireworkRocketItem.class)
abstract class FireworkCooldownMixin {
    @Inject(method = "use", at = @At("RETURN"))
    private void tweaks$cooldownAfterBoost(Level level, Player player, InteractionHand hand,
                                           CallbackInfoReturnable<InteractionResult> cir) {
        tweaks$cooldown(player, cir.getReturnValue());
    }

    @Inject(method = "useOn", at = @At("RETURN"))
    private void tweaks$cooldownAfterLaunch(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        tweaks$cooldown(context.getPlayer(), cir.getReturnValue());
    }

    private static void tweaks$cooldown(Player player, InteractionResult result) {
        if (player instanceof ServerPlayer && result.consumesAction() && !player.entityTags().contains("propwash.exempt")) {
            // Not the used stack: it may be empty now (the last rocket). All rockets share this cooldown.
            player.getCooldowns().addCooldown(new ItemStack(Items.FIREWORK_ROCKET), community.propwash.tweaks.Tweaks.FIREWORK_COOLDOWN_TICKS);
        }
    }
}
