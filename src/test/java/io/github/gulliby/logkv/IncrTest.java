package io.github.gulliby.logkv;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class IncrTest {
    private static final byte[] NONE = new byte[0];
    private static final String NUMBER_HINT =
            " (expected a whole number from -9223372036854775808 to 9223372036854775807)";

    @TempDir
    Path tmp;

    private static final class Result {
        final int code;
        final byte[] out;
        final byte[] err;

        Result(int code, byte[] out, byte[] err) {
            this.code = code;
            this.out = out;
            this.err = err;
        }

        String outText() {
            return new String(out, StandardCharsets.UTF_8);
        }

        String errText() {
            return new String(err, StandardCharsets.UTF_8);
        }
    }

    private static Result run(InputStream stdin, String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(stdout, false, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(stderr, false, StandardCharsets.UTF_8);
        int code = Cli.run(args, stdin, out, err);
        out.flush();
        err.flush();
        return new Result(code, stdout.toByteArray(), stderr.toByteArray());
    }

    private static Result run(String... args) {
        return run(new ByteArrayInputStream(NONE), args);
    }

    private static void check(Result actual, int code, String out, String err) {
        assertEquals(code, actual.code);
        assertEquals(out, actual.outText());
        assertEquals(err, actual.errText());
        assertLf(actual.out);
        assertLf(actual.err);
    }

    private static void assertLf(byte[] bytes) {
        for (byte value : bytes) {
            assertTrue(value != 0x0D, "output contains CR");
        }
        if (bytes.length > 0) {
            assertEquals(0x0A, bytes[bytes.length - 1], "output does not end with LF");
        }
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            output.writeBytes(part);
        }
        return output.toByteArray();
    }

    private static byte[] valueBytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static String display(Path path) {
        return path.toString().replace('\\', '/');
    }

    static Stream<String> invalidStoredValues() {
        return Stream.of("abc", "1.5", " 1", "+1", "007", "-0", "", "9223372036854775808",
                "-9223372036854775809");
    }

    static Stream<String> invalidDeltas() {
        return Stream.of("007", "+1", "-0", "1.5", "9223372036854775808", "-9223372036854775809", "");
    }

    static Stream<Arguments> argumentErrors() {
        return Stream.of(
                Arguments.of((Object) new String[] {"incr"}, "missing FILE"),
                Arguments.of((Object) new String[] {"incr", "F"}, "missing KEY"),
                Arguments.of((Object) new String[] {"incr", "F", ""}, "invalid key \"\""
                        + " (expected 1 to 255 printable ASCII characters, no spaces)"),
                Arguments.of((Object) new String[] {"incr", "F", "bad key", "007"}, "invalid key \"bad key\""
                        + " (expected 1 to 255 printable ASCII characters, no spaces)"),
                Arguments.of((Object) new String[] {"incr", "F", "k", "007", "extra"}, "too many arguments"),
                Arguments.of((Object) new String[] {"incr", "F", "k", "007"}, "invalid delta \"007\"" + NUMBER_HINT));
    }

    static Stream<String> unknownIncrOptions() {
        return Stream.of("-x", "-1x", "--prefix", "-");
    }

    static Stream<Arguments> parsedCounterValues() {
        return Stream.of(
                Arguments.of("0", 0L),
                Arguments.of("1", 1L),
                Arguments.of("-1", -1L),
                Arguments.of("9223372036854775807", Long.MAX_VALUE),
                Arguments.of("-9223372036854775808", Long.MIN_VALUE));
    }

    @Test
    void workedExampleIncrementsAndGetsRawValue() throws IOException {
        Path file = tmp.resolve("demo.kv");
        String path = file.toString();

        check(run("incr", path, "hits"), 0, "1\n", "");
        check(run("incr", path, "hits"), 0, "2\n", "");
        check(run("incr", path, "hits", "5"), 0, "7\n", "");
        check(run("incr", path, "balance", "-20"), 0, "-20\n", "");
        check(run("incr", path, "hits", "--", "-20"), 0, "-13\n", "");

        Result get = run("get", path, "hits");
        assertEquals(0, get.code);
        assertArrayEquals("-13".getBytes(StandardCharsets.US_ASCII), get.out);
        assertEquals("", get.errText());
        assertArrayEquals(cat(Record.MAGIC,
                Record.put("hits", valueBytes("1")).encode(),
                Record.put("hits", valueBytes("2")).encode(),
                Record.put("hits", valueBytes("7")).encode(),
                Record.put("balance", valueBytes("-20")).encode(),
                Record.put("hits", valueBytes("-13")).encode()), Files.readAllBytes(file));
    }

    @Test
    void defaultZeroAndNegativeDeltasAppendRecords() throws IOException {
        Path file = tmp.resolve("counters.kv");
        String path = file.toString();

        check(run("incr", path, "n"), 0, "1\n", "");
        check(run("incr", path, "n", "0"), 0, "1\n", "");
        check(run("incr", path, "n", "-3"), 0, "-2\n", "");
        check(run("dump", path), 0, "4 put n 1\n17 put n 1\n30 put n 2\n", "");
        assertArrayEquals(cat(Record.MAGIC,
                Record.put("n", valueBytes("1")).encode(),
                Record.put("n", valueBytes("1")).encode(),
                Record.put("n", valueBytes("-2")).encode()), Files.readAllBytes(file));
    }

    @Test
    void newFileStoresCanonicalAsciiDigitsWithoutLineFeed() throws IOException {
        Path file = tmp.resolve("bytes.kv");
        check(run("incr", file.toString(), "k", "42"), 0, "42\n", "");

        byte[] actual = Files.readAllBytes(file);
        assertEquals(18, actual.length);
        assertArrayEquals(cat(Record.MAGIC, Record.put("k", valueBytes("42")).encode()), actual);
        Result get = run("get", file.toString(), "k");
        assertEquals(0, get.code);
        assertArrayEquals(valueBytes("42"), get.out);
        assertEquals("", get.errText());
    }

    @Test
    void deletedKeyStartsAtZero() {
        Path file = tmp.resolve("deleted.kv");
        String path = file.toString();

        check(run("put", path, "count", "15"), 0, "", "");
        check(run("del", path, "count"), 0, "", "");
        check(run("incr", path, "count", "-2"), 0, "-2\n", "");
    }

    @ParameterizedTest
    @MethodSource("parsedCounterValues")
    void counterParsesCanonicalSignedLongValues(String raw, long expected) {
        assertEquals(expected, Counter.parse(raw));
    }

    @ParameterizedTest
    @MethodSource("invalidStoredValues")
    void counterRejectsNoncanonicalAndOutOfRangeValues(String raw) {
        assertThrows(NumberFormatException.class, () -> Counter.parse(raw));
    }

    @Test
    void counterAdditionAcceptsBoundaryResultsAndRejectsOverflowBothWays() {
        assertEquals(Long.MAX_VALUE, Counter.add(Long.MAX_VALUE - 1, 1));
        assertEquals(Long.MIN_VALUE, Counter.add(Long.MIN_VALUE + 1, -1));
        assertEquals(-1, Counter.add(Long.MIN_VALUE, Long.MAX_VALUE));
        assertThrows(ArithmeticException.class, () -> Counter.add(Long.MAX_VALUE, 1));
        assertThrows(ArithmeticException.class, () -> Counter.add(Long.MIN_VALUE, -1));
    }

    @Test
    void cliAcceptsSignedEndpointsAndRejectsOverflowWithoutChangingFiles() throws IOException {
        Path high = tmp.resolve("high.kv");
        check(run("incr", high.toString(), "hi", "9223372036854775807"), 0,
                "9223372036854775807\n", "");
        byte[] highBefore = Files.readAllBytes(high);
        check(run("incr", high.toString(), "hi"), 2, "",
                "logkv: result for \"hi\" is out of range\n");
        assertArrayEquals(highBefore, Files.readAllBytes(high));

        Path low = tmp.resolve("low.kv");
        check(run("incr", low.toString(), "lo", "-9223372036854775808"), 0,
                "-9223372036854775808\n", "");
        byte[] lowBefore = Files.readAllBytes(low);
        check(run("incr", low.toString(), "lo", "-1"), 2, "",
                "logkv: result for \"lo\" is out of range\n");
        assertArrayEquals(lowBefore, Files.readAllBytes(low));
        check(run("incr", low.toString(), "lo", "9223372036854775807"), 0, "-1\n", "");
    }

    @ParameterizedTest
    @MethodSource("invalidStoredValues")
    void invalidStoredValueFailsWithoutOutputOrFileChanges(String value) throws IOException {
        Path file = tmp.resolve("invalid.kv");
        check(run("put", file.toString(), "--", "k", value), 0, "", "");
        byte[] before = Files.readAllBytes(file);

        check(run("incr", file.toString(), "k"), 2, "",
                "logkv: value of \"k\" is not a whole number\n");
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @ParameterizedTest
    @MethodSource("invalidDeltas")
    void invalidDeltaFailsBeforeCreatingOrChangingFile(String delta) throws IOException {
        Path file = tmp.resolve("delta.kv");

        check(run("incr", file.toString(), "k", delta), 2, "",
                "logkv: invalid delta \"" + delta + "\"" + NUMBER_HINT + "\n");
        assertFalse(Files.exists(file));
    }

    @ParameterizedTest
    @MethodSource("argumentErrors")
    void argumentErrorsKeepTheirOrderAndDoNotTouchFile(String[] template, String message) {
        Path file = tmp.resolve("args.kv");
        String[] args = template.clone();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("F")) {
                args[i] = file.toString();
            }
        }

        check(run(args), 2, "", "logkv: " + message + "\n");
        assertFalse(Files.exists(file));
    }

    @ParameterizedTest
    @MethodSource("unknownIncrOptions")
    void nonnumericDashArgumentsAreUnknownOptions(String argument) {
        Path file = tmp.resolve("option.kv");
        check(run("incr", file.toString(), "k", argument), 2, "",
                "logkv: unknown option \"" + argument + "\"\n");
        assertFalse(Files.exists(file));
    }

    @Test
    void optionsParseIncrementAndPreserveExistingOptionRules() {
        Options defaultDelta = Options.parse(new String[] {"incr", "f.kv", "k"});
        assertEquals("incr", defaultDelta.command());
        assertEquals("f.kv", defaultDelta.file());
        assertEquals("k", defaultDelta.key());
        assertEquals(1, defaultDelta.delta());

        Options negative = Options.parse(new String[] {"incr", "f.kv", "k", "-3"});
        assertEquals(-3, negative.delta());
        Options afterDoubleDash = Options.parse(new String[] {"incr", "f.kv", "k", "--", "-20"});
        assertEquals(-20, afterDoubleDash.delta());

        KvException putError = assertThrows(KvException.class,
                () -> Options.parse(new String[] {"put", "f.kv", "k", "-5"}));
        assertEquals("unknown option \"-5\"", putError.getMessage());
        Options putAfterDoubleDash = Options.parse(new String[] {"put", "f.kv", "--", "k", "-5"});
        assertEquals("k", putAfterDoubleDash.key());
        assertEquals("-5", putAfterDoubleDash.value());
    }

    @Test
    void createsMissingFileAndUsesExistingFileErrors() throws IOException {
        Path file = tmp.resolve("new.kv");
        check(run("incr", file.toString(), "k"), 0, "1\n", "");
        assertArrayEquals(cat(Record.MAGIC, Record.put("k", valueBytes("1")).encode()), Files.readAllBytes(file));

        check(run("incr", tmp.toString(), "k"), 2, "",
                "logkv: " + display(tmp) + ": cannot read the file\n");

        Path invalid = tmp.resolve("plain.kv");
        Files.writeString(invalid, "hello", StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(invalid);
        check(run("incr", invalid.toString(), "k"), 2, "",
                "logkv: " + display(invalid) + ": not a logkv file\n");
        assertArrayEquals(before, Files.readAllBytes(invalid));
    }

    @Test
    void damagedFileIsUnchangedUntilRepairThenIncrementWorks() throws IOException {
        Path file = tmp.resolve("damaged.kv");
        check(run("put", file.toString(), "k", "4"), 0, "", "");
        long offset = Files.size(file);
        Files.write(file, new byte[] {0, 1, 2}, StandardOpenOption.APPEND);
        byte[] damaged = Files.readAllBytes(file);

        check(run("incr", file.toString(), "k"), 2, "",
                "logkv: " + display(file) + ": corrupt at offset " + offset
                        + ": incomplete record (run repair first)\n");
        assertArrayEquals(damaged, Files.readAllBytes(file));

        check(run("repair", file.toString()), 0, "repaired: removed 3 bytes at offset " + offset + "\n", "");
        check(run("incr", file.toString(), "k"), 0, "5\n", "");
    }

    @Test
    void compactPreservesIncrementedCounter() {
        Path file = tmp.resolve("compact.kv");
        String path = file.toString();
        check(run("incr", path, "count"), 0, "1\n", "");
        check(run("incr", path, "count", "4"), 0, "5\n", "");
        check(run("compact", path), 0, "compacted: 2 records, 1 keys, 38 -> 21 bytes\n", "");
        Result get = run("get", path, "count");
        assertEquals(0, get.code);
        assertArrayEquals(valueBytes("5"), get.out);
        assertEquals("", get.errText());
        check(run("dump", path), 0, "4 put count 1\n", "");
    }

    @Test
    void readmeDocumentsUsageAndCounterSemantics() throws IOException {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8).replace("\r\n", "\n");
        String usage = "       logkv incr FILE KEY [DELTA]\n";
        assertTrue(readme.contains(usage));
        assertTrue(readme.contains(Report.usage()));

        String counters = "## Counters\n\n"
                + "`incr` keeps a counter in a key. The value is the decimal digits of a signed 64-bit integer; "
                + "a key that does not exist counts as 0. `DELTA` is the amount to add, a whole number such as "
                + "`5` or `-20` (default 1), and `incr` prints the new value. A negative `DELTA` is written "
                + "as it is, without `--`.\n\n"
                + "```\n"
                + "logkv incr visits.kv hits\n"
                + "logkv incr visits.kv hits\n"
                + "logkv incr visits.kv hits 5\n"
                + "logkv incr visits.kv balance -20\n"
                + "```\n\n"
                + "print\n\n"
                + "```\n"
                + "1\n2\n7\n-20\n"
                + "```\n\n"
                + "Every `incr` appends one `put` record, also when `DELTA` is 0. A value that is not a whole number "
                + "(`abc`, `1.5`, an empty value, a number with a plus sign or leading zeros) and a result outside "
                + "the 64-bit range are errors with exit code 2 and the file stays as it is. Like `put`, `incr` "
                + "creates a missing file and refuses to work on a damaged one until you run `repair`.\n";
        assertTrue(readme.contains(counters));
        assertTrue(readme.indexOf("## Counters") < readme.indexOf("## File format"));
    }
}
