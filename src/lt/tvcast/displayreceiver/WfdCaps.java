package lt.tvcast.displayreceiver;

/**
 * What this receiver tells a source about itself (the M3 answer) and the name it gives itself in RTSP.
 * No Android classes, so the answers can be tested. Two properties are deliberate: HDCP is never
 * claimed (the receiver cannot decrypt protected content), and every parameter that is not known is
 * answered "none" instead of being refused.
 */
final class WfdCaps {
    private WfdCaps() { }

    /** What a Samsung TV (Tizen 8) calls itself; a Samsung phone gives its faster modes to such a receiver. */
    static final String USER_AGENT = "SVDTZ8_0/SEC-UIBC3";

    /** The video list a Samsung TV gives: H.264 and HEVC, up to 2560x1440 at 30 frames a second. */
    static final String R2_VIDEO =
            "20 01 01 0008 0000000800a1 000151555555 000000000555 00 0000 00c8 01, "
            + "01 08 0008 0000000800a1 000151555555 000000000555 00 0000 00c8 01, "
            + "01 10 0008 0000000800a1 000151555555 000000000555 00 0000 00c8 01, "
            + "01 20 0008 0000000800a1 000151555555 000000000555 00 0000 00c8 01, "
            + "02 01 0003 0000000800a1 000151555555 000000000555 00 0000 00c8 01, "
            + "02 02 0003 0000000800a1 000151555555 000000000555 00 0000 00c8 01 01";

    /** The longest parameter name echoed back; the list comes from the other device. */
    static final int MAX_KEY = 64;

    /** The answer to an M3 GET_PARAMETER body: one "name: value" line for every name asked. */
    static String describe(String asked, int rtpPort) {
        StringBuilder sb = new StringBuilder();
        for (String line : asked.split("\r\n")) {
            String key = line.trim();
            if (key.isEmpty() || key.length() > MAX_KEY || !key.matches("[A-Za-z0-9_]+")) continue;
            sb.append(key).append(": ").append(value(key, rtpPort)).append("\r\n");
        }
        return sb.toString();
    }

    private static String value(String key, int rtpPort) {
        switch (key) {
            case "wfd_video_formats": return "00 00 02 10 0001ffff 1fffffff 00000fff 00 0000 0000 00 none none";
            case "wfd2_video_formats": return R2_VIDEO;
            case "wfd_audio_codecs":
            case "wfd2_audio_codecs": return "LPCM 00000003 00, AAC 00000001 00";
            case "wfd2_sec_version": return "2.2.09100";
            case "wfd_sec_screensharing":
            case "wfd_sec_dex_support": return "yes";
            case "wfd_sec_dex_mouse_support": return "no";
            case "wfd_sec_portrait_display": return "disable";
            case "wfd_client_rtp_ports": return "RTP/AVP/UDP;unicast " + rtpPort + " 0 mode=play";
            default: return "none";               // includes wfd_content_protection: no HDCP
        }
    }
}
