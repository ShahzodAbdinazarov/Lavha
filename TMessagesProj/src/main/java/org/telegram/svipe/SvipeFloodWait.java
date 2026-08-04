package org.telegram.svipe;

/**
 * Reads the wait out of Telegram's flood errors ("FLOOD_WAIT_4473", "FLOOD_PREMIUM_WAIT_60").
 *
 * Worth its own pure class because the number matters: a flood window is measured in minutes to
 * hours, and code that keeps calling a flood-limited method inside its window can have the window
 * extended. Pure Java so the parsing is unit-tested on the JVM.
 */
public class SvipeFloodWait {

    private static final String[] PREFIXES = {"FLOOD_PREMIUM_WAIT_", "FLOOD_WAIT_", "SLOWMODE_WAIT_"};

    /** Seconds to wait, or 0 when the text is not a flood error (or carries no usable number). */
    public static int secondsIn(String errorText) {
        if (errorText == null) return 0;
        for (String prefix : PREFIXES) {
            int at = errorText.indexOf(prefix);
            if (at < 0) continue;
            int i = at + prefix.length();
            int value = 0;
            boolean any = false;
            while (i < errorText.length() && errorText.charAt(i) >= '0' && errorText.charAt(i) <= '9') {
                if (value > (Integer.MAX_VALUE - 9) / 10) return Integer.MAX_VALUE; // absurd, but never overflow
                value = value * 10 + (errorText.charAt(i) - '0');
                any = true;
                i++;
            }
            return any ? value : 0;
        }
        return 0;
    }
}
