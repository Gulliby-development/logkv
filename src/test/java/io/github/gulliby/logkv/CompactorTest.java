package io.github.gulliby.logkv;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompactorTest {
    @TempDir
    Path dir;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] expected(int type, String key, String value) {
        byte[] k = bytes(key);
        byte[] v = bytes(value);
        ByteBuffer body = ByteBuffer.allocate(7 + k.length + v.length);
        body.put((byte) type);
        body.putShort((short) k.length);
        body.putInt(v.length);
        body.put(k);
        body.put(v);
        CRC32 crc = new CRC32();
        crc.update(body.array());
        ByteBuffer out = ByteBuffer.allocate(4 + body.capacity());
        out.putInt((int) crc.getValue());
        out.put(body.array());
        return out.array();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    private Store open(Path file) {
        return Store.load(file, "demo.kv", true);
    }

    private Path worked() {
        Path file = dir.resolve("demo.kv");
        Store s = open(file);
        s.append(Record.put("alpha", bytes("one")));
        s.append(Record.put("beta", bytes("two")));
        s.append(Record.put("alpha", bytes("uno")));
        s.append(Record.delete("beta"));
        s.append(Record.put("gamma", bytes("tres")));
        return file;
    }

    @Test
    void workedExampleProducesExactBytesAndCounts() throws IOException {
        Path file = worked();
        assertEquals(95, Files.size(file));
        Compactor.Result r = Compactor.compact(Store.load(file, "demo.kv", false));
        assertEquals(5, r.records());
        assertEquals(2, r.keys());
        assertEquals(95, r.oldBytes());
        assertEquals(43, r.newBytes());
        byte[] want = concat(Record.MAGIC, expected(1, "alpha", "uno"), expected(1, "gamma", "tres"));
        assertEquals(43, want.length);
        assertArrayEquals(want, Files.readAllBytes(file));
        assertFalse(Files.exists(dir.resolve("demo.kv.compact")));
    }

    @Test
    void putThenDeleteLeavesOnlyMagic() throws IOException {
        Path file = dir.resolve("a.kv");
        Store s = open(file);
        s.append(Record.put("a", bytes("1")));
        s.append(Record.delete("a"));
        assertEquals(29, Files.size(file));
        Compactor.Result r = Compactor.compact(Store.load(file, "a.kv", false));
        assertEquals(2, r.records());
        assertEquals(0, r.keys());
        assertEquals(29, r.oldBytes());
        assertEquals(4, r.newBytes());
        assertArrayEquals(Record.MAGIC, Files.readAllBytes(file));
    }

    @Test
    void zeroByteFileBecomesMagic() throws IOException {
        Path file = dir.resolve("e.kv");
        Files.write(file, new byte[0]);
        Compactor.Result r = Compactor.compact(Store.load(file, "e.kv", false));
        assertEquals(0, r.records());
        assertEquals(0, r.keys());
        assertEquals(0, r.oldBytes());
        assertEquals(4, r.newBytes());
        assertArrayEquals(Record.MAGIC, Files.readAllBytes(file));
    }

    @Test
    void staleCompactFileIsOverwritten() throws IOException {
        Path file = worked();
        Path stale = dir.resolve("demo.kv.compact");
        Files.write(stale, bytes("stale content that is much longer than the compacted file itself, yes"));
        Compactor.compact(Store.load(file, "demo.kv", false));
        assertFalse(Files.exists(stale));
        assertEquals(43, Files.size(file));
    }

    @Test
    void reloadGivesSameLivePairsAndNoGarbage() throws IOException {
        Path file = worked();
        Store before = Store.load(file, "demo.kv", false);
        List<String> keysBefore = new ArrayList<>(before.live().keySet());
        Compactor.compact(before);
        Store after = Store.load(file, "demo.kv", false);
        assertEquals(keysBefore, new ArrayList<>(after.live().keySet()));
        assertEquals(List.of("alpha", "gamma"), keysBefore);
        for (Map.Entry<String, Record> e : after.live().entrySet()) {
            assertArrayEquals(before.live().get(e.getKey()).value(), e.getValue().value());
        }
        assertEquals(after.fileLength(), after.liveBytes());
        assertEquals(43, after.liveBytes());
        assertEquals(2, after.scan().records().size());
        assertFalse(after.hasTail());
    }

    @Test
    void keysAreWrittenInUnsignedByteOrder() throws IOException {
        Path file = dir.resolve("o.kv");
        Store s = open(file);
        for (String k : new String[] {"b", "B", "a", "A", "_", "ab"}) {
            s.append(Record.put(k, bytes("v" + k)));
        }
        Compactor.compact(Store.load(file, "o.kv", false));
        byte[] want = concat(Record.MAGIC, expected(1, "A", "vA"), expected(1, "B", "vB"),
                expected(1, "_", "v_"), expected(1, "a", "va"), expected(1, "ab", "vab"),
                expected(1, "b", "vb"));
        assertArrayEquals(want, Files.readAllBytes(file));
    }

    @Test
    void compactingTwiceIsStable() throws IOException {
        Path file = worked();
        Compactor.compact(Store.load(file, "demo.kv", false));
        byte[] first = Files.readAllBytes(file);
        Compactor.Result r = Compactor.compact(Store.load(file, "demo.kv", false));
        assertEquals(2, r.records());
        assertEquals(43, r.oldBytes());
        assertEquals(43, r.newBytes());
        assertArrayEquals(first, Files.readAllBytes(file));
    }

    @Test
    void appendAfterCompactionWorks() throws IOException {
        Path file = worked();
        Compactor.compact(Store.load(file, "demo.kv", false));
        Store s = Store.load(file, "demo.kv", false);
        s.append(Record.put("delta", bytes("4")));
        Store again = Store.load(file, "demo.kv", false);
        assertEquals(List.of("alpha", "delta", "gamma"), new ArrayList<>(again.live().keySet()));
        assertEquals(43 + 11 + 5 + 1, Files.size(file));
    }

    @Test
    void emptyValueAndBinaryValueSurvive() throws IOException {
        Path file = dir.resolve("b.kv");
        Store s = open(file);
        s.append(Record.put("e", new byte[0]));
        s.append(Record.put("bin", new byte[] {0, (byte) 0xff, 10, 13}));
        Compactor.compact(Store.load(file, "b.kv", false));
        Store after = Store.load(file, "b.kv", false);
        assertEquals(0, after.live().get("e").value().length);
        assertArrayEquals(new byte[] {0, (byte) 0xff, 10, 13}, after.live().get("bin").value());
        assertEquals(after.fileLength(), after.liveBytes());
    }

    @Test
    void compactCommandPrintsSummary() throws IOException {
        Path file = worked();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Cli.run(new String[] {"compact", file.toString()},
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(0, code);
        assertEquals("compacted: 5 records, 2 keys, 95 -> 43 bytes\n",
                out.toString(StandardCharsets.UTF_8));
        assertEquals("", err.toString(StandardCharsets.UTF_8));
        assertTrue(Files.exists(file));
        assertFalse(Files.exists(dir.resolve("demo.kv.compact")));
    }
}
