package io.github.gulliby.logkv;

/** Strict parsing and checked arithmetic for signed 64-bit counters. */
public final class Counter {
    private Counter() {
    }

    /**
     * Parses a canonical signed decimal value that fits in a {@code long}.
     *
     * @throws NumberFormatException if the value is not canonical or is outside the signed 64-bit range
     */
    public static long parse(String value) {
        if (value == null || value.isEmpty()) {
            throw invalidNumber();
        }

        int firstDigit = value.charAt(0) == '-' ? 1 : 0;
        if (firstDigit == value.length()
                || value.charAt(firstDigit) == '0' && (firstDigit == 1 || value.length() > firstDigit + 1)) {
            throw invalidNumber();
        }
        for (int i = firstDigit; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                throw invalidNumber();
            }
        }
        return Long.parseLong(value);
    }

    /** Adds two counter values, rejecting a result outside the signed 64-bit range. */
    public static long add(long current, long delta) {
        return Math.addExact(current, delta);
    }

    private static NumberFormatException invalidNumber() {
        return new NumberFormatException("not a canonical signed 64-bit integer");
    }
}
