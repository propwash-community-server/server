package community.propwash.voicebody;

import java.util.UUID;
import community.propwash.voicebody.VoiceBody.Spot;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EntitySoundPacketEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.packets.EntitySoundPacket;
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket;
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
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
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

    /** /voicemute: while muted, a player who isn't an admin can't be heard by anyone. */
    private void onMicrophone(MicrophonePacketEvent event) {
        VoicechatConnection sender = event.getSenderConnection();
        if (sender == null || !VoiceBody.muted()) {
            return;
        }
        UUID id = sender.getPlayer().getUuid();
        if (!VoiceBody.isAdmin(id)) {
            event.cancel();
            return;
        }
        // An admin while muted is heard by everyone: the same audio, as a static (non-positional) sound to each other player.
        event.cancel();
        if (api == null) {
            return;
        }
        StaticSoundPacket packet = event.getPacket().toStaticSoundPacket();
        for (UUID listener : VoiceBody.spots().keySet()) {
            if (listener.equals(id)) {
                continue;
            }
            VoicechatConnection connection = api.getConnectionOf(listener);
            if (connection != null) {
                api.sendStaticSoundPacketTo(connection, packet);
            }
        }
    }
}
