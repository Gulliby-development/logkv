package io.github.gulliby.logkv;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class CliTest {
    private static final byte[] MAGIC = "KVL1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NONE = new byte[0];
    private static final String USAGE = "usage: logkv put FILE KEY VALUE\n"
            + "       logkv get FILE KEY\n"
            + "       logkv del FILE KEY\n"
            + "       logkv incr FILE KEY [DELTA]\n"
            + "       logkv list FILE [--prefix PREFIX] [--count]\n"
            + "       logkv stats|compact|verify|repair|dump FILE\n";
    private static final String DEMO_DUMP = "4 put alpha 3\n23 put beta 3\n41 put alpha 3\n60 del beta\n75 put gamma 4\n";
    private static final String TAIL_WARNING_SUFFIX =
            ": 3 bytes after offset 95 are corrupt (incomplete record); run repair\n";

    @TempDir
    Path tmp;

    private static final class Res {
        final int code;
        final byte[] out;
        final byte[] err;

        Res(int code, byte[] out, byte[] err) {
            this.code = code;
            this.out = out;
            this.err = err;
        }

        String out() {
            return new String(out, StandardCharsets.UTF_8);
        }

        String err() {
            return new String(err, StandardCharsets.UTF_8);
        }
    }

    private static final class FailingStream extends InputStream {
        @Override
        public int read() throws IOException {
            throw new IOException("boom");
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            throw new IOException("boom");
        }
    }

    private static Res run(InputStream in, String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        PrintStream po = new PrintStream(o, false, StandardCharsets.UTF_8);
        PrintStream pe = new PrintStream(e, false, StandardCharsets.UTF_8);
        int code = Cli.run(args, in, po, pe);
        po.flush();
        pe.flush();
        return new Res(code, o.toByteArray(), e.toByteArray());
    }

    private static Res run(String... args) {
        return run(new ByteArrayInputStream(NONE), args);
    }

    private static Res runStdin(byte[] stdin, String... args) {
        return run(new ByteArrayInputStream(stdin), args);
    }

    private static void lineClean(byte[] b) {
        for (byte x : b) {
            assertTrue(x != 0x0D, "CR byte in output");
        }
        if (b.length >= 3) {
            assertFalse(b[0] == (byte) 0xEF && b[1] == (byte) 0xBB && b[2] == (byte) 0xBF, "BOM");
        }
        if (b.length > 0) {
            assertTrue(b[b.length - 1] == 0x0A, "line does not end with LF");
        }
    }

    private static void check(Res r, int code, String out, String err) {
        assertEquals(code, r.code);
        assertEquals(out, r.out());
        assertEquals(err, r.err());
        lineClean(r.out);
        lineClean(r.err);
    }

    private static byte[] rec(int type, String key, byte[] value) {
        byte[] k = key.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer body = ByteBuffer.allocate(7 + k.length + value.length);
        body.put((byte) type).putShort((short) k.length).putInt(value.length).put(k).put(value);
        CRC32 crc = new CRC32();
        crc.update(body.array());
        ByteBuffer all = ByteBuffer.allocate(4 + body.capacity());
        all.putInt((int) crc.getValue()).put(body.array());
        return all.array();
    }

    private static byte[] put(String key, String value) {
        return rec(1, key, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.writeBytes(p);
        }
        return o.toByteArray();
    }

    private static String disp(Path p) {
        return p.toString().replace('\\', '/');
    }

    private static String[] args(String spec, Path f) {
        String[] parts = spec.split(" ");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("F")) {
                parts[i] = f.toString();
            }
        }
        return parts;
    }

    private static byte[] demoBytes() {
        return cat(MAGIC, put("alpha", "one"), put("beta", "two"), put("alpha", "uno"),
                rec(2, "beta", NONE), put("gamma", "tres"));
    }

    private Path demo() {
        Path f = tmp.resolve("demo.kv");
        String p = f.toString();
        assertEquals(0, run("put", p, "alpha", "one").code);
        assertEquals(0, run("put", p, "beta", "two").code);
        assertEquals(0, run("put", p, "alpha", "uno").code);
        assertEquals(0, run("del", p, "beta").code);
        assertEquals(0, run("put", p, "gamma", "tres").code);
        return f;
    }

    private Path damaged() throws IOException {
        Path f = demo();
        Files.write(f, new byte[] {0, 1, 2}, StandardOpenOption.APPEND);
        return f;
    }

    // ---- usage and command errors

    @Test
    void helpPrintsUsage() {
        check(run("--help"), 0, USAGE, "");
    }

    @Test
    void noArgumentsIsMissingCommand() {
        check(run(), 2, "",
                "logkv: missing command (put, get, del, incr, list, stats, compact, verify, repair or dump)\n");
    }

    @Test
    void unknownCommand() {
        check(run("frob"), 2, "", "logkv: unknown command \"frob\"\n");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"--help put", "list F --help"})
    void helpWithOtherArgumentsIsUnknownOption(String spec) {
        check(run(args(spec, tmp.resolve("f.kv"))), 2, "", "logkv: unknown option \"--help\"\n");
    }

    // ---- argument errors

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "get|missing FILE",
        "get --bogus|unknown option \"--bogus\"",
        "get F|missing KEY",
        "put F k|missing VALUE",
        "stats F extra|too many arguments",
        "get F a b|too many arguments",
        "list F --prefix|--prefix needs a value",
        "stats --prefix=a F|unknown option \"--prefix=a\"",
        "get -x F k|unknown option \"-x\""
    })
    void argumentErrors(String spec, String message) {
        Path f = tmp.resolve("f.kv");
        check(run(args(spec, f)), 2, "", "logkv: " + message + "\n");
        assertFalse(Files.exists(f));
    }

    @Test
    void invalidKeyOnGet() {
        check(run("get", "f.kv", "a b"), 2, "",
                "logkv: invalid key \"a b\" (expected 1 to 255 printable ASCII characters, no spaces)\n");
    }

    @Test
    void invalidKeyOnPutDoesNotCreateFile() {
        Path f = tmp.resolve("n.kv");
        check(run("put", f.toString(), "a b", "v"), 2, "",
                "logkv: invalid key \"a b\" (expected 1 to 255 printable ASCII characters, no spaces)\n");
        assertFalse(Files.exists(f));
    }

    @Test
    void keyOf256CharactersIsInvalid() {
        String key = "k".repeat(256);
        check(run("get", "f.kv", key), 2, "",
                "logkv: invalid key \"" + key
                        + "\" (expected 1 to 255 printable ASCII characters, no spaces)\n");
    }

    @Test
    void invalidPrefix() {
        check(run("list", "f.kv", "--prefix", "a b"), 2, "",
                "logkv: invalid prefix \"a b\" (expected printable ASCII characters, no spaces)\n");
    }

    @Test
    void acceptedFormsAfterDoubleDash() {
        Path f = tmp.resolve("d.kv");
        String p = f.toString();
        assertEquals(0, run("put", p, "--", "-k", "dash").code);
        assertEquals(0, run("put", p, "--", "-x1", "x").code);
        assertEquals(0, run("put", p, "a", "1").code);
        assertEquals(0, run("put", p, "b", "2").code);
        Res g = run("get", p, "--", "-k");
        assertEquals(0, g.code);
        assertArrayEquals("dash".getBytes(StandardCharsets.UTF_8), g.out);
        check(run("list", p, "--prefix", "-x"), 0, "-x1\n", "");
        check(run("list", p, "--prefix=a", "--prefix", "b"), 0, "b\n", "");
        check(run("list", p, "--prefix="), 0, "-k\n-x1\na\nb\n", "");
    }

    // ---- put

    @Test
    void putCreatesFileWithExactBytes() throws IOException {
        Path f = tmp.resolve("demo.kv");
        check(run("put", f.toString(), "alpha", "one"), 0, "", "");
        byte[] actual = Files.readAllBytes(f);
        assertEquals(23, actual.length);
        assertArrayEquals(HexFormat.of().parseHex("4b564c31be0a2fa501000500000003616c7068616f6e65"), actual);
        assertArrayEquals(cat(MAGIC, put("alpha", "one")), actual);
    }

    @Test
    void putStoresUtf8ValueBytes() throws IOException {
        Path f = tmp.resolve("u.kv");
        check(run("put", f.toString(), "k", "h\u00e9llo"), 0, "", "");
        byte[] value = "h\u00e9llo".getBytes(StandardCharsets.UTF_8);
        assertEquals(6, value.length);
        assertArrayEquals(cat(MAGIC, rec(1, "k", value)), Files.readAllBytes(f));
    }

    @Test
    void putEmptyValue() throws IOException {
        Path f = tmp.resolve("e.kv");
        check(run("put", f.toString(), "key", ""), 0, "", "");
        byte[] actual = Files.readAllBytes(f);
        assertEquals(4 + 11 + 3, actual.length);
        assertArrayEquals(cat(MAGIC, rec(1, "key", NONE)), actual);
    }

    @Test
    void putReadsBinaryValueFromStdin() throws IOException {
        Path f = tmp.resolve("b.kv");
        byte[] bin = {0x00, (byte) 0xff, 0x0a, 0x0d, 0x1a};
        check(runStdin(bin, "put", f.toString(), "bin", "-"), 0, "", "");
        assertArrayEquals(cat(MAGIC, rec(1, "bin", bin)), Files.readAllBytes(f));
        Res g = run("get", f.toString(), "bin");
        assertEquals(0, g.code);
        assertArrayEquals(bin, g.out);
        assertEquals("", g.err());
    }

    @Test
    void putAcceptsValueOfLimitSize() throws IOException {
        Path f = tmp.resolve("big.kv");
        byte[] big = new byte[1048576];
        java.util.Arrays.fill(big, (byte) 'x');
        check(runStdin(big, "put", f.toString(), "k", "-"), 0, "", "");
        byte[] actual = Files.readAllBytes(f);
        assertEquals(4 + 11 + 1 + 1048576, actual.length);
        assertArrayEquals(cat(MAGIC, rec(1, "k", big)), actual);
    }

    @Test
    void putRejectsValueOneByteOverLimit() {
        Path f = tmp.resolve("over.kv");
        check(runStdin(new byte[1048577], "put", f.toString(), "k", "-"), 2, "",
                "logkv: value too large (limit 1048576 bytes)\n");
        assertFalse(Files.exists(f));
    }

    @Test
    void putRejectedValueLeavesExistingFileUnchanged() throws IOException {
        Path f = demo();
        byte[] before = Files.readAllBytes(f);
        check(runStdin(new byte[1048577], "put", f.toString(), "k", "-"), 2, "",
                "logkv: value too large (limit 1048576 bytes)\n");
        assertArrayEquals(before, Files.readAllBytes(f));
    }

    @Test
    void putWithFailingStdin() {
        Path f = tmp.resolve("fail.kv");
        check(run(new FailingStream(), "put", f.toString(), "k", "-"), 2, "",
                "logkv: cannot read standard input\n");
        assertFalse(Files.exists(f));
    }

    @Test
    void putAcceptsKeyOf255Characters() throws IOException {
        Path f = tmp.resolve("k.kv");
        String key = "k".repeat(255);
        check(run("put", f.toString(), key, "v"), 0, "", "");
        assertArrayEquals(cat(MAGIC, put(key, "v")), Files.readAllBytes(f));
        Res g = run("get", f.toString(), key);
        assertEquals(0, g.code);
        assertArrayEquals("v".getBytes(StandardCharsets.UTF_8), g.out);
    }

    // ---- get

    @Test
    void getWritesRawValueWithoutLineEnd() {
        Path f = demo();
        Res r = run("get", f.toString(), "alpha");
        assertEquals(0, r.code);
        assertArrayEquals("uno".getBytes(StandardCharsets.UTF_8), r.out);
        assertEquals("", r.err());
    }

    @Test
    void getUnknownKey() {
        Path f = demo();
        check(run("get", f.toString(), "beta"), 1, "", "logkv: key not found: beta\n");
    }

    // ---- del

    @Test
    void delAppendsOneDeleteRecord() throws IOException {
        Path f = tmp.resolve("d.kv");
        run("put", f.toString(), "a", "1");
        byte[] before = Files.readAllBytes(f);
        check(run("del", f.toString(), "a"), 0, "", "");
        assertArrayEquals(cat(before, rec(2, "a", NONE)), Files.readAllBytes(f));
    }

    @Test
    void delOfMissingKeyLeavesFileUnchanged() throws IOException {
        Path f = tmp.resolve("d.kv");
        run("put", f.toString(), "a", "1");
        run("del", f.toString(), "a");
        byte[] before = Files.readAllBytes(f);
        check(run("del", f.toString(), "a"), 1, "", "logkv: key not found: a\n");
        assertArrayEquals(before, Files.readAllBytes(f));
    }

    @Test
    void delOnMissingFileDoesNotCreateIt() {
        Path f = tmp.resolve("missing.kv");
        check(run("del", f.toString(), "a"), 2, "", "logkv: " + disp(f) + ": cannot read the file\n");
        assertFalse(Files.exists(f));
    }

    // ---- list

    private Path orderedStore() {
        Path f = tmp.resolve("o.kv");
        for (String k : new String[] {"b", "B", "a", "A", "_", "ab"}) {
            assertEquals(0, run("put", f.toString(), k, "1").code);
        }
        return f;
    }

    @Test
    void listIsInUnsignedByteOrder() {
        check(run("list", orderedStore().toString()), 0, "A\nB\n_\na\nab\nb\n", "");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "--prefix a|a,ab",
        "--prefix=a|a,ab",
        "--prefix zz|",
        "--prefix=|A,B,_,a,ab,b",
        "--prefix _|_",
        "--prefix ab|ab"
    })
    void listWithPrefix(String prefixArgs, String expectedKeys) {
        String f = orderedStore().toString();
        StringBuilder expected = new StringBuilder();
        if (expectedKeys != null) {
            for (String k : expectedKeys.split(",")) {
                expected.append(k).append('\n');
            }
        }
        String[] a = prefixArgs.split(" ");
        String[] all = new String[a.length + 2];
        all[0] = "list";
        all[1] = f;
        System.arraycopy(a, 0, all, 2, a.length);
        check(run(all), 0, expected.toString(), "");
    }

    // ---- stats

    @Test
    void statsOnWorkedExample() {
        check(run("stats", demo().toString()), 0,
                "keys: 2\nrecords: 5\nfile: 95 bytes\nlive: 43 bytes\ngarbage: 52 bytes (55%)\n", "");
    }

    @ParameterizedTest
    @CsvSource({"52,95,55", "1,200,1", "1,201,0", "0,4,0", "0,0,0", "3,6,50"})
    void percentageRounding(long garbage, long file, long expected) {
        assertEquals(expected, Report.percent(garbage, file));
    }

    @Test
    void statsCountsIgnoredTail() throws IOException {
        Path f = damaged();
        check(run("stats", f.toString()), 0,
                "keys: 2\nrecords: 5\nfile: 98 bytes\nlive: 43 bytes\ngarbage: 55 bytes (56%)\n",
                "logkv: warning: " + disp(f) + TAIL_WARNING_SUFFIX);
    }

    // ---- compact

    @Test
    void compactWorkedExample() throws IOException {
        Path f = damaged();
        check(run("repair", f.toString()), 0, "repaired: removed 3 bytes at offset 95\n", "");
        assertEquals(95, Files.size(f));
        check(run("verify", f.toString()), 0, "ok: 5 records, 2 keys\n", "");
        check(run("compact", f.toString()), 0, "compacted: 5 records, 2 keys, 95 -> 43 bytes\n", "");
        assertArrayEquals(cat(MAGIC, put("alpha", "uno"), put("gamma", "tres")), Files.readAllBytes(f));
        assertFalse(Files.exists(tmp.resolve("demo.kv.compact")));
        check(run("dump", f.toString()), 0, "4 put alpha 3\n23 put gamma 4\n", "");
    }

    @Test
    void compactWithNoLiveKeysLeavesMagicOnly() throws IOException {
        Path f = tmp.resolve("z.kv");
        run("put", f.toString(), "a", "1");
        run("del", f.toString(), "a");
        assertEquals(29, Files.size(f));
        check(run("compact", f.toString()), 0, "compacted: 2 records, 0 keys, 29 -> 4 bytes\n", "");
        assertArrayEquals(MAGIC, Files.readAllBytes(f));
    }

    @Test
    void compactOverwritesStaleCompactFile() throws IOException {
        Path f = demo();
        Path stale = tmp.resolve("demo.kv.compact");
        Files.writeString(stale, "stale junk");
        check(run("compact", f.toString()), 0, "compacted: 5 records, 2 keys, 95 -> 43 bytes\n", "");
        assertFalse(Files.exists(stale));
    }

    // ---- verify, repair, dump

    @Test
    void verifyIntactAndDamaged() throws IOException {
        Path f = demo();
        check(run("verify", f.toString()), 0, "ok: 5 records, 2 keys\n", "");
        Files.write(f, new byte[] {0, 1, 2}, StandardOpenOption.APPEND);
        check(run("verify", f.toString()), 1,
                "corrupt: offset 95: incomplete record, 3 bytes ignored\n", "");
    }

    private Path compactedWithLastByteChanged() throws IOException {
        Path f = demo();
        assertEquals(0, run("compact", f.toString()).code);
        byte[] b = Files.readAllBytes(f);
        assertEquals(43, b.length);
        b[b.length - 1] ^= (byte) 0xFF;
        Files.write(f, b);
        return f;
    }

    @Test
    void verifyDetectsBadChecksum() throws IOException {
        Path f = compactedWithLastByteChanged();
        check(run("verify", f.toString()), 1, "corrupt: offset 23: bad checksum, 20 bytes ignored\n", "");
    }

    @Test
    void repairTruncatesBadChecksumTail() throws IOException {
        Path f = compactedWithLastByteChanged();
        check(run("repair", f.toString()), 0, "repaired: removed 20 bytes at offset 23\n", "");
        assertEquals(23, Files.size(f));
        check(run("verify", f.toString()), 0, "ok: 1 records, 1 keys\n", "");
    }

    @Test
    void repairOnIntactFileChangesNothing() throws IOException {
        Path f = demo();
        check(run("repair", f.toString()), 0, "ok: nothing to repair\n", "");
        assertArrayEquals(demoBytes(), Files.readAllBytes(f));
    }

    @Test
    void dumpWorkedExample() {
        check(run("dump", demo().toString()), 0, DEMO_DUMP, "");
    }

    // ---- ignored tail: warnings and refusals

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "list F|alpha\\ngamma\\n",
        "get F alpha|uno",
        "dump F|DUMP"
    })
    void readCommandsWarnOnIgnoredTail(String spec, String out) throws IOException {
        Path f = damaged();
        String expected = out.equals("DUMP") ? DEMO_DUMP : out.replace("\\n", "\n");
        Res r = run(args(spec, f));
        assertEquals(0, r.code);
        assertEquals(expected, r.out());
        assertEquals("logkv: warning: " + disp(f) + TAIL_WARNING_SUFFIX, r.err());
        lineClean(r.err);
    }

    @Test
    void getMissingKeyOnTailPrintsWarningThenNotFound() throws IOException {
        Path f = damaged();
        check(run("get", f.toString(), "beta"), 1, "",
                "logkv: warning: " + disp(f) + TAIL_WARNING_SUFFIX + "logkv: key not found: beta\n");
    }

    @ParameterizedTest
    @CsvSource({"put F x y", "del F alpha", "del F nope", "compact F"})
    void writeCommandsRefuseIgnoredTail(String spec) throws IOException {
        Path f = damaged();
        byte[] before = Files.readAllBytes(f);
        check(run(args(spec, f)), 2, "",
                "logkv: " + disp(f) + ": corrupt at offset 95: incomplete record (run repair first)\n");
        assertArrayEquals(before, Files.readAllBytes(f));
    }

    // ---- file errors

    @ParameterizedTest
    @CsvSource({"get F a", "list F", "stats F", "dump F", "verify F", "repair F", "compact F", "del F a"})
    void missingFileCannotBeRead(String spec) {
        Path f = tmp.resolve("missing.kv");
        check(run(args(spec, f)), 2, "", "logkv: " + disp(f) + ": cannot read the file\n");
        assertFalse(Files.exists(f));
    }

    @ParameterizedTest
    @CsvSource({"put F k v", "get F k", "list F", "stats F", "dump F", "verify F", "repair F", "compact F", "del F k"})
    void directoryCannotBeRead(String spec) {
        check(run(args(spec, tmp)), 2, "", "logkv: " + disp(tmp) + ": cannot read the file\n");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"hello|get F a", "KV|get F a", "hello|put F a b", "KV|verify F"})
    void notALogkvFile(String content, String spec) throws IOException {
        Path f = tmp.resolve("x.kv");
        Files.writeString(f, content);
        check(run(args(spec, f)), 2, "", "logkv: " + disp(f) + ": not a logkv file\n");
        assertArrayEquals(content.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(f));
    }

    @Test
    void putIntoMissingParentCannotWrite() {
        Path f = tmp.resolve("nodir").resolve("x.kv");
        check(run("put", f.toString(), "k", "v"), 2, "",
                "logkv: " + disp(f) + ": cannot write the file\n");
        assertFalse(Files.exists(f));
    }

    @Test
    void backslashInPathIsShownAsSlash() {
        check(run("get", "sub\\missing.kv", "a"), 2, "", "logkv: sub/missing.kv: cannot read the file\n");
    }

    // ---- 0-byte file

    static Stream<Arguments> emptyFileCases() {
        String nf = "logkv: key not found: a\n";
        return Stream.of(
                Arguments.of("get F a", "", nf, 1, NONE),
                Arguments.of("del F a", "", nf, 1, NONE),
                Arguments.of("list F", "", "", 0, NONE),
                Arguments.of("stats F",
                        "keys: 0\nrecords: 0\nfile: 0 bytes\nlive: 0 bytes\ngarbage: 0 bytes (0%)\n", "", 0, NONE),
                Arguments.of("verify F", "ok: 0 records, 0 keys\n", "", 0, NONE),
                Arguments.of("repair F", "ok: nothing to repair\n", "", 0, NONE),
                Arguments.of("dump F", "", "", 0, NONE),
                Arguments.of("compact F", "compacted: 0 records, 0 keys, 0 -> 4 bytes\n", "", 0, MAGIC),
                Arguments.of("put F a 1", "", "", 0, cat(MAGIC, put("a", "1"))));
    }

    @ParameterizedTest
    @MethodSource("emptyFileCases")
    void zeroByteFileIsAnEmptyStore(String spec, String out, String err, int code, byte[] fileAfter)
            throws IOException {
        Path f = tmp.resolve("e.kv");
        Files.write(f, NONE);
        check(run(args(spec, f)), code, out, err);
        assertArrayEquals(fileAfter, Files.readAllBytes(f));
    }

    // ---- worked example and output hygiene

    @Test
    void workedExampleEndToEnd() throws IOException {
        Path f = demo();
        byte[] actual = Files.readAllBytes(f);
        assertEquals(95, actual.length);
        assertArrayEquals(demoBytes(), actual);
        String p = f.toString();
        check(run("list", p), 0, "alpha\ngamma\n", "");
        assertArrayEquals("uno".getBytes(StandardCharsets.UTF_8), run("get", p, "alpha").out);
        check(run("get", p, "beta"), 1, "", "logkv: key not found: beta\n");
        check(run("verify", p), 0, "ok: 5 records, 2 keys\n", "");
    }

    @Test
    void outputsHaveNoCrNoBomAndEndWithLf() throws IOException {
        Path f = damaged();
        String p = f.toString();
        String[][] commands = {{"--help"}, {"list", p}, {"stats", p}, {"dump", p}, {"verify", p},
            {"get", p, "nope"}, {"put", p, "x", "y"}, {"frob"}, {}};
        for (String[] c : commands) {
            Res r = run(c);
            lineClean(r.out);
            lineClean(r.err);
        }
    }
}
