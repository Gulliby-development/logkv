package io.github.gulliby.logkv;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ListLimitTest {
    @TempDir
    Path tmp;

    private static final class Result {
        final int code;
        final String stdout;
        final String stderr;

        Result(int code, String stdout, String stderr) {
            this.code = code;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    private static Result run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(stdout, false, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(stderr, false, StandardCharsets.UTF_8);
        int code = Cli.run(args, new ByteArrayInputStream(new byte[0]), out, err);
        out.flush();
        err.flush();
        return new Result(code, new String(stdout.toByteArray(), StandardCharsets.UTF_8),
                new String(stderr.toByteArray(), StandardCharsets.UTF_8));
    }

    private static void check(Result r, int code, String stdout, String stderr) {
        assertEquals(code, r.code);
        assertEquals(stdout, r.stdout);
        assertEquals(stderr, r.stderr);
    }

    private String demo() {
        String f = tmp.resolve("demo.kv").toString();
        assertEquals(0, run("put", f, "alpha", "one").code);
        assertEquals(0, run("put", f, "beta", "two").code);
        assertEquals(0, run("put", f, "alpha", "uno").code);
        assertEquals(0, run("del", f, "beta").code);
        assertEquals(0, run("put", f, "gamma", "four").code);
        assertEquals(0, run("put", f, "apple", "five").code);
        return f;
    }

    @Test
    void limitStopsAfterTheFirstMatchingKeys() {
        String f = demo();
        check(run("list", f), 0, "alpha\napple\ngamma\n", "");
        check(run("list", f, "--limit", "1"), 0, "alpha\n", "");
        check(run("list", f, "--limit", "2"), 0, "alpha\napple\n", "");
        check(run("list", f, "--limit=3"), 0, "alpha\napple\ngamma\n", "");
        check(run("list", f, "--limit", "1000000"), 0, "alpha\napple\ngamma\n", "");
    }

    @Test
    void limitCountsOnlyTheKeysThatMatchThePrefix() {
        String f = demo();
        check(run("list", f, "--prefix", "a", "--limit", "2"), 0, "alpha\napple\n", "");
        check(run("list", f, "--limit", "1", "--prefix", "g"), 0, "gamma\n", "");
        check(run("list", f, "--prefix=zz", "--limit", "5"), 0, "", "");
    }

    @Test
    void countIsAtMostTheLimit() {
        String f = demo();
        check(run("list", f, "--limit", "2", "--count"), 0, "2\n", "");
        check(run("list", f, "--count", "--limit", "9"), 0, "3\n", "");
        check(run("list", f, "--prefix", "g", "--limit", "5", "--count"), 0, "1\n", "");
        check(run("list", f, "--prefix", "zz", "--limit", "5", "--count"), 0, "0\n", "");
    }

    @Test
    void theLastLimitWins() {
        String f = demo();
        check(run("list", f, "--limit", "1", "--limit", "2"), 0, "alpha\napple\n", "");
        check(run("list", f, "--limit=3", "--limit", "1"), 0, "alpha\n", "");
        check(run("list", "--limit", "1", f), 0, "alpha\n", "");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1000001", "-1", "+1", "01", "00", "1.5", "abc", "", " 1", "1 ", "99999999999999999999", "\u0663"})
    void anyOtherLimitIsRefused(String text) {
        String f = demo();
        check(run("list", f, "--limit", text), 2, "", "logkv: invalid limit \"" + text
                + "\" (expected a whole number from 1 to 1000000)\n");
    }

    @Test
    void aMissingLimitValueIsRefused() {
        String f = demo();
        check(run("list", f, "--limit"), 2, "", "logkv: --limit needs a value\n");
    }

    @Test
    void anInvalidPrefixIsReportedBeforeAnInvalidLimit() {
        String f = demo();
        check(run("list", f, "--limit", "0", "--prefix", "a b"), 2, "", "logkv: invalid prefix \"a b\" "
                + "(expected printable ASCII characters, no spaces)\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"get", "del", "put", "incr"})
    void limitIsAnOptionOfListOnly(String command) {
        String f = demo();
        check(run(command, f, "alpha", "--limit", "1"), 2, "", "logkv: unknown option \"--limit\"\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"--LIMIT=1", "--limits", "-l", "--limit1"})
    void otherSpellingsAreUnknownOptions(String spelling) {
        String f = demo();
        check(run("list", f, spelling), 2, "", "logkv: unknown option \"" + spelling + "\"\n");
    }

    @Test
    void afterDoubleDashLimitIsAnOperand() {
        String f = demo();
        check(run("list", f, "--", "--limit"), 2, "", "logkv: too many arguments\n");
    }

    @Test
    void limitDoesNotChangeTheFile() throws Exception {
        String f = demo();
        byte[] before = Files.readAllBytes(Path.of(f));
        check(run("list", f, "--limit", "1"), 0, "alpha\n", "");
        assertArrayEquals(before, Files.readAllBytes(Path.of(f)));
    }

    @Test
    void limitOnADamagedFileListsTheValidPartAndWarns() throws Exception {
        String f = demo();
        Files.write(Path.of(f), new byte[] {1, 2, 3}, java.nio.file.StandardOpenOption.APPEND);
        Result r = run("list", f, "--limit", "2");
        assertEquals(0, r.code);
        assertEquals("alpha\napple\n", r.stdout);
        assertEquals(true, r.stderr.startsWith("logkv: warning: "));
    }
}
