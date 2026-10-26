package io.github.gulliby.logkv;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ListCountTest {
    private static final String USAGE = "usage: logkv put FILE KEY VALUE\n"
            + "       logkv get FILE KEY\n"
            + "       logkv del FILE KEY\n"
            + "       logkv incr FILE KEY [DELTA]\n"
            + "       logkv list FILE [--prefix PREFIX] [--count]\n"
            + "       logkv stats|compact|verify|repair|dump FILE\n";

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
        return f;
    }

    @Test
    void countPrintsHowManyKeysListWouldPrint() {
        String f = demo();
        check(run("list", f), 0, "alpha\ngamma\n", "");
        check(run("list", f, "--count"), 0, "2\n", "");
    }

    @Test
    void countWorksWithPrefixInEitherOrder() {
        String f = demo();
        check(run("list", f, "--prefix", "a", "--count"), 0, "1\n", "");
        check(run("list", f, "--count", "--prefix", "a"), 0, "1\n", "");
        check(run("list", f, "--prefix=g", "--count"), 0, "1\n", "");
        check(run("list", f, "--count", "--prefix="), 0, "2\n", "");
        check(run("list", "--count", f), 0, "2\n", "");
    }

    @Test
    void countIsZeroWhenNothingMatchesOrTheStoreIsEmpty() throws Exception {
        String f = demo();
        check(run("list", f, "--prefix", "zz", "--count"), 0, "0\n", "");
        // list EMPTY --count, where EMPTY is a file of 0 bytes
        Path empty = tmp.resolve("empty.kv");
        Files.write(empty, new byte[0]);
        check(run("list", empty.toString(), "--count"), 0, "0\n", "");
    }

    @Test
    void countMayBeRepeated() {
        String f = demo();
        check(run("list", f, "--count", "--count"), 0, "2\n", "");
    }

    @ParameterizedTest
    @ValueSource(strings = {"--count=1", "--Count", "--counts", "-c"})
    void otherSpellingsAreUnknownOptions(String spelling) {
        String f = demo();
        check(run("list", f, spelling), 2, "", "logkv: unknown option \"" + spelling + "\"\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"get", "del", "put", "incr"})
    void countIsAnOptionOfListOnly(String command) {
        String f = demo();
        check(run(command, f, "alpha", "--count"), 2, "", "logkv: unknown option \"--count\"\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"stats", "dump", "verify", "repair", "compact"})
    void countIsRefusedForTheCommandsWithoutKeys(String command) {
        String f = demo();
        check(run(command, f, "--count"), 2, "", "logkv: unknown option \"--count\"\n");
    }

    @Test
    void afterDoubleDashCountIsAnOperand() {
        String f = demo();
        check(run("list", f, "--", "--count"), 2, "", "logkv: too many arguments\n");
    }

    @Test
    void countDoesNotChangeTheFile() throws Exception {
        String f = demo();
        byte[] before = Files.readAllBytes(Path.of(f));
        check(run("list", f, "--count"), 0, "2\n", "");
        assertArrayEquals(before, Files.readAllBytes(Path.of(f)));
    }

    @Test
    void countOfADamagedFileCountsTheValidPartAndWarns() throws Exception {
        String f = demo();
        Files.write(Path.of(f), new byte[3], StandardOpenOption.APPEND);
        Result r = run("list", f, "--count");
        assertEquals(0, r.code);
        assertEquals("2\n", r.stdout);
        assertTrue(r.stderr.startsWith("logkv: warning: "));
        assertTrue(r.stderr.endsWith(
                ": 3 bytes after offset 95 are corrupt (incomplete record); run repair\n"));
    }

    @Test
    void helpShowsTheOption() {
        check(run("--help"), 0, USAGE, "");
    }
}
