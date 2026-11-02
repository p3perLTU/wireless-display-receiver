package lt.tvcast.displayreceiver;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * A small ADB client that talks to this TV's own adb daemon (on the loopback address, port 5555), so
 * the app can run a few shell commands that an ordinary app is not allowed to run. It needs USB
 * debugging to be on; the first time the TV asks "Allow USB debugging?" (tick "Always allow"). The
 * key it signs in with stays in the app's private storage and is deleted when 5 GHz mode is switched
 * off. The wire format is in {@link AdbWire}: the TV answers AUTH with a token that must be signed
 * (or an unknown key must be offered); OPEN starts a "shell:" stream and the TV answers with WRTE
 * messages (acknowledged with OKAY) until CLSE.
 */
final class SelfAdb implements Closeable {
    /** Shell output longer than this is cut: the commands used here print a line or two. */
    private static final int MAX_OUTPUT = 64 * 1024;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private int nextLocalId = 1;

    private SelfAdb(Socket socket) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    /**
     * Connects and signs in. {@code authWaitMs} is how long to wait for somebody to press "Allow" on
     * the TV the first time. Throws a ConnectException when USB debugging is off and a
     * SocketTimeoutException when nobody pressed "Allow".
     */
    static SelfAdb connect(File keyFile, int authWaitMs) throws Exception {
        PrivateKey priv = loadOrCreateKey(keyFile);
        Socket s = new Socket();
        s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), 5555), 3000);
        s.setSoTimeout(8000);
        SelfAdb adb = new SelfAdb(s);
        try {
            adb.send(AdbWire.A_CNXN, AdbWire.VERSION, 256 * 1024, "host::displayreceiver\0".getBytes("UTF-8"));
            boolean signed = false;
            for (int rounds = 0; rounds < 8; rounds++) {
                Msg m = adb.read();
                if (m.cmd == AdbWire.A_CNXN) {
                    s.setSoTimeout(15000);
                    return adb;
                }
                if (m.cmd != AdbWire.A_AUTH || m.arg0 != AdbWire.AUTH_TOKEN) continue;
                if (!signed) {
                    signed = true;
                    Signature sig = Signature.getInstance("NONEwithRSA");
                    sig.initSign(priv);
                    sig.update(AdbWire.tokenToSign(m.data));
                    adb.send(AdbWire.A_AUTH, AdbWire.AUTH_SIGNATURE, 0, sig.sign());
                } else {
                    // the TV does not know the key: offer it, then wait for the "Allow" button
                    RSAPrivateCrtKey k = (RSAPrivateCrtKey) priv;
                    adb.send(AdbWire.A_AUTH, AdbWire.AUTH_RSAPUBLICKEY, 0,
                            AdbWire.publicKeyLine(k.getModulus(), k.getPublicExponent(), "displayreceiver@tv"));
                    s.setSoTimeout(authWaitMs);
                }
            }
            throw new IOException("adb sign-in did not finish");
        } catch (Exception e) {
            adb.close();
            throw e;
        }
    }

    /** Runs a shell command and returns what it printed (cut at 64 kB). */
    synchronized String shell(String command) throws IOException {
        int local = nextLocalId++;
        send(AdbWire.A_OPEN, local, 0, ("shell:" + command + "\0").getBytes("UTF-8"));
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        while (true) {
            Msg m = read();
            if (m.arg1 != local) continue;
            if (m.cmd == AdbWire.A_WRTE) {
                if (text.size() < MAX_OUTPUT) text.write(m.data, 0, Math.min(m.data.length, MAX_OUTPUT - text.size()));
                send(AdbWire.A_OKAY, local, m.arg0, new byte[0]);
            } else if (m.cmd == AdbWire.A_CLSE) {
                return text.toString("UTF-8").trim();
            }
        }
    }

    @Override public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }

    /** Forgets the key: without it this app can no longer sign in, even if the TV still lists it. */
    static void deleteKey(File keyFile) {
        if (keyFile.exists() && !keyFile.delete()) keyFile.deleteOnExit();
    }

    // ---- wire ----

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        out.write(AdbWire.header(cmd, arg0, arg1, data.length));
        out.write(data);
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] h = new byte[24];
        readFully(h, 24);
        int[] p = AdbWire.parseHeader(h);
        Msg m = new Msg();
        m.cmd = p[0];
        m.arg0 = p[1];
        m.arg1 = p[2];
        m.data = new byte[p[3]];
        readFully(m.data, p[3]);
        return m;
    }

    private void readFully(byte[] b, int len) throws IOException {
        int got = 0;
        while (got < len) {
            int n = in.read(b, got, len - got);
            if (n < 0) throw new IOException("adb connection closed");
            got += n;
        }
    }

    // ---- key ----

    private static PrivateKey loadOrCreateKey(File f) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (f.exists()) {
            byte[] enc = new byte[(int) f.length()];
            FileInputStream fin = new FileInputStream(f);
            try { new DataInputStream(fin).readFully(enc); } finally { fin.close(); }
            return kf.generatePrivate(new PKCS8EncodedKeySpec(enc));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(kp.getPrivate().getEncoded()); } finally { o.close(); }
        f.setReadable(false, false);
        f.setReadable(true, true);
        f.setWritable(false, false);
        f.setWritable(true, true);
        return kp.getPrivate();
    }
}
