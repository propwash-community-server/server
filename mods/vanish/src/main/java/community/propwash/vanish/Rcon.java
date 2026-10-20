package community.propwash.vanish;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** One console command on a backend over rcon (on 127.0.0.1, like the servers). */
final class Rcon {
    private static final int LOGIN = 3;
    private static final int COMMAND = 2;
    private static final int TIMEOUT_MS = 5000;

    private Rcon() {
    }

    static String run(int port, String password, String command) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            send(out, 1, LOGIN, password);
            if (receive(in).id == -1) {
                throw new IOException("rcon login refused on port " + port);
            }
            send(out, 2, COMMAND, command);
            return receive(in).body;
        }
    }

    private static void send(OutputStream out, int id, int type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer packet = ByteBuffer.allocate(14 + bytes.length).order(ByteOrder.LITTLE_ENDIAN);
        packet.putInt(10 + bytes.length).putInt(id).putInt(type).put(bytes).put((byte) 0).put((byte) 0);
        out.write(packet.array());
        out.flush();
    }

    private record Packet(int id, String body) {
    }

    private static Packet receive(DataInputStream in) throws IOException {
        byte[] header = new byte[4];
        in.readFully(header);
        int length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt();
        byte[] rest = new byte[length];
        in.readFully(rest);
        ByteBuffer packet = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN);
        int id = packet.getInt();
        packet.getInt(); // type
        return new Packet(id, new String(rest, 8, Math.max(0, length - 10), StandardCharsets.UTF_8));
    }
}
