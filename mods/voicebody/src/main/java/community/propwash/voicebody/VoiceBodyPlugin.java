package community.propwash.voicebody;

import community.propwash.voicebody.VoiceBody.Spot;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EntitySoundPacketEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.packets.EntitySoundPacket;
import net.minecraft.world.phys.Vec3;

/**
 * The client plays voices around its camera, which in FPV is on the drone, so a pilot didn't hear the
 * players next to their body. Simple Voice Chat still picks who hears whom by bodies; for a pilot in
 * FPV we only move each voice by the body-to-camera offset, so it sounds where it would with the
 * camera on the body: same distance, same range. Group voices aren't positional and stay as they are.
 */
public final class VoiceBodyPlugin implements VoicechatPlugin {
    private VoicechatServerApi api;

    @Override
    public String getPluginId() {
        return "voicebody";
    }

    @Override
    public void initialize(VoicechatApi api) {
        if (api instanceof VoicechatServerApi server) {
            this.api = server;
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(EntitySoundPacketEvent.class, this::onEntitySound);
    }

    private void onEntitySound(EntitySoundPacketEvent event) {
        VoicechatConnection receiver = event.getReceiverConnection();
        if (api == null || !VoiceBody.enabled() || receiver == null) {
            return;
        }
        var spots = VoiceBody.spots();
        Spot listener = spots.get(receiver.getPlayer().getUuid());
        EntitySoundPacket packet = event.getPacket();
        Spot speaker = spots.get(packet.getEntityUuid());
        if (listener == null || listener.camera() == null || speaker == null || speaker.level() != listener.level()) {
            return;
        }
        Vec3 at = speaker.body().subtract(listener.body()).add(listener.camera());
        event.cancel();
        api.sendLocationalSoundPacketTo(receiver, packet.locationalSoundPacketBuilder()
                .position(api.createPosition(at.x, at.y, at.z))
                .distance(packet.getDistance())
                .build());
    }
}
