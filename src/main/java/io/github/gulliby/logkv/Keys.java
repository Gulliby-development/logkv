package io.github.gulliby.logkv;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;

/** Key validation and unsigned byte-order comparison. */
public final class Keys {
    public static final Comparator<String> COMPARATOR =
            (a, b) -> compare(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));

    private Keys() {
    }

    public static boolean isValidKey(String key) {
        return key != null && key.length() >= 1 && key.length() <= Record.MAX_KEY_LENGTH && allPrintable(key);
    }

    public static boolean isValidPrefix(String prefix) {
        return prefix != null && allPrintable(prefix);
    }

    private static boolean allPrintable(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    public static int compare(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = a[i] & 0xFF;
            int y = b[i] & 0xFF;
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }
        return Integer.compare(a.length, b.length);
    }
}
