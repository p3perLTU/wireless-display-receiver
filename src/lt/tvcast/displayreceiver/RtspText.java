package lt.tvcast.displayreceiver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits an RTSP byte stream into messages (header, then a body whose length the header gives) and
 * reads header lines. The peer is another device on the network, so every size is bounded and a
 * malformed message is an IOException, never another kind of exception. No Android classes.
 */
final class RtspText {
    static final int MAX_HEADER = 16 * 1024;
    static final int MAX_BODY = 256 * 1024;

    static final class Message {
        final String head;
        final String body;
        Message(String head, String body) { this.head = head; this.body = body; }
    }

    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    /** Adds bytes from the stream and returns the messages that are complete now. */
    List<Message> feed(byte[] data, int length) throws IOException {
        pending.write(data, 0, length);
        byte[] all = pending.toByteArray();
        List<Message> out = new ArrayList<Message>();
        int used = 0;
        while (true) {
            int end = indexOf(all, used, "\r\n\r\n");
            if (end < 0) {
                if (all.length - used > MAX_HEADER) throw new IOException("RTSP header longer than " + MAX_HEADER);
                break;
            }
            if (end - used > MAX_HEADER) throw new IOException("RTSP header longer than " + MAX_HEADER);
            String head = new String(all, used, end - used, StandardCharsets.UTF_8);
            int len = contentLength(head);
            int bodyStart = end + 4;
            if (bodyStart + (long) len > all.length) break;               // body not complete yet
            String body = new String(all, bodyStart, len, StandardCharsets.UTF_8);
            used = bodyStart + len;
            out.add(new Message(head, body));
        }
        pending.reset();
        pending.write(all, used, all.length - used);
        return out;
    }

    /** The Content-Length of a header: 0 when absent, an IOException when it is not a sane number. */
    static int contentLength(String head) throws IOException {
        String v = headerValue(head, "content-length");
        if (v == null) return 0;
        int n;
        try { n = Integer.parseInt(v.trim()); } catch (NumberFormatException e) { throw new IOException("bad Content-Length"); }
        if (n < 0 || n > MAX_BODY) throw new IOException("Content-Length out of range: " + n);
        return n;
    }

    static String headerValue(String head, String name) {
        for (String line : head.split("\r\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase(name)) return line.substring(c + 1).trim();
        }
        return null;
    }

    /** A numeric header such as CSeq: 0 when absent or not a number. */
    static int headerInt(String head, String name) {
        String v = headerValue(head, name);
        if (v == null) return 0;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return 0; }
    }

    private static int indexOf(byte[] b, int from, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = from; i <= b.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) if (b[i + j] != n[j]) continue outer;
            return i;
        }
        return -1;
    }
}
