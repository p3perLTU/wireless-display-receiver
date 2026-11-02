package lt.tvcast.displayreceiver;

import static lt.tvcast.testing.Assert.*;

/** The country goes into a shell command, so only two ASCII letters are let through. */
final class CountryCodeTest {
    static void testTwoLettersAreNormalisedToCapitals() {
        assertEquals("LT", CountryCode.normalise("LT"), "LT");
        assertEquals("LT", CountryCode.normalise("lt"), "lt");
        assertEquals("DE", CountryCode.normalise("De"), "De");
    }

    static void testAnythingElseIsRefused() {
        String[] bad = { null, "", "L", "LTU", "419", "L1", "1T", "  ", "L;", ";l", "a b", "$(", "ĄČ", "L\n", "LT;reboot" };
        for (String s : bad) assertNull(CountryCode.normalise(s), "'" + s + "'");
    }

    static void testTheTurkishLocaleDoesNotChangeTheResult() {
        // upper-casing must not depend on the TV's language: under Turkish rules "i" becomes a dotted capital I
        java.util.Locale old = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(new java.util.Locale("tr", "TR"));
            assertEquals("TI", CountryCode.normalise("ti"), "ti under a Turkish locale");
        } finally {
            java.util.Locale.setDefault(old);
        }
    }
}
