package lt.tvcast.displayreceiver;

import static lt.tvcast.testing.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Reading RTSP messages from a device on the network: split, bounded, and never an unexpected exception. */
final class RtspTextTest {
    static List<RtspText.Message> feed(RtspText t, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return t.feed(b, b.length);
    }

    static void expectIoException(String what, String text) {
        try {
            feed(new RtspText(), text);
            throw new AssertionError(what + " was accepted");
        } catch (IOException expected) { }
    }

    static void testOneMessageWithoutBody() throws Exception {
        List<RtspText.Message> m = feed(new RtspText(), "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\n");
        assertEquals(1, m.size(), "one message");
        assertEquals("OPTIONS * RTSP/1.0\r\nCSeq: 1", m.get(0).head, "head");
        assertEquals("", m.get(0).body, "body");
    }

    static void testBodyFollowsContentLength() throws Exception {
        RtspText t = new RtspText();
        assertEquals(0, feed(t, "SET_PARAMETER x RTSP/1.0\r\nCSeq: 3\r\nContent-Length: 5\r\n\r\nab").size(), "body not complete");
        List<RtspText.Message> m = feed(t, "cde");
        assertEquals(1, m.size(), "complete now");
        assertEquals("abcde", m.get(0).body, "body");
    }

    static void testTwoMessagesInOneRead() throws Exception {
        List<RtspText.Message> m = feed(new RtspText(), "RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\nRTSP/1.0 200 OK\r\nCSeq: 2\r\n\r\n");
        assertEquals(2, m.size(), "two messages");
        assertEquals(2, RtspText.headerInt(m.get(1).head, "cseq"), "second CSeq");
    }

    static void testMessageSplitAtEveryByte() throws Exception {
        String text = "GET_PARAMETER x RTSP/1.0\r\nCSeq: 2\r\nContent-Length: 12\r\n\r\nwfd_a\r\nwfd_b";
        RtspText t = new RtspText();
        int found = 0;
        String body = null;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            for (RtspText.Message m : t.feed(new byte[] { b }, 1)) { found++; body = m.body; }
        }
        assertEquals(1, found, "exactly one message however it arrives");
        assertEquals("wfd_a\r\nwfd_b", body, "body");
    }

    static void testNegativeContentLengthIsAnIoException() {
        expectIoException("a negative Content-Length", "SET_PARAMETER x RTSP/1.0\r\nContent-Length: -5\r\n\r\n");
    }

    static void testNonNumericContentLengthIsAnIoException() {
        expectIoException("a Content-Length that is not a number", "SET_PARAMETER x RTSP/1.0\r\nContent-Length: abc\r\n\r\n");
    }

    static void testHugeContentLengthIsAnIoException() {
        expectIoException("a Content-Length of 2 GB", "SET_PARAMETER x RTSP/1.0\r\nContent-Length: 2147483647\r\n\r\n");
        expectIoException("a Content-Length over the limit",
                "SET_PARAMETER x RTSP/1.0\r\nContent-Length: " + (RtspText.MAX_BODY + 1) + "\r\n\r\n");
    }

    static void testHeaderWithoutEndIsBounded() {
        StringBuilder sb = new StringBuilder("OPTIONS * RTSP/1.0\r\n");
        while (sb.length() < RtspText.MAX_HEADER + 100) sb.append("X-Padding: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\r\n");
        expectIoException("an endless header", sb.toString());
    }

    static void testTheLargestAllowedBodyIsAccepted() throws Exception {
        StringBuilder body = new StringBuilder();
        while (body.length() < RtspText.MAX_BODY) body.append('a');
        List<RtspText.Message> m = feed(new RtspText(),
                "SET_PARAMETER x RTSP/1.0\r\nContent-Length: " + RtspText.MAX_BODY + "\r\n\r\n" + body);
        assertEquals(1, m.size(), "the message");
        assertEquals(RtspText.MAX_BODY, m.get(0).body.length(), "body length");
    }

    static void testHeadersAreFoundWhateverTheirCase() {
        String head = "RTSP/1.0 200 OK\r\ncseq: 7\r\nSession: 123;timeout=30\r\nUSER-AGENT: SVDTZ8_0/SEC-UIBC3";
        assertEquals("123;timeout=30", RtspText.headerValue(head, "session"), "Session");
        assertEquals("SVDTZ8_0/SEC-UIBC3", RtspText.headerValue(head, "User-Agent"), "User-Agent");
        assertNull(RtspText.headerValue(head, "transport"), "a header that is not there");
        assertEquals(7, RtspText.headerInt(head, "CSeq"), "CSeq");
    }

    static void testBadNumbersInAHeaderAreZero() {
        assertEquals(0, RtspText.headerInt("RTSP/1.0 200 OK\r\nCSeq: abc", "cseq"), "text");
        assertEquals(0, RtspText.headerInt("RTSP/1.0 200 OK\r\nCSeq: 99999999999", "cseq"), "too large for an int");
        assertEquals(0, RtspText.headerInt("RTSP/1.0 200 OK", "cseq"), "absent");
    }

    static void testBytesThatAreNotTextDoNotThrow() throws Exception {
        byte[] junk = new byte[2000];
        new java.util.Random(1).nextBytes(junk);
        try { new RtspText().feed(junk, junk.length); } catch (IOException ok) { }
        byte[] mixed = "\u0000ÿ\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        try { new RtspText().feed(mixed, mixed.length); } catch (IOException ok) { }
    }
}
