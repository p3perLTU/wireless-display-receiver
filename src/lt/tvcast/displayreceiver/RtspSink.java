package lt.tvcast.displayreceiver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;

/**
 * The sink side of a Miracast (Wi-Fi Display) session. The source listens on port 7236 and this
 * class talks to it: M1 OPTIONS from the source, our M2 OPTIONS, M3 GET_PARAMETER (we describe what
 * we can show), M4 SET_PARAMETER (the source picks a format), M5 trigger SETUP, then our SETUP and
 * PLAY. Afterwards the source sends MPEG-TS over RTP, by UDP or TCP, to {@link #RTP_PORT}.
 *
 * The other end is a device on the network, so: the RTP sockets are bound to the address of the
 * Wi-Fi Direct link only (not to every network the TV is on) and take packets only from the source
 * that holds the RTSP connection; RTSP messages have size limits; and nothing a packet contains can
 * stop the app, whatever it says.
 */
final class RtspSink {
    interface Log { void note(String message); }

    static final int RTP_PORT = 19000;
    /** How long the source gets to connect to our RTP port after SETUP. */
    private static final int ACCEPT_MS = 15000;

    private final Socket socket;
    private final Log log;
    private final InetAddress source;             // the one device whose RTP packets are accepted
    private final InetAddress local;              // our address on that link: RTP sockets bind to it
    private OutputStream out;
    private int cseq = 100;
    private final Map<Integer, String> pending = new HashMap<Integer, String>();
    private String url = "rtsp://localhost/wfd1.0/streamid=0";

    private DatagramSocket udp, rtcp;
    private ServerSocket tcpServer;
    private volatile Socket rtpSocket;
    private boolean tcp;                          // the source chose RTP over TCP in M4

    // what the RTCP receiver report says about the stream (the Samsung source adapts its bitrate to it)
    private volatile int srcSsrc;
    private volatile long extSeq, totalLost;
    private int lastSeq = -1, cycles;
    private long reportedSeq, reportedLost;

    private final TsPlayer player;

    RtspSink(Socket socket, Log log, TsPlayer player) {
        this.socket = socket;
        this.log = log;
        this.player = player;
        this.source = socket.getInetAddress();
        this.local = socket.getLocalAddress();
        if (player != null) player.setKeyframeRequest(new Runnable() {
            @Override public void run() { requestKeyframe(); }
        });
    }

    void run() {
        try {
            socket.setSoTimeout(60000);
            InputStream in = socket.getInputStream();
            out = socket.getOutputStream();
            RtspText messages = new RtspText();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                for (RtspText.Message m : messages.feed(buf, n)) handle(m.head, m.body);
            }
            log.note("closed by source");
        } catch (IOException e) {
            log.note("session: " + e.getMessage());
        } catch (RuntimeException e) {
            log.note("session ended by a message it could not read: " + e);
        } finally {
            closeUdp();
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private void handle(String head, String body) throws IOException {
        String first = head.split("\r\n")[0];
        log.note("<< " + first + (body.isEmpty() ? "" : " [" + body.replace("\r\n", " ; ").trim() + "]"));
        int seq = RtspText.headerInt(head, "cseq");
        if (first.startsWith("RTSP/")) {
            onReply(seq, first, head);
            return;
        }
        String method = first.split(" ")[0];
        if (method.equals("OPTIONS")) {
            // A Samsung phone judges the receiver by this header (a Samsung TV sends it too) and only
            // then asks for, and uses, the faster Samsung modes.
            reply(seq, "Public: OPTIONS, GET_PARAMETER, PAUSE, PLAY, SETUP, SET_PARAMETER, TEARDOWN, org.wfa.wfd1.0\r\n"
                    + "User-Agent: " + WfdCaps.USER_AGENT + "\r\n", null);
            request("OPTIONS", "*", "Require: org.wfa.wfd1.0\r\nUser-Agent: " + WfdCaps.USER_AGENT + "\r\n");
        } else if (method.equals("GET_PARAMETER")) {
            reply(seq, "", body.trim().isEmpty() ? null : WfdCaps.describe(body, RTP_PORT));
        } else if (method.equals("SET_PARAMETER")) {
            reply(seq, "", null);
            for (String line : body.split("\r\n")) {
                if (line.startsWith("wfd_presentation_URL:")) {
                    String[] p = line.substring(21).trim().split(" ");
                    if (p.length > 0 && p[0].startsWith("rtsp://")) url = p[0];
                }
                if (line.startsWith("wfd_sec_rtp_transport:") && line.contains("TCP")) tcp = true;
            }
            if (body.contains("wfd_trigger_method: SETUP")) setup();
        } else {
            reply(seq, "", null);
        }
    }

    private void setup() throws IOException {
        closeUdp();
        if (tcp) {
            // the source connects to us, so the port must be listening before SETUP is sent
            final ServerSocket server = new ServerSocket(RTP_PORT, 2, local);
            tcpServer = server;
            new Thread(new Runnable() {
                @Override public void run() { receiveTcp(server); }
            }).start();
            request("SETUP", url, "Transport: RTP/AVP/TCP;unicast;client_port=" + RTP_PORT + "\r\n");
            return;
        }
        udp = new DatagramSocket(new InetSocketAddress(local, RTP_PORT));
        startRtcp();
        new Thread(new Runnable() {
            @Override public void run() { receiveRtp(udp); }
        }).start();
        request("SETUP", url, "Transport: RTP/AVP/UDP;unicast;client_port=" + RTP_PORT + "\r\n");
    }

    private void onReply(int seq, String status, String head) throws IOException {
        String what;
        synchronized (pending) { what = pending.remove(seq); }
        if (what == null) return;
        if (!status.contains(" 200")) {
            log.note("!! " + what + " refused: " + status);
            return;
        }
        if (what.equals("SETUP")) {
            String session = RtspText.headerValue(head, "session");
            if (session == null) session = "";
            int semi = session.indexOf(';');
            if (semi >= 0) session = session.substring(0, semi);
            request("PLAY", url, "Session: " + session.trim() + "\r\n");
        } else if (what.equals("PLAY")) {
            log.note("PLAY accepted, waiting for the picture on port " + RTP_PORT);
        }
    }

    /**
     * Packets from the network (UDP, or TCP when the source asks for it) go through one queue, so
     * reading the socket and decoding run on separate threads: a slow decoder call must never stop
     * the reading, or packets pile up in the socket and get dropped (the picture then stutters).
     * Decoding a malformed packet must not end the app: a failure is counted and the next packet read.
     */
    private final class Pump {
        private final java.util.concurrent.LinkedBlockingQueue<byte[]> queue = new java.util.concurrent.LinkedBlockingQueue<byte[]>(4000);
        private final byte[] stop = new byte[0];
        private long packets, bytes, lost, dropped, maxGap, maxQueue;
        private volatile long failures;
        private long last = System.currentTimeMillis(), prev = last;
        private int expected = -1;

        Pump() {
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        while (true) {
                            byte[] pkt = queue.take();
                            if (pkt == stop) return;
                            try {
                                if (player != null) player.feedRtp(pkt, pkt.length);
                            } catch (RuntimeException e) {
                                if (failures++ < 3) log.note("a packet could not be decoded: " + e);
                            }
                        }
                    } catch (InterruptedException ignored) { }
                }
            }).start();
        }

        void packet(byte[] b, int len) {
            long now = System.currentTimeMillis();
            packets++;
            bytes += len;
            if (len >= 12) {
                int seq = ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
                srcSsrc = ((b[8] & 0xFF) << 24) | ((b[9] & 0xFF) << 16) | ((b[10] & 0xFF) << 8) | (b[11] & 0xFF);
                if (seq < lastSeq - 30000) cycles += 65536;
                lastSeq = seq;
                extSeq = cycles + seq;
                if (expected >= 0 && seq != expected) {
                    int missing = (seq - expected) & 0xFFFF;
                    if (missing < 1000) { lost += missing; totalLost = lost; }
                }
                expected = (seq + 1) & 0xFFFF;
            }
            maxGap = Math.max(maxGap, now - prev);
            prev = now;
            byte[] copy = java.util.Arrays.copyOf(b, len);
            if (!queue.offer(copy)) {
                queue.poll();
                queue.offer(copy);
                dropped++;
            }
            maxQueue = Math.max(maxQueue, queue.size());
            if (packets == 1) log.note("first RTP packet, " + len + " bytes");
            if (now - last >= 5000) {
                log.note("RTP: " + packets + " packets, lost " + lost + ", dropped " + dropped
                        + ", longest gap " + maxGap + " ms, queue max " + maxQueue);
                maxGap = 0;
                maxQueue = 0;
                last = now;
            }
        }

        void close() { queue.offer(stop); }
    }

    private void receiveRtp(final DatagramSocket s) {
        try { s.setReceiveBufferSize(4 << 20); } catch (java.net.SocketException ignored) { }
        Pump pump = new Pump();
        byte[] b = new byte[2048];
        try {
            while (!s.isClosed()) {
                DatagramPacket p = new DatagramPacket(b, b.length);
                s.receive(p);
                if (!source.equals(p.getAddress())) continue;       // anybody else on the network is ignored
                pump.packet(b, p.getLength());
            }
        } catch (IOException ignored) {
        } finally {
            pump.close();
        }
    }

    /**
     * RTP over TCP, as a Samsung source uses it: it connects to our port and sends every RTP
     * packet behind a two-byte length (RFC 4571). Only the source of the RTSP session is let in.
     */
    private void receiveTcp(final ServerSocket server) {
        Pump pump = new Pump();
        Socket s = null;
        try {
            long deadline = System.currentTimeMillis() + ACCEPT_MS;
            while (s == null) {
                int left = (int) (deadline - System.currentTimeMillis());
                if (left <= 0) throw new SocketTimeoutException("the source did not connect for RTP");
                server.setSoTimeout(left);
                Socket c = server.accept();
                if (source.equals(c.getInetAddress())) s = c;
                else try { c.close(); } catch (IOException ignored) { }
            }
            rtpSocket = s;
            log.note("the source connected for RTP over TCP");
            s.setReceiveBufferSize(4 << 20);
            s.setSoTimeout(30000);
            InputStream in = s.getInputStream();
            byte[] b = new byte[65536];
            boolean first = true;
            while (true) {
                int hi = in.read();
                int lo = in.read();
                if (hi < 0 || lo < 0) break;
                int len = (hi << 8) | lo;
                readFully(in, b, len);
                if (first) {
                    first = false;
                    log.note("first TCP frame, length " + len);
                }
                pump.packet(b, len);
            }
        } catch (IOException e) {
            log.note("RTP over TCP: " + e.getMessage());
        } finally {
            pump.close();
            if (s != null) try { s.close(); } catch (IOException ignored) { }
            try { server.close(); } catch (IOException ignored) { }
        }
    }

    private static void readFully(InputStream in, byte[] b, int len) throws IOException {
        int got = 0;
        while (got < len) {
            int n = in.read(b, got, len - got);
            if (n < 0) throw new IOException("stream ended inside a packet");
            got += n;
        }
    }

    /**
     * Once a second an RTCP receiver report goes to the source's RTCP port (its RTP port + 1), and
     * what the source sends there is read and thrown away: a closed port would answer with ICMP
     * errors, and a Samsung source ends the session if it hears nothing about the link.
     */
    private void startRtcp() {
        try {
            final DatagramSocket s = new DatagramSocket(new InetSocketAddress(local, RTP_PORT + 1));
            rtcp = s;
            new Thread(new Runnable() {
                @Override public void run() {
                    byte[] b = new byte[1500];
                    try {
                        while (!s.isClosed()) {
                            s.setSoTimeout(1000);
                            try { s.receive(new DatagramPacket(b, b.length)); } catch (SocketTimeoutException e) { /* time to report */ }
                            if (srcSsrc != 0) sendReport(s, source);
                        }
                    } catch (IOException ignored) { }
                }
            }).start();
        } catch (IOException e) {
            log.note("RTCP: " + e.getMessage());
        }
    }

    private long lastReport;

    private void sendReport(DatagramSocket s, InetAddress to) throws IOException {
        long now = System.currentTimeMillis();
        if (now - lastReport < 900) return;
        lastReport = now;
        long expected = extSeq - reportedSeq;
        long lostNow = totalLost - reportedLost;
        int fraction = expected > 0 ? (int) Math.min(255, lostNow * 256 / expected) : 0;
        reportedSeq = extSeq;
        reportedLost = totalLost;
        byte[] r = new byte[32];
        r[0] = (byte) 0x81;                       // version 2, one report block
        r[1] = (byte) 201;                        // receiver report
        r[3] = 7;                                 // length in words minus one
        put32(r, 4, 0x54564C31);                  // our own SSRC
        put32(r, 8, srcSsrc);
        r[12] = (byte) fraction;
        r[13] = (byte) (totalLost >> 16);
        r[14] = (byte) (totalLost >> 8);
        r[15] = (byte) totalLost;
        put32(r, 16, (int) extSeq);
        s.send(new DatagramPacket(r, r.length, to, RTP_PORT + 1));
    }

    private static void put32(byte[] b, int o, int v) {
        b[o] = (byte) (v >> 24);
        b[o + 1] = (byte) (v >> 16);
        b[o + 2] = (byte) (v >> 8);
        b[o + 3] = (byte) v;
    }

    private void closeUdp() {
        ServerSocket t = tcpServer;
        tcpServer = null;
        if (t != null) try { t.close(); } catch (IOException ignored) { }
        Socket c = rtpSocket;
        rtpSocket = null;
        if (c != null) try { c.close(); } catch (IOException ignored) { }
        DatagramSocket r = rtcp;
        rtcp = null;
        if (r != null) r.close();
        DatagramSocket s = udp;
        udp = null;
        if (s != null) s.close();
    }

    private void reply(int seq, String extraHeaders, String body) throws IOException {
        StringBuilder sb = new StringBuilder("RTSP/1.0 200 OK\r\nCSeq: ").append(seq).append("\r\n").append(extraHeaders);
        if (body != null) {
            int len = body.getBytes("UTF-8").length;
            sb.append("Content-Type: text/parameters\r\nContent-Length: ").append(len).append("\r\n");
        }
        sb.append("\r\n");
        if (body != null) sb.append(body);
        write(sb.toString());
        log.note(">> 200 OK" + (body != null ? " [" + body.replace("\r\n", " ; ").trim() + "]" : ""));
    }

    private synchronized void request(String method, String target, String headers) throws IOException {
        int seq = ++cseq;
        synchronized (pending) { pending.put(seq, method); }
        write(method + " " + target + " RTSP/1.0\r\nCSeq: " + seq + "\r\n" + headers + "\r\n");
        log.note(">> " + method);
    }

    /** Asks the source for a new key frame (a picture that stands on its own): wfd_idr_request. */
    void requestKeyframe() {
        try {
            String body = "wfd_idr_request\r\n";
            request("SET_PARAMETER", "rtsp://localhost/wfd1.0",
                    "Content-Type: text/parameters\r\nContent-Length: " + body.length() + "\r\n\r\n" + body.substring(0, body.length() - 2));
        } catch (IOException ignored) { }
    }

    private synchronized void write(String s) throws IOException {
        out.write(s.getBytes("UTF-8"));
        out.flush();
    }
}
