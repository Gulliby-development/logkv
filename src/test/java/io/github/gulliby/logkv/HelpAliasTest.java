package io.github.gulliby.logkv;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

class HelpAliasTest {
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
        final byte[] stdout;
        final byte[] stderr;

        Result(int code, byte[] stdout, byte[] stderr) {
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
        return new Result(code, stdout.toByteArray(), stderr.toByteArray());
    }

    private static void assertResult(Result result, int code, String stdout, String stderr) {
        assertEquals(code, result.code);
        assertArrayEquals(stdout.getBytes(StandardCharsets.UTF_8), result.stdout);
        assertArrayEquals(stderr.getBytes(StandardCharsets.UTF_8), result.stderr);
    }

    @ParameterizedTest
    @ValueSource(strings = {"--help", "-h", "help"})
    void eachHelpSpellingPrintsExactUsage(String spelling) {
        assertResult(run(spelling), 0, USAGE, "");
    }

    @Test
    void shortHelpWithAnotherArgumentIsUnknownOption() {
        assertResult(run("-h", "put"), 2, "", "logkv: unknown option \"-h\"\n");
    }

    @Test
    void helpWithAnotherArgumentIsUnknownCommand() {
        assertResult(run("help", "put"), 2, "", "logkv: unknown command \"help\"\n");
    }

    @Test
    void shortHelpAfterGetAndListIsUnknownOption() {
        KvException get = assertThrows(KvException.class, () -> Options.parse(new String[] {"get", "FILE", "-h"}));
        assertEquals("unknown option \"-h\"", get.getMessage());

        KvException list = assertThrows(KvException.class, () -> Options.parse(new String[] {"list", "FILE", "-h"}));
        assertEquals("unknown option \"-h\"", list.getMessage());
    }

    @Test
    void helpIsStoredAsAKey() {
        Path file = tmp.resolve("store.kv");
        assertResult(run("put", file.toString(), "help", "v"), 0, "", "");
        assertResult(run("get", file.toString(), "help"), 0, "v", "");
    }

    @Test
    void readmeDocumentsHelpAliasesImmediatelyBeforeCounters() throws Exception {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8).replace("\r\n", "\n");
        String section = "## Help\n\n"
                + "`logkv --help`, `logkv -h` and `logkv help` each print the usage text above and exit with 0 "
                + "when they are the only argument. Anywhere else `-h` is an unknown option "
                + "(`logkv: unknown option \"-h\"`, exit 2) and `help` is an unknown command. "
                + "The usage text itself is unchanged.\n\n"
                + "## Counters\n";
        assertTrue(readme.contains(section));
    }
}
