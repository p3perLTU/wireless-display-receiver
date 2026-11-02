package lt.tvcast.displayreceiver;

import java.util.Locale;

/** The two-letter country the Wi-Fi is told about; it ends up inside a shell command, so it is checked. */
final class CountryCode {
    private CountryCode() { }

    /** An ISO 3166 alpha-2 code in capitals, or null when the text is not exactly two ASCII letters. */
    static String normalise(String country) {
        if (country == null || country.length() != 2) return null;
        for (int i = 0; i < 2; i++) {
            char c = country.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) return null;
        }
        return country.toUpperCase(Locale.ROOT);
    }
}
