package lt.tvcast.displayreceiver;

import static lt.tvcast.testing.Assert.*;

/** What the receiver tells a source: it looks like a Samsung TV, claims no HDCP, and answers anything. */
final class WfdCapsTest {
    static String answer(String asked) { return WfdCaps.describe(asked, 19000); }

    static void testEveryNameAskedGetsOneLineInOrder() {
        String a = answer("wfd_video_formats\r\nwfd_audio_codecs\r\nwfd_client_rtp_ports");
        String[] lines = a.split("\r\n");
        assertEquals(3, lines.length, "three lines");
        assertTrue(lines[0].startsWith("wfd_video_formats: "), "first");
        assertTrue(lines[1].startsWith("wfd_audio_codecs: "), "second");
        assertTrue(lines[2].startsWith("wfd_client_rtp_ports: "), "third");
    }

    static void testHdcpIsNeverClaimed() {
        assertEquals("wfd_content_protection: none\r\n", answer("wfd_content_protection"), "content protection");
    }

    static void testUnknownNamesAreAnsweredNone() {
        assertEquals("wfd_something_new: none\r\nwfd_sec_framerate: none\r\n",
                answer("wfd_something_new\r\nwfd_sec_framerate"), "unknown parameters");
    }

    static void testTheRtpPortIsTheOneGiven() {
        assertEquals("wfd_client_rtp_ports: RTP/AVP/UDP;unicast 19000 0 mode=play\r\n", answer("wfd_client_rtp_ports"), "ports");
        assertTrue(WfdCaps.describe("wfd_client_rtp_ports", 12345).contains("unicast 12345 0"), "another port");
    }

    static void testTheSamsungParametersAreAnswered() {
        String a = answer("wfd2_sec_version\r\nwfd_sec_dex_support\r\nwfd_sec_screensharing\r\nwfd_sec_dex_mouse_support");
        assertTrue(a.contains("wfd2_sec_version: 2.2.09100\r\n"), "version");
        assertTrue(a.contains("wfd_sec_dex_support: yes\r\n"), "DeX");
        assertTrue(a.contains("wfd_sec_screensharing: yes\r\n"), "screen sharing");
        assertTrue(a.contains("wfd_sec_dex_mouse_support: no\r\n"), "no mouse on the TV");
    }

    static void testVideoListOffersHevcUpTo1440p30() {
        String list = WfdCaps.R2_VIDEO;
        assertEquals(6, list.split(", ").length, "six entries");
        assertTrue(list.contains("02 01 0003"), "HEVC main");
        assertTrue(list.contains("000151555555"), "the VESA modes up to 2560x1440 at 30");
        assertEquals(WfdCaps.R2_VIDEO, answer("wfd2_video_formats").trim().substring("wfd2_video_formats: ".length()), "the answer");
    }

    /** A Windows source asks for the display EDID; without a valid one it never goes on to M4. */
    static void testTheDisplayEdidIsAValidOneBlockEdid() {
        String a = answer("wfd_display_edid").trim();
        assertTrue(a.startsWith("wfd_display_edid: 0001 00ffffffffffff00"), "one block, EDID header");
        String hex = a.substring("wfd_display_edid: 0001 ".length());
        assertEquals(256, hex.length(), "128 bytes in hex");
        int sum = 0;
        for (int i = 0; i < 128; i++) sum += Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        assertEquals(0, sum & 0xFF, "the 128 bytes add up to 0 modulo 256");
        assertEquals(0x01, Integer.parseInt(hex.substring(2 * 18, 2 * 18 + 2), 16), "EDID version 1");
        assertTrue(hex.contains("416e64726f6964205456"), "named Android TV");
        assertTrue(hex.contains("023a801871382d40582c4500"), "the first timing is 1920x1080 at 60 Hz");
    }

    static void testTheConnectorIsInternal() {
        assertEquals("wfd_connector_type: 07\r\n", answer("wfd_connector_type"), "connector type");
    }

    static void testTheNameIsASamsungTvOne() {
        assertEquals("SVDTZ8_0/SEC-UIBC3", WfdCaps.USER_AGENT, "user agent");
    }

    /** Names come from the other device and go back into a message: only plain names are echoed. */
    static void testOddNamesAreNotEchoed() {
        assertEquals("", answer("bad name\r\nx: y\r\nz\u0000y\r\nåå"), "names with spaces, colons, zero bytes or non-ASCII letters");
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i <= WfdCaps.MAX_KEY; i++) longName.append('a');
        assertEquals("", answer(longName.toString()), "a name longer than the limit");
        assertEquals("wfd_ok: none\r\n", answer("bad name\r\nwfd_ok"), "plain names around it are still answered");
    }

    static void testEmptyListGivesAnEmptyAnswer() {
        assertEquals("", answer(""), "nothing asked");
        assertEquals("", answer("\r\n\r\n  \r\n"), "only blank lines");
    }
}
