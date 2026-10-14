package io.github.gulliby.logkv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OptionsTest {
    private static final String KEY_HINT = " (expected 1 to 255 printable ASCII characters, no spaces)";
    private static final String PREFIX_HINT = " (expected printable ASCII characters, no spaces)";

    private static Arguments err(String message, String... args) {
        return Arguments.of(args, message);
    }

    static Stream<Arguments> errors() {
        return Stream.of(
                err("missing command (put, get, del, incr, list, stats, compact, verify, repair or dump)"),
                err("unknown command \"frob\"", "frob"),
                err("unknown command \"--prefix\"", "--prefix"),
                err("unknown option \"--help\"", "--help", "put"),
                err("unknown option \"--help\"", "list", "f.kv", "--help"),
                err("missing FILE", "get"),
                err("missing FILE", "put"),
                err("missing FILE", "stats"),
                err("unknown option \"--bogus\"", "get", "--bogus"),
                err("unknown option \"--bogus\"", "get", "f.kv", "k", "--bogus"),
                err("missing KEY", "get", "f.kv"),
                err("missing KEY", "put", "f.kv"),
                err("missing KEY", "del", "f.kv"),
                err("missing VALUE", "put", "f.kv", "k"),
                err("missing VALUE", "put", "f.kv", "a b"),
                err("too many arguments", "stats", "f.kv", "extra"),
                err("too many arguments", "get", "f.kv", "a", "b"),
                err("too many arguments", "put", "f.kv", "a", "b", "c"),
                err("too many arguments", "list", "f.kv", "x"),
                err("too many arguments", "get", "f.kv", "a b", "extra"),
                err("invalid key \"a b\"" + KEY_HINT, "put", "f.kv", "a b", "v"),
                err("invalid key \"a b\"" + KEY_HINT, "get", "f.kv", "a b"),
                err("invalid key \"\"" + KEY_HINT, "get", "f.kv", ""),
                err("invalid key \"" + "k".repeat(256) + "\"" + KEY_HINT, "del", "f.kv", "k".repeat(256)),
                err("--prefix needs a value", "list", "f.kv", "--prefix"),
                err("invalid prefix \"a b\"" + PREFIX_HINT, "list", "f.kv", "--prefix", "a b"),
                err("invalid prefix \"\u00e9\"" + PREFIX_HINT, "list", "f.kv", "--prefix=\u00e9"),
                err("unknown option \"--prefix=a\"", "stats", "--prefix=a", "f.kv"),
                err("unknown option \"--prefix\"", "get", "f.kv", "a", "--prefix", "x"),
                err("unknown option \"-x\"", "get", "-x", "f.kv", "k"),
                err("unknown option \"-x\"", "list", "f.kv", "-x"),
                err("unknown option \"--bogus\"", "list", "--bogus", "--prefix"),
                err("--prefix needs a value", "list", "--prefix"),
                err("too many arguments", "list", "f.kv", "x", "--prefix", "a b"));
    }

    @ParameterizedTest
    @MethodSource("errors")
    void rejectsInvalidArguments(String[] args, String message) {
        KvException e = assertThrows(KvException.class, () -> Options.parse(args));
        assertEquals(message, e.getMessage());
    }

    @Test
    void helpAloneIsHelp() {
        Options o = Options.parse(new String[] {"--help"});
        assertTrue(o.help());
        assertNull(o.command());
        assertNull(o.file());
    }

    @Test
    void putParsesAllFields() {
        Options o = Options.parse(new String[] {"put", "f.kv", "k", "v"});
        assertFalse(o.help());
        assertEquals("put", o.command());
        assertEquals("f.kv", o.file());
        assertEquals("k", o.key());
        assertEquals("v", o.value());
        assertNull(o.prefix());
    }

    @Test
    void dashIsStdinValue() {
        Options o = Options.parse(new String[] {"put", "f.kv", "k", "-"});
        assertEquals("-", o.value());
    }

    @Test
    void dashIsPositionalFile() {
        Options o = Options.parse(new String[] {"stats", "-"});
        assertEquals("-", o.file());
    }

    @Test
    void doubleDashMakesLaterArgumentsPositional() {
        Options o = Options.parse(new String[] {"get", "f.kv", "--", "-k"});
        assertEquals("-k", o.key());
        Options o2 = Options.parse(new String[] {"get", "--", "-f", "k"});
        assertEquals("-f", o2.file());
        assertEquals("k", o2.key());
    }

    @Test
    void doubleDashMakesPrefixAndHelpPositional() {
        Options o = Options.parse(new String[] {"put", "f.kv", "--", "--prefix", "--help"});
        assertEquals("--prefix", o.key());
        assertEquals("--help", o.value());
    }

    @Test
    void getAndDelHaveKeyButNoValue() {
        Options g = Options.parse(new String[] {"get", "f.kv", "k"});
        assertEquals("k", g.key());
        assertNull(g.value());
        Options d = Options.parse(new String[] {"del", "f.kv", "k"});
        assertEquals("del", d.command());
        assertEquals("k", d.key());
    }

    @Test
    void statsHasOnlyFile() {
        Options o = Options.parse(new String[] {"stats", "f.kv"});
        assertEquals("f.kv", o.file());
        assertNull(o.key());
        assertNull(o.value());
    }

    @Test
    void keyOf255CharactersIsAccepted() {
        String key = "k".repeat(255);
        assertEquals(key, Options.parse(new String[] {"get", "f.kv", key}).key());
    }

    @Test
    void prefixWithSeparateValueTakesDashedValue() {
        Options o = Options.parse(new String[] {"list", "f.kv", "--prefix", "-x"});
        assertEquals("-x", o.prefix());
    }

    @Test
    void prefixWithEqualsForm() {
        assertEquals("a", Options.parse(new String[] {"list", "f.kv", "--prefix=a"}).prefix());
    }

    @Test
    void lastPrefixWins() {
        Options o = Options.parse(new String[] {"list", "f.kv", "--prefix=a", "--prefix", "b"});
        assertEquals("b", o.prefix());
        Options o2 = Options.parse(new String[] {"list", "f.kv", "--prefix", "b", "--prefix=c"});
        assertEquals("c", o2.prefix());
    }

    @Test
    void emptyPrefixIsValid() {
        Options o = Options.parse(new String[] {"list", "f.kv", "--prefix="});
        assertEquals("", o.prefix());
        Options o2 = Options.parse(new String[] {"list", "f.kv", "--prefix", ""});
        assertEquals("", o2.prefix());
    }

    @Test
    void prefixMayComeBeforeFile() {
        Options o = Options.parse(new String[] {"list", "--prefix", "a", "f.kv"});
        assertEquals("f.kv", o.file());
        assertEquals("a", o.prefix());
    }

    @Test
    void listWithoutPrefixHasNullPrefix() {
        assertNull(Options.parse(new String[] {"list", "f.kv"}).prefix());
    }
}
