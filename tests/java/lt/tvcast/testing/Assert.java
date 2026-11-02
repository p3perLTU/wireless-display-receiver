package lt.tvcast.testing;

import java.util.Arrays;

/** The few assertions the tests need (JUnit-style names). */
public final class Assert {
    private Assert() {}

    public static void assertTrue(boolean v, String what) {
        if (!v) throw new AssertionError(what);
    }

    public static void assertFalse(boolean v, String what) {
        if (v) throw new AssertionError(what);
    }

    public static void assertEquals(long expected, long actual, String what) {
        if (expected != actual) throw new AssertionError(what + ": expected " + expected + ", got " + actual);
    }

    public static void assertEquals(double expected, double actual, double delta, String what) {
        if (Math.abs(expected - actual) > delta) throw new AssertionError(what + ": expected " + expected + ", got " + actual);
    }

    public static void assertEquals(Object expected, Object actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + ", got " + actual);
        }
    }

    public static void assertArrayEquals(byte[] expected, byte[] actual, String what) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(what + ": expected " + hex(expected) + ", got " + hex(actual));
        }
    }

    public static void assertNull(Object v, String what) {
        if (v != null) throw new AssertionError(what + ": expected null, got " + v);
    }

    public static void assertNotNull(Object v, String what) {
        if (v == null) throw new AssertionError(what + ": expected a value, got null");
    }

    public static String hex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length && i < 64; i++) sb.append(String.format("%02x", b[i] & 0xFF));
        if (b.length > 64) sb.append("... (").append(b.length).append(" bytes)");
        return sb.toString();
    }

    public static byte[] unhex(String s) {
        s = s.replace(" ", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
}
