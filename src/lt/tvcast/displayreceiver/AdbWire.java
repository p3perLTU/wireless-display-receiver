package lt.tvcast.displayreceiver;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

/**
 * The ADB wire format and key encoding, with no Android classes so it can be tested on its own.
 * Every message is a 24-byte little-endian header (command, arg0, arg1, data length, checksum,
 * command xor 0xffffffff) and the data.
 */
final class AdbWire {
    private AdbWire() { }

    static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f,
            A_OKAY = 0x59414b4f, A_CLSE = 0x45534c43, A_WRTE = 0x45545257;
    static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    /** From this version on the data checksum is not checked. */
    static final int VERSION = 0x01000001;
    /** The biggest message data accepted from the daemon. */
    static final int MAX_DATA = 1 << 20;
    /** DigestInfo of SHA-1: the token is signed as if it were already a SHA-1 digest. */
    static final byte[] SHA1_PREFIX = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14 };

    static byte[] header(int cmd, int arg0, int arg1, int dataLength) {
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(dataLength).putInt(0).putInt(cmd ^ 0xffffffff);
        return h.array();
    }

    /** {command, arg0, arg1, data length} of a header; rejects a wrong magic or a data length out of range. */
    static int[] parseHeader(byte[] h) throws IOException {
        if (h.length != 24) throw new IOException("adb header must be 24 bytes");
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        int cmd = b.getInt(), a0 = b.getInt(), a1 = b.getInt(), len = b.getInt();
        b.getInt();
        int magic = b.getInt();
        if (magic != (cmd ^ 0xffffffff)) throw new IOException("bad adb message magic");
        if (len < 0 || len > MAX_DATA) throw new IOException("bad adb message length " + len);
        return new int[] { cmd, a0, a1, len };
    }

    /** What is signed for an AUTH token: the SHA-1 DigestInfo followed by the 20-byte token. */
    static byte[] tokenToSign(byte[] token) throws IOException {
        if (token.length != 20) throw new IOException("adb token is " + token.length + " bytes, not 20");
        byte[] r = new byte[SHA1_PREFIX.length + 20];
        System.arraycopy(SHA1_PREFIX, 0, r, 0, SHA1_PREFIX.length);
        System.arraycopy(token, 0, r, SHA1_PREFIX.length, 20);
        return r;
    }

    /**
     * The public key the way adb wants it: base64 of {number of 32-bit words, -1/n mod 2^32, n, R^2 mod n,
     * exponent}, all little-endian, then a space, a name and a zero byte. Only 2048-bit keys.
     */
    static byte[] publicKeyLine(BigInteger n, BigInteger exponent, String name) {
        if (n.bitLength() != 2048) throw new IllegalArgumentException("only 2048-bit keys");
        BigInteger two32 = BigInteger.ONE.shiftLeft(32);
        BigInteger word = two32.subtract(BigInteger.ONE);
        BigInteger n0inv = two32.subtract(n.mod(two32).modInverse(two32));
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64).putInt(n0inv.intValue());
        for (int i = 0; i < 64; i++) b.putInt(n.shiftRight(32 * i).and(word).intValue());
        for (int i = 0; i < 64; i++) b.putInt(rr.shiftRight(32 * i).and(word).intValue());
        b.putInt(exponent.intValue());
        String line = Base64.getEncoder().encodeToString(b.array()) + " " + name + "\0";
        return line.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
