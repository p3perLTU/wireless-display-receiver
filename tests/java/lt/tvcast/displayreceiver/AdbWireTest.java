package lt.tvcast.displayreceiver;

import static lt.tvcast.testing.Assert.*;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/** The ADB message header and the key encoding the app uses to sign in to the TV's own adb daemon. */
final class AdbWireTest {
    static KeyPair key() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    static void testHeaderLayout() {
        byte[] h = AdbWire.header(AdbWire.A_CNXN, AdbWire.VERSION, 256 * 1024, 20);
        assertEquals(24, h.length, "header size");
        // command "CNXN", version 0x01000001, max data 0x40000, data length 20, no checksum, magic = ~command
        assertArrayEquals(unhex("434e584e 01000001 00000400 14000000 00000000 bcb1a7b1"), h,
                "CNXN header (little-endian)");
    }

    static void testHeaderRoundTrip() throws Exception {
        int[] p = AdbWire.parseHeader(AdbWire.header(AdbWire.A_WRTE, 7, 9, 1234));
        assertEquals(AdbWire.A_WRTE, p[0], "command");
        assertEquals(7, p[1], "arg0");
        assertEquals(9, p[2], "arg1");
        assertEquals(1234, p[3], "data length");
    }

    static void testWrongMagicIsRejected() {
        byte[] h = AdbWire.header(AdbWire.A_OKAY, 1, 2, 0);
        h[23] ^= 0x55;
        try {
            AdbWire.parseHeader(h);
            throw new AssertionError("a header with a wrong magic was accepted");
        } catch (IOException expected) { }
    }

    static void testDataLengthOutOfRangeIsRejected() {
        for (int len : new int[] { -1, AdbWire.MAX_DATA + 1, Integer.MAX_VALUE, Integer.MIN_VALUE }) {
            try {
                AdbWire.parseHeader(AdbWire.header(AdbWire.A_WRTE, 1, 2, len));
                throw new AssertionError("length " + len + " was accepted");
            } catch (IOException expected) { }
        }
    }

    static void testShortHeaderIsRejected() {
        try {
            AdbWire.parseHeader(new byte[23]);
            throw new AssertionError("23 bytes were accepted as a header");
        } catch (IOException expected) { }
    }

    static void testTokenToSignIsDigestInfoPlusToken() throws Exception {
        byte[] token = new byte[20];
        for (int i = 0; i < 20; i++) token[i] = (byte) (i + 1);
        byte[] r = AdbWire.tokenToSign(token);
        assertEquals(35, r.length, "length");
        assertArrayEquals(AdbWire.SHA1_PREFIX, java.util.Arrays.copyOfRange(r, 0, 15), "SHA-1 DigestInfo prefix");
        assertArrayEquals(token, java.util.Arrays.copyOfRange(r, 15, 35), "token");
    }

    static void testTokenOfAnotherLengthIsRejected() {
        for (int n : new int[] { 0, 19, 21, 64 }) {
            try {
                AdbWire.tokenToSign(new byte[n]);
                throw new AssertionError("a token of " + n + " bytes was signed");
            } catch (IOException expected) { }
        }
    }

    /** The structure adb expects, checked by reading it back and by the maths the daemon uses it for. */
    static void testPublicKeyLineHasTheStructureAdbExpects() throws Exception {
        KeyPair kp = key();
        RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
        byte[] line = AdbWire.publicKeyLine(pub.getModulus(), pub.getPublicExponent(), "displayreceiver@tv");
        String text = new String(line, "UTF-8");
        assertTrue(text.endsWith(" displayreceiver@tv\0"), "ends with the name and a zero byte");
        byte[] raw = Base64.getDecoder().decode(text.substring(0, text.indexOf(' ')));
        assertEquals(4 + 4 + 256 + 256 + 4, raw.length, "structure size");
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(64, b.getInt(), "number of 32-bit words");
        long n0inv = b.getInt() & 0xFFFFFFFFL;
        BigInteger n = BigInteger.ZERO, rr = BigInteger.ZERO;
        for (int i = 0; i < 64; i++) n = n.or(BigInteger.valueOf(b.getInt() & 0xFFFFFFFFL).shiftLeft(32 * i));
        for (int i = 0; i < 64; i++) rr = rr.or(BigInteger.valueOf(b.getInt() & 0xFFFFFFFFL).shiftLeft(32 * i));
        assertEquals(65537, b.getInt(), "exponent");
        assertEquals(pub.getModulus(), n, "modulus");
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(n), rr, "R^2 mod n");
        // n0inv is -1/n mod 2^32, so n0inv * n[0] = -1 mod 2^32
        long n0 = n.longValue() & 0xFFFFFFFFL;
        assertEquals(0xFFFFFFFFL, (n0inv * n0) & 0xFFFFFFFFL, "n0inv * n0 = -1 mod 2^32");
    }

    static void testOnly2048BitKeysAreEncoded() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(1024);
        RSAPublicKey pub = (RSAPublicKey) g.generateKeyPair().getPublic();
        try {
            AdbWire.publicKeyLine(pub.getModulus(), pub.getPublicExponent(), "x");
            throw new AssertionError("a 1024-bit key was encoded");
        } catch (IllegalArgumentException expected) { }
    }

    /** What the app sends back for a token must verify as a plain PKCS#1 signature of the DigestInfo. */
    static void testSignatureOfATokenVerifies() throws Exception {
        KeyPair kp = key();
        byte[] token = new byte[20];
        new java.util.Random(5).nextBytes(token);
        Signature sig = Signature.getInstance("NONEwithRSA");
        sig.initSign(kp.getPrivate());
        sig.update(AdbWire.tokenToSign(token));
        byte[] signature = sig.sign();
        assertEquals(256, signature.length, "signature length");

        Signature check = Signature.getInstance("NONEwithRSA");
        check.initVerify(kp.getPublic());
        check.update(AdbWire.tokenToSign(token));
        assertTrue(check.verify(signature), "the signature verifies");

        byte[] other = token.clone();
        other[0] ^= 1;
        Signature wrong = Signature.getInstance("NONEwithRSA");
        wrong.initVerify(kp.getPublic());
        wrong.update(AdbWire.tokenToSign(other));
        assertFalse(wrong.verify(signature), "a signature does not verify for another token");
    }

    static void testPrivateKeyAndPublicKeyLineAgree() throws Exception {
        KeyPair kp = key();
        RSAPrivateCrtKey priv = (RSAPrivateCrtKey) kp.getPrivate();
        byte[] a = AdbWire.publicKeyLine(priv.getModulus(), priv.getPublicExponent(), "n");
        RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
        byte[] b = AdbWire.publicKeyLine(pub.getModulus(), pub.getPublicExponent(), "n");
        assertArrayEquals(a, b, "the line made from the private key equals the one from the public key");
    }
}
