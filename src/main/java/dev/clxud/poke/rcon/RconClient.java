package dev.clxud.poke.rcon;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Minimal Source RCON protocol client.
 *
 * <p>Keeps a single persistent, authenticated connection and reconnects on
 * failure. All command execution is funnelled through a synchronized method so
 * it is safe to call from Clanker's background worker thread — which is exactly
 * the point: dispatching commands over RCON lets us run them off the server's
 * main thread without blocking it.
 */
public final class RconClient {

    private static final int TYPE_AUTH = 3;
    private static final int TYPE_AUTH_RESPONSE = 2;
    private static final int TYPE_EXEC = 2;
    private static final int AUTH_FAILED_ID = -1;

    private final String host;
    private final int port;
    private final String password;

    private Socket socket;
    private OutputStream out;
    private InputStream in;
    private int requestId = 0;

    public RconClient(String host, int port, String password) {
        this.host = host;
        this.port = port;
        this.password = password;
    }

    /** Executes a command and returns the server's textual response. */
    public synchronized String exec(String command) throws IOException {
        try {
            ensureConnected();
            return execOnce(command);
        } catch (IOException first) {
            // One transparent reconnect-and-retry; servers drop idle RCON sockets.
            close();
            ensureConnected();
            return execOnce(command);
        }
    }

    private String execOnce(String command) throws IOException {
        int id = ++requestId;
        sendPacket(id, TYPE_EXEC, command);
        Packet response = readPacket();
        return response.body;
    }

    private void ensureConnected() throws IOException {
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            return;
        }
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 10_000);
        s.setSoTimeout(15_000);
        this.socket = s;
        this.out = s.getOutputStream();
        this.in = s.getInputStream();

        int id = ++requestId;
        sendPacket(id, TYPE_AUTH, password);
        Packet authResponse = readPacket();
        if (authResponse.id == AUTH_FAILED_ID) {
            close();
            throw new IOException("RCON authentication failed — check rcon.password in config.yml / server.properties");
        }
        if (authResponse.type != TYPE_AUTH_RESPONSE) {
            // Some servers send an empty SERVERDATA_RESPONSE_VALUE first; read again.
            Packet retry = readPacket();
            if (retry.id == AUTH_FAILED_ID) {
                close();
                throw new IOException("RCON authentication failed");
            }
        }
    }

    private void sendPacket(int id, int type, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        int length = 4 /*id*/ + 4 /*type*/ + payload.length + 2 /*two null bytes*/;

        ByteArrayOutputStream buffer = new ByteArrayOutputStream(length + 4);
        DataOutputStream d = new DataOutputStream(buffer);
        writeIntLE(d, length);
        writeIntLE(d, id);
        writeIntLE(d, type);
        d.write(payload);
        d.write(0);
        d.write(0);
        d.flush();

        out.write(buffer.toByteArray());
        out.flush();
    }

    private Packet readPacket() throws IOException {
        int length = readIntLE();
        if (length < 10) {
            throw new IOException("Malformed RCON packet (length " + length + ")");
        }
        int id = readIntLE();
        int type = readIntLE();
        int bodyLen = length - 4 - 4 - 2;
        byte[] body = readFully(bodyLen);
        readFully(2); // two trailing null bytes
        return new Packet(id, type, new String(body, StandardCharsets.UTF_8));
    }

    private byte[] readFully(int n) throws IOException {
        byte[] data = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(data, read, n - read);
            if (r < 0) {
                throw new IOException("RCON connection closed mid-packet");
            }
            read += r;
        }
        return data;
    }

    private static void writeIntLE(DataOutputStream d, int value) throws IOException {
        d.write(value & 0xFF);
        d.write((value >>> 8) & 0xFF);
        d.write((value >>> 16) & 0xFF);
        d.write((value >>> 24) & 0xFF);
    }

    private int readIntLE() throws IOException {
        int a = in.read();
        int b = in.read();
        int c = in.read();
        int e = in.read();
        if ((a | b | c | e) < 0) {
            throw new IOException("RCON connection closed");
        }
        return a | (b << 8) | (c << 16) | (e << 24);
    }

    public synchronized void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // best effort
        } finally {
            socket = null;
            out = null;
            in = null;
        }
    }

    private record Packet(int id, int type, String body) {
    }
}
