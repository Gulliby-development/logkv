package io.github.gulliby.logkv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DocsTest {
    private static String readme;

    @BeforeAll
    static void load() throws IOException {
        readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"## Install", "## Usage", "## File format", "## Recovery", "## Exit codes",
        "## Development"})
    void hasHeading(String heading) {
        assertTrue(readme.contains("\n" + heading + "\n"), heading);
    }

    @Test
    void hasUsageLines() {
        String usage = "usage: logkv put FILE KEY VALUE\n"
                + "       logkv get FILE KEY\n"
                + "       logkv del FILE KEY\n"
                + "       logkv incr FILE KEY [DELTA]\n"
                + "       logkv list FILE [--prefix PREFIX] [--count]\n"
                + "       logkv stats|compact|verify|repair|dump FILE\n";
        assertTrue(readme.contains(usage));
    }

    @Test
    void usageMatchesReport() {
        assertTrue(readme.contains(Report.usage()));
    }

    @Test
    void hasHexLine() {
        assertTrue(readme.contains("4b564c31 be0a2fa5 01 0005 00000003 616c706861 6f6e65\n"));
    }

    @Test
    void hasStatsOutput() {
        String stats = "keys: 2\nrecords: 5\nfile: 95 bytes\nlive: 43 bytes\ngarbage: 52 bytes (55%)\n";
        assertTrue(readme.contains(stats));
        assertEquals(stats, Report.stats(2, 5, 95, 43));
    }

    @Test
    void hasDumpOutput() {
        String dump = "4 put alpha 3\n23 put beta 3\n41 put alpha 3\n60 del beta\n75 put gamma 4\n";
        assertTrue(readme.contains(dump));
    }

    @Test
    void hasBuildAndRunCommands() {
        assertTrue(readme.contains("mvn -B -q verify"));
        assertTrue(readme.contains("java -cp target/classes io.github.gulliby.logkv.Main"));
    }

    @Test
    void mentionsNoLocking() {
        assertTrue(readme.contains("no locking"));
    }
}
