package community.propwash.tweaks.mixin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import community.propwash.tweaks.SecretMaps;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Nothing about courses on secret maps (SecretMaps) goes to the Discord bot: the Propwash bridge's
 * records, race results and leaderboards carry their course (with its dimension) and are dropped here, and
 * the course list it syncs leaves them out. By name, so tweaks builds without the bridge; only applied
 * where it's loaded (TweaksMixinPlugin). require = 0: if a bridge update renames enqueue, the server
 * still starts (and secret maps' times would reach Discord again; check after bridge updates).
 */
@Pseudo
@Mixin(targets = "dev.j4v1.propwashbridge.Sender", remap = false)
abstract class BridgeHiddenMixin {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("tweaks");

    @Inject(method = "enqueue(Ljava/lang/String;Lcom/google/gson/JsonObject;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void tweaks$hideSecretMaps(String type, JsonObject payload, CallbackInfo ci) {
        if (payload == null) {
            return;
        }
        JsonElement course = payload.get("course");
        if (course != null && course.isJsonObject()) {
            JsonElement dimension = course.getAsJsonObject().get("dimension");
            if (dimension != null && dimension.isJsonPrimitive() && SecretMaps.hidden(dimension.getAsString())) {
                LOG.info("Not sent to Discord ({}): a secret map's course", type);
                ci.cancel();
                return;
            }
        }
        JsonElement ids = payload.get("courseIds");
        if (ids != null && ids.isJsonArray()) {
            Set<String> secret = SecretMaps.courseIds();
            if (!secret.isEmpty()) {
                JsonArray kept = new JsonArray();
                for (JsonElement id : ids.getAsJsonArray()) {
                    if (!(id.isJsonPrimitive() && secret.contains(id.getAsString()))) {
                        kept.add(id);
                    }
                }
                payload.add("courseIds", kept);
            }
        }
    }
}
