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

    /**
     * The display EDID a Windows source asks for in M3 (wfd_display_edid). One 128-byte EDID 1.3 block for a
     * digital 1920x1080 at 60 Hz display called "Android TV". Without it a Windows 11 PC opened the Wi-Fi
     * Direct group and the RTSP session, answered M3 and then never sent M4, so no picture ever started.
     * Built field by field (instead of a hex blob) so it can be read and checked.
     */
    static String edid() {
        int[] e = new int[128];
        int[] header = {0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x00};
        System.arraycopy(header, 0, e, 0, 8);
        e[8] = 0x5C; e[9] = 0x92;                     // manufacturer "WDR" (not a registered PnP id)
        e[10] = 0x01;                                 // product code
        e[16] = 1; e[17] = 34;                        // week 1 of 2024
        e[18] = 1; e[19] = 3;                         // EDID 1.3
        e[20] = 0x80;                                 // digital input
        e[23] = 0x78;                                 // gamma 2.2
        e[24] = 0x0A;                                 // RGB 4:4:4, preferred timing in the first descriptor
        int[] chroma = {0xEE, 0x91, 0xA3, 0x54, 0x4C, 0x99, 0x26, 0x0F, 0x50, 0x54};   // sRGB primaries, D65
        System.arraycopy(chroma, 0, e, 25, chroma.length);
        e[38] = 0xD1; e[39] = 0xC0;                   // standard timing: 1920x1080, 16:9, 60 Hz
        for (int i = 40; i < 54; i++) e[i] = 0x01;    // the other standard timings: unused
        int[] dtd = {0x02, 0x3A, 0x80, 0x18, 0x71, 0x38, 0x2D, 0x40, 0x58, 0x2C, 0x45, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x1E};  // 148.5 MHz, 1920x1080 (htotal 2200, vtotal 1125), +hsync +vsync
        System.arraycopy(dtd, 0, e, 54, dtd.length);
        int[] name = {0x00, 0x00, 0x00, 0xFC, 0x00, 'A', 'n', 'd', 'r', 'o', 'i', 'd', ' ', 'T', 'V', 0x0A, 0x20, 0x20};
        System.arraycopy(name, 0, e, 72, name.length);
        int[] range = {0x00, 0x00, 0x00, 0xFD, 0x00, 59, 61, 15, 70, 15, 0x00, 0x0A, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20};
        System.arraycopy(range, 0, e, 90, range.length);
        e[111] = 0x10;                                // descriptor 4: dummy
        int sum = 0;
        for (int i = 0; i < 127; i++) sum += e[i];
        e[127] = (256 - (sum & 0xFF)) & 0xFF;         // the 128 bytes add up to 0 modulo 256
        StringBuilder sb = new StringBuilder("0001 ");   // one block
        for (int b : e) sb.append(String.format("%02x", b));
        return sb.toString();
    }

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
            case "wfd_display_edid": return edid();
            case "wfd_connector_type": return "07";           // internal / integrated display
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
