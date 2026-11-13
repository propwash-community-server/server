package community.propwash.tweaks;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walk-in portals to other servers (the hub's, which Advanced Portals did on Paper): config/tweaks.properties
 * lines "portal.<name> = <dimension> <x1> <y1> <z1> <x2> <y2> <z2> <server>" (blocks, both corners included).
 * A player inside one is sent to that server through the proxy (the BungeeCord "Connect" message, which
 * Velocity takes with bungee-plugin-message-channel on), at most every 2 seconds. Nether portal blocks
 * inside one don't take anyone to the nether (mixin.NetherPortalMixin), so they can make the portal's look.
 *
 * Lines "command.<name> = <server>" add a command /<name> that sends the player to that server the same way
 * (the hub's /race, /freestyle, /plots), for everyone, at most every 2 seconds. It takes the place of a
 * command of that name from this mod (Race's /race).
 */
public final class Portals {
    private static final Logger LOG = LoggerFactory.getLogger("tweaks");
    private static final long COOLDOWN_TICKS = 40;

    /** A portal: a box of blocks in a dimension, and the server it sends to. */
    public record Portal(String name, Identifier dimension, BlockPos min, BlockPos max, String server) {
        boolean contains(Level level, BlockPos pos) {
            return level.dimension().identifier().equals(dimension)
                    && pos.getX() >= min.getX() && pos.getX() <= max.getX()
                    && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                    && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
        }
    }

    /** The proxy's plugin message channel; the payload is the message's bytes as they are. */
    private record BungeeMessage(byte[] data) implements CustomPacketPayload {
        static final Type<BungeeMessage> TYPE = new Type<>(Identifier.parse("bungeecord:main"));
        static final StreamCodec<RegistryFriendlyByteBuf, BungeeMessage> CODEC = StreamCodec.of(
                (buf, message) -> buf.writeBytes(message.data()),
                buf -> {
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    return new BungeeMessage(data);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static final List<Portal> PORTALS = new ArrayList<>();
    private static final Map<UUID, Long> lastSent = new HashMap<>();
    /** command.<name> lines: the command and the server it sends to. */
    private static final Map<String, String> COMMANDS = new HashMap<>();

    private Portals() {
    }

    static void init() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("tweaks.properties");
        Properties properties = new Properties();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                properties.load(reader);
            } catch (IOException e) {
                LOG.warn("Can't read {}", file, e);
            }
        }
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("command.")) {
                String name = key.substring(8).strip();
                String server = properties.getProperty(key).strip();
                if (name.isEmpty() || server.isEmpty() || server.contains(" ")) {
                    LOG.warn("Command {} ignored: want 'command.<name> = <server>'", key);
                } else {
                    COMMANDS.put(name, server);
                }
                continue;
            }
            if (!key.startsWith("portal.")) {
                continue;
            }
            String[] p = properties.getProperty(key).strip().split("\\s+");
            try {
                BlockPos a = new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                BlockPos b = new BlockPos(Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6]));
                PORTALS.add(new Portal(key.substring(7), Identifier.parse(p[0]), BlockPos.min(a, b), BlockPos.max(a, b), p[7]));
            } catch (RuntimeException e) {
                LOG.warn("Portal {} ignored: want '<dimension> <x1> <y1> <z1> <x2> <y2> <z2> <server>'", key);
            }
        }
        if (PORTALS.isEmpty() && COMMANDS.isEmpty()) {
            return;
        }
        PayloadTypeRegistry.clientboundPlay().register(BungeeMessage.TYPE, BungeeMessage.CODEC);
        if (!COMMANDS.isEmpty()) {
            LOG.info("Server commands: {}", COMMANDS);
            CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                    COMMANDS.forEach((name, server) -> dispatcher.register(Commands.literal(name)
                            .executes(c -> command(c.getSource(), server)))));
        }
        if (PORTALS.isEmpty()) {
            return;
        }
        LOG.info("Portals: {}", PORTALS.stream().map(p -> p.name() + " -> " + p.server()).toList());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = server.getTickCount();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                Portal portal = at(player.level(), player.blockPosition());
                if (portal == null || now - lastSent.getOrDefault(player.getUUID(), -COOLDOWN_TICKS) < COOLDOWN_TICKS) {
                    continue;
                }
                lastSent.put(player.getUUID(), now);
                connect(player, portal.server());
            }
        });
    }

    /** Whether a command.<name> line makes /<name> a server command here. */
    static boolean hasCommand(String name) {
        return COMMANDS.containsKey(name);
    }

    private static int command(CommandSourceStack source, String server) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        long now = source.getServer().getTickCount();
        if (now - lastSent.getOrDefault(player.getUUID(), -COOLDOWN_TICKS) < COOLDOWN_TICKS) {
            return 0;
        }
        lastSent.put(player.getUUID(), now);
        connect(player, server);
        return 1;
    }

    /** The portal around this position, if any. */
    public static Portal at(Level level, BlockPos pos) {
        for (Portal portal : PORTALS) {
            if (portal.contains(level, pos)) {
                return portal;
            }
        }
        return null;
    }

    private static void connect(ServerPlayer player, String server) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("Connect");
            out.writeUTF(server);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        ServerPlayNetworking.send(player, new BungeeMessage(bytes.toByteArray()));
    }
}
