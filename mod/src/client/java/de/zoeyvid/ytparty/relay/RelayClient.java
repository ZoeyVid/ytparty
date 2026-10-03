package de.zoeyvid.ytparty.relay;

import de.zoeyvid.ytparty.ClientConfig;
import de.zoeyvid.ytparty.PlayerController;
import de.zoeyvid.ytparty.net.ClientSync;
import de.zoeyvid.ytparty.net.SyncProtocol;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.BlockingQueue;

public final class RelayClient {
    public enum Status { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    private static final byte[] POISON = new byte[0];

    public static final RelayClient INSTANCE = new RelayClient();

    public static String host = "";
    public static String port = "25599";
    public static String password = "";
    public static String token = "";
    public static boolean rememberPassword = true;
    public static boolean autoConnect = false;

    private volatile Status status = Status.DISCONNECTED;
    private volatile String message = "";
    private final AtomicInteger generation = new AtomicInteger();
    private volatile BlockingQueue<byte[]> sendQueue;
    private Socket socket;

    private RelayClient() {}

    public Status status() { return status; }
    public String message() { return message; }
    public boolean connected() { return status == Status.CONNECTED; }

    public void connect(String h, int p, String pass) {
        if (status == Status.CONNECTING || status == Status.CONNECTED) return;
        byte[] key = RelayCrypto.parseKey(pass);
        if (key == null) { fail("Password: invalid key"); return; }
        host = h; port = String.valueOf(p); password = pass;
        ClientConfig.save();
        status = Status.CONNECTING;
        message = "connecting\u2026";
        int gen = generation.incrementAndGet();
        Thread t = new Thread(() -> run(gen, h, p, key), "ytparty-relay-reader");
        t.setDaemon(true);
        t.start();
    }

    public void send(byte[] data) {
        BlockingQueue<byte[]> q = sendQueue;
        if (q == null) return;
        for (byte[] part : SyncProtocol.parts(data, RelayCrypto.MAX_DATA)) if (!q.offer(part)) { cleanup("send buffer full"); return; }
    }

    public void disconnect() { cleanup("disconnected"); }

    private void run(int gen, String h, int p, byte[] key) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(h, p), 8000);
            s.setKeepAlive(true);
            s.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream os = s.getOutputStream();

            KeyPair xc = RelayCrypto.genX25519();
            KeyPair mk = RelayCrypto.genMlKem();
            byte[] cn = new byte[16];
            RelayCrypto.RNG.nextBytes(cn);
            ByteArrayOutputStream m1 = new ByteArrayOutputStream();
            m1.write(cn);
            m1.write(RelayCrypto.x25519Pub(xc));
            m1.write(RelayCrypto.mlkemEk(mk));
            byte[] msg1 = m1.toByteArray();
            writeFrame(os, msg1);

            byte[] msg2 = readFrame(in, 16 + 32 + 1088 + 16 + 16);
            if (msg2.length != 16 + 32 + 1088 + 16 + 16) { fail("Relay: invalid handshake"); s.close(); return; }
            byte[] core = Arrays.copyOf(msg2, 16 + 32 + 1088);
            byte[] ee = RelayCrypto.x25519Agree(xc.getPrivate(), Arrays.copyOfRange(msg2, 16, 48));
            byte[] ssm = RelayCrypto.mlkemDecapsulate(mk.getPrivate(), Arrays.copyOfRange(msg2, 48, core.length));
            byte[] access = RelayCrypto.hmac(key, "ytparty-access-v3".getBytes(StandardCharsets.UTF_8), msg1, core, ee, ssm);
            byte[] session = RelayCrypto.hmac(access, "ytparty-sk-v3".getBytes(StandardCharsets.UTF_8), RelayCrypto.x25519Agree(xc.getPrivate(), key));
            try { RelayCrypto.decrypt(access, RelayCrypto.nonce(1, 0), Arrays.copyOfRange(msg2, core.length, core.length + 16)); }
            catch (GeneralSecurityException e) { fail("Relay: wrong password"); s.close(); return; }
            try { RelayCrypto.decrypt(session, RelayCrypto.nonce(1, 0), Arrays.copyOfRange(msg2, core.length + 16, msg2.length)); }
            catch (GeneralSecurityException e) { fail("Relay: not the real relay"); s.close(); return; }

            ByteArrayOutputStream idb = new ByteArrayOutputStream();
            DataOutputStream id = new DataOutputStream(idb);
            writeBlob(id, Minecraft.getInstance().getUser().getName().getBytes(StandardCharsets.UTF_8));
            writeBlob(id, Minecraft.getInstance().getUser().getProfileId().toString().getBytes(StandardCharsets.UTF_8));
            writeBlob(id, token.getBytes(StandardCharsets.UTF_8));
            writeFrame(os, RelayCrypto.encrypt(session, RelayCrypto.nonce(0, 0), RelayCrypto.pad(idb.toByteArray())));

            byte[] ack;
            try { ack = RelayCrypto.unpad(RelayCrypto.decrypt(session, RelayCrypto.nonce(1, 1), readFrame(in, 256 + 16))); }
            catch (GeneralSecurityException e) { fail("Relay: invalid handshake"); s.close(); return; }
            DataInputStream ackIn = new DataInputStream(new java.io.ByteArrayInputStream(ack));
            if (ackIn.read() != 1) { fail("Relay: rejected"); s.close(); return; }
            int tlen = ackIn.readShort() & 0xffff;
            byte[] tb = new byte[tlen];
            ackIn.readFully(tb);
            token = new String(tb, StandardCharsets.UTF_8);

            if (gen != generation.get()) { s.close(); return; }
            BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(1024);
            synchronized (this) { socket = s; sendQueue = queue; }
            status = Status.CONNECTED;
            message = "connected to " + h + ":" + p;
            Thread writer = new Thread(() -> writerLoop(gen, queue, os, session), "ytparty-relay-writer");
            writer.setDaemon(true);
            writer.start();
            Minecraft.getInstance().execute(() -> { PlayerController.INSTANCE.onPartyLeft(); PlayerController.INSTANCE.setBackend(this::send); });

            long recvCtr = 2;
            while (gen == generation.get()) {
                byte[] frame = readFrame(in, RelayCrypto.MAX_FRAME);
                byte[] payload;
                try { payload = RelayCrypto.unpad(RelayCrypto.decrypt(session, RelayCrypto.nonce(1, recvCtr), frame)); }
                catch (GeneralSecurityException e) { break; }
                recvCtr++;
                Minecraft.getInstance().execute(() -> ClientSync.dispatch(payload));
            }
            cleanup("disconnected");
        } catch (IOException | GeneralSecurityException e) {
            try { s.close(); } catch (IOException ignored) {}
            if (status == Status.CONNECTING) fail("Relay: connection failed");
            else cleanup("disconnected");
        }
    }

    private void writerLoop(int gen, BlockingQueue<byte[]> queue, OutputStream os, byte[] session) {
        long sendCtr = 1;
        try {
            while (gen == generation.get()) {
                byte[] data = queue.take();
                if (data.length == 0) return;
                writeFrame(os, RelayCrypto.encrypt(session, RelayCrypto.nonce(0, sendCtr), RelayCrypto.pad(data)));
                sendCtr++;
            }
        } catch (InterruptedException ignored) {
        } catch (IOException | GeneralSecurityException e) { cleanup("send failed"); }
    }

    private void fail(String msg) { status = Status.FAILED; message = msg; }

    private synchronized void cleanup(String msg) {
        if (status == Status.DISCONNECTED) return;
        generation.incrementAndGet();
        status = Status.DISCONNECTED;
        message = msg;
        BlockingQueue<byte[]> q = sendQueue;
        if (q != null) q.offer(POISON);
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        socket = null; sendQueue = null;
        Minecraft.getInstance().execute(() -> { PlayerController.INSTANCE.setBackend(ClientSync.serverSink()); PlayerController.INSTANCE.onPartyLeft(); });
    }

    private static void writeBlob(DataOutputStream d, byte[] b) throws IOException { d.writeShort(b.length); d.write(b); }

    private static void writeFrame(OutputStream os, byte[] data) throws IOException {
        os.write(new byte[]{(byte) (data.length >>> 24), (byte) (data.length >>> 16), (byte) (data.length >>> 8), (byte) data.length});
        os.write(data);
        os.flush();
    }

    private static byte[] readFrame(DataInputStream in, int max) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > max) throw new IOException("bad frame");
        byte[] b = new byte[len];
        in.readFully(b);
        return b;
    }
}
