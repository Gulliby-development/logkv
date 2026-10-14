package io.github.gulliby.logkv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class KeysTest {

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    static Stream<String> validKeys() {
        return Stream.of("a", "!", "~", "Key.Name/1-x", repeat('k', 255));
    }

    static Stream<String> invalidKeys() {
        return Stream.of("", repeat('k', 256), "a b", "a\tb", "\t", "a\u007fb", "\u007f", "\u00e9", "a\u00e9", "a\nb", " ");
    }

    static Stream<String> validPrefixes() {
        return Stream.of("", "a", "ab", "~!", repeat('p', 300));
    }

    static Stream<String> invalidPrefixes() {
        return Stream.of("a b", " ", "\t", "\u007f", "\u00e9", "a\u00e9");
    }

    static Stream<Arguments> orderedPairs() {
        return Stream.of(
                Arguments.of("A", "B"),
                Arguments.of("B", "_"),
                Arguments.of("_", "a"),
                Arguments.of("a", "b"),
                Arguments.of("ab", "abc"),
                Arguments.of("a", "~"),
                Arguments.of("A", "a"),
                Arguments.of("Z", "a"),
                Arguments.of("a", "ab"),
                Arguments.of("abc", "abd"));
    }

    @ParameterizedTest
    @MethodSource("validKeys")
    void validKeyIsAccepted(String key) {
        assertTrue(Keys.isValidKey(key));
    }

    @ParameterizedTest
    @MethodSource("invalidKeys")
    void invalidKeyIsRejected(String key) {
        assertFalse(Keys.isValidKey(key));
    }

    @Test
    void nullKeyIsRejected() {
        assertFalse(Keys.isValidKey(null));
    }

    @ParameterizedTest
    @MethodSource("validPrefixes")
    void validPrefixIsAccepted(String prefix) {
        assertTrue(Keys.isValidPrefix(prefix));
    }

    @ParameterizedTest
    @MethodSource("invalidPrefixes")
    void invalidPrefixIsRejected(String prefix) {
        assertFalse(Keys.isValidPrefix(prefix));
    }

    @ParameterizedTest
    @MethodSource("orderedPairs")
    void compareOrdersBytesUnsigned(String smaller, String larger) {
        byte[] a = smaller.getBytes(StandardCharsets.US_ASCII);
        byte[] b = larger.getBytes(StandardCharsets.US_ASCII);
        assertTrue(Keys.compare(a, b) < 0);
        assertTrue(Keys.compare(b, a) > 0);
        assertTrue(Keys.COMPARATOR.compare(smaller, larger) < 0);
        assertTrue(Keys.COMPARATOR.compare(larger, smaller) > 0);
    }

    @Test
    void equalKeysCompareAsZero() {
        byte[] a = "same".getBytes(StandardCharsets.US_ASCII);
        byte[] b = "same".getBytes(StandardCharsets.US_ASCII);
        assertEquals(0, Keys.compare(a, b));
        assertEquals(0, Keys.COMPARATOR.compare("same", "same"));
        assertEquals(0, Keys.compare(new byte[0], new byte[0]));
    }

    @Test
    void emptySortsBeforeAnything() {
        assertTrue(Keys.compare(new byte[0], new byte[] {0x21}) < 0);
        assertTrue(Keys.compare(new byte[] {0x21}, new byte[0]) > 0);
    }

    @Test
    void highBytesSortAfterTilde() {
        byte[] high = {(byte) 0x80};
        byte[] ff = {(byte) 0xFF};
        byte[] tilde = {0x7E};
        assertTrue(Keys.compare(high, tilde) > 0);
        assertTrue(Keys.compare(tilde, high) < 0);
        assertTrue(Keys.compare(ff, high) > 0);
        assertTrue(Keys.compare(new byte[] {0x00}, high) < 0);
    }

    @Test
    void comparatorSortsListInByteOrder() {
        List<String> keys = new ArrayList<>(List.of("b", "B", "a", "A", "_", "ab"));
        keys.sort(Keys.COMPARATOR);
        assertEquals(List.of("A", "B", "_", "a", "ab", "b"), keys);
    }
}
