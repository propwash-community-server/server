package community.propwash.tweaks.mixin;

import community.propwash.tweaks.RaceRatings;
import de.lardtom.propwash.race.CourseRegistry;
import de.lardtom.propwash.race.RacePayloads;
import de.lardtom.propwash.race.RaceServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every lap Propwash's server accepts (its own checks passed; assisted laps too) counts for the race
 * difficulty ratings (RaceRatings). Only applied when Propwash is loaded (TweaksMixinPlugin).
 */
@Mixin(value = RaceServer.class, remap = false)
abstract class RaceLapMixin {
    @Inject(method = "lap", at = @At("RETURN"))
    private static void tweaks$countLap(CourseRegistry registry, CourseRegistry.Course course, @Coerce Object session,
            RacePayloads.RaceReportC2S report, ServerPlayer player, long now, CallbackInfoReturnable<RaceServer.Verdict> cir) {
        if (cir.getReturnValue() == RaceServer.Verdict.ACCEPTED) {
            RaceRatings.lap(player, course.id);
        }
    }
}
