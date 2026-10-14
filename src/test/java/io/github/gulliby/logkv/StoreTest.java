package io.github.gulliby.logkv;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StoreTest {
    @TempDir
    Path dir;

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** Expected record bytes, computed independently of Record.encode. */
    private static byte[] expected(int type, String key, String value) {
        byte[] k = ascii(key);
        byte[] v = value.getBytes(StandardCharsets.UTF_8);
        ByteBuffer body = ByteBuffer.allocate(7 + k.length + v.length);
        body.put((byte) type).putShort((short) k.length).putInt(v.length).put(k).put(v);
        CRC32 crc = new CRC32();
        crc.update(body.array());
        ByteBuffer out = ByteBuffer.allocate(4 + body.capacity());
        out.putInt((int) crc.getValue()).put(body.array());
        return out.array();
    }

    private static byte[] concat(byte[]... parts) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.write(p);
        }
        return o.toByteArray();
    }

    private static Record put(String k, String v) {
        return Record.put(k, v.getBytes(StandardCharsets.UTF_8));
    }

    private Store workedExample(Path file) {
        Store s = Store.load(file, "demo.kv", true);
        s.append(put("alpha", "one"));
        s.append(put("beta", "two"));
        s.append(put("alpha", "uno"));
        s.append(Record.delete("beta"));
        s.append(put("gamma", "tres"));
        return s;
    }

    @Test
    void indexHoldsOnlyLiveKeyAfterMixedRecords() {
        Path f = dir.resolve("a.kv");
        Store s = Store.load(f, "a.kv", true);
        s.append(put("a", "1"));
        s.append(Record.delete("zz"));
        s.append(put("a", "2"));
        s.append(put("b", "3"));
        s.append(Record.delete("a"));
        Store r = Store.load(f, "a.kv", false);
        assertEquals(List.of("b"), new ArrayList<>(r.live().keySet()));
        assertArrayEquals(ascii("3"), r.live().get("b").value());
        assertEquals(5, r.scan().records().size());
        assertEquals(List.of("b"), new ArrayList<>(s.live().keySet()));
    }

    @Test
    void lastRecordOfKeyWins() {
        Path f = dir.resolve("w.kv");
        Store s = Store.load(f, "w.kv", true);
        s.append(put("k", "old"));
        s.append(put("k", "new"));
        Store r = Store.load(f, "w.kv", false);
        assertEquals(1, r.live().size());
        assertArrayEquals(ascii("new"), r.live().get("k").value());
    }

    @Test
    void deleteThenPutRevivesKey() {
        Path f = dir.resolve("r.kv");
        Store s = Store.load(f, "r.kv", true);
        s.append(put("k", "1"));
        s.append(Record.delete("k"));
        s.append(put("k", "2"));
        Store r = Store.load(f, "r.kv", false);
        assertArrayEquals(ascii("2"), r.live().get("k").value());
    }

    @Test
    void appendGrowsFileByRecordSizeAndReloadMatches() throws IOException {
        Path f = dir.resolve("g.kv");
        Store s = Store.load(f, "g.kv", true);
        Record first = put("alpha", "one");
        s.append(first);
        assertEquals(4 + first.size(), Files.size(f));
        assertEquals(Files.size(f), s.fileLength());
        Record second = Record.delete("alpha");
        s.append(second);
        assertEquals(4 + first.size() + second.size(), Files.size(f));
        Record third = put("b", "x".repeat(100));
        s.append(third);
        assertEquals(4 + first.size() + second.size() + third.size(), Files.size(f));
        Store r = Store.load(f, "g.kv", false);
        assertEquals(s.live().keySet(), r.live().keySet());
        assertEquals(s.fileLength(), r.fileLength());
        assertEquals(3, r.scan().records().size());
        assertEquals(4L, r.scan().records().get(0).offset());
        assertEquals(4L + first.size(), r.scan().records().get(1).offset());
        assertEquals(s.scan().validEnd(), r.scan().validEnd());
    }

    @Test
    void firstAppendToNewFileWritesMagicAndRecord() throws IOException {
        Path f = dir.resolve("n.kv");
        Store s = Store.load(f, "n.kv", true);
        assertEquals(0, s.fileLength());
        assertFalse(Files.exists(f));
        s.append(put("alpha", "one"));
        byte[] want = concat(ascii("KVL1"), expected(1, "alpha", "one"));
        assertArrayEquals(want, Files.readAllBytes(f));
        assertEquals(23, want.length);
    }

    @Test
    void firstAppendToZeroByteFileWritesMagic() throws IOException {
        Path f = dir.resolve("z.kv");
        Files.write(f, new byte[0]);
        Store s = Store.load(f, "z.kv", false);
        s.append(Record.delete("q"));
        assertArrayEquals(concat(ascii("KVL1"), expected(2, "q", "")), Files.readAllBytes(f));
    }

    @Test
    void secondAppendDoesNotRepeatMagic() throws IOException {
        Path f = dir.resolve("m.kv");
        Store s = Store.load(f, "m.kv", true);
        s.append(put("a", "1"));
        s.append(put("b", "2"));
        assertArrayEquals(
                concat(ascii("KVL1"), expected(1, "a", "1"), expected(1, "b", "2")),
                Files.readAllBytes(f));
    }

    @Test
    void liveBytesIsZeroForZeroByteFile() throws IOException {
        Path f = dir.resolve("e.kv");
        Files.write(f, new byte[0]);
        Store s = Store.load(f, "e.kv", false);
        assertEquals(0, s.liveBytes());
        assertEquals(0, s.fileLength());
        assertTrue(s.live().isEmpty());
        assertFalse(s.hasTail());
        assertNull(s.scan().problem());
    }

    @Test
    void liveBytesIsFourForMagicOnlyFile() throws IOException {
        Path f = dir.resolve("o.kv");
        Files.write(f, ascii("KVL1"));
        assertEquals(4, Store.load(f, "o.kv", false).liveBytes());
    }

    @Test
    void workedExampleSizes() throws IOException {
        Path f = dir.resolve("demo.kv");
        Store s = workedExample(f);
        assertEquals(95, Files.size(f));
        assertEquals(95, s.fileLength());
        assertEquals(43, s.liveBytes());
        Store r = Store.load(f, "demo.kv", false);
        assertEquals(43, r.liveBytes());
        assertEquals(List.of("alpha", "gamma"), new ArrayList<>(r.live().keySet()));
        assertEquals(List.of(4L, 23L, 41L, 60L, 75L),
                r.scan().records().stream().map(LogScan.Entry::offset).toList());
    }

    @Test
    void liveKeysAreInUnsignedByteOrder() {
        Path f = dir.resolve("o2.kv");
        Store s = Store.load(f, "o2.kv", true);
        for (String k : List.of("b", "B", "a", "A", "_", "ab")) {
            s.append(put(k, "v"));
        }
        assertEquals(List.of("A", "B", "_", "a", "ab", "b"),
                new ArrayList<>(Store.load(f, "o2.kv", false).live().keySet()));
    }

    @Test
    void missingFileIsReadErrorUnlessMissingIsEmpty() {
        Path f = dir.resolve("none.kv");
        KvException e = assertThrows(KvException.class, () -> Store.load(f, "none.kv", false));
        assertEquals("none.kv: cannot read the file", e.getMessage());
        Store s = Store.load(f, "none.kv", true);
        assertEquals(0, s.fileLength());
        assertTrue(s.live().isEmpty());
        assertFalse(Files.exists(f));
    }

    @Test
    void directoryIsReadError() {
        for (boolean missingIsEmpty : new boolean[] {false, true}) {
            KvException e = assertThrows(KvException.class, () -> Store.load(dir, "sub/d", missingIsEmpty));
            assertEquals("sub/d: cannot read the file", e.getMessage());
        }
    }

    @ParameterizedTest
    @CsvSource({"hello", "KV", "K", "KVL", "KVL2", "kvl1", "XVL1abcdef"})
    void wrongMagicIsNotALogkvFile(String content) throws IOException {
        Path f = dir.resolve("bad.kv");
        Files.write(f, ascii(content));
        KvException e = assertThrows(KvException.class, () -> Store.load(f, "bad.kv", false));
        assertEquals("bad.kv: not a logkv file", e.getMessage());
    }

    @Test
    void requireIntactPassesOnCleanFile() {
        Path f = dir.resolve("c.kv");
        Store s = workedExample(f);
        s.requireIntact();
        assertFalse(s.hasTail());
        Store.load(f, "c.kv", false).requireIntact();
    }

    @Test
    void requireIntactReportsDamagedTail() throws IOException {
        Path f = dir.resolve("demo.kv");
        workedExample(f);
        Files.write(f, new byte[] {0, 1, 2}, StandardOpenOption.APPEND);
        Store s = Store.load(f, "demo.kv", false);
        assertTrue(s.hasTail());
        assertEquals(98, s.fileLength());
        assertEquals(95, s.scan().validEnd());
        assertEquals("incomplete record", s.scan().problem());
        assertEquals(5, s.scan().records().size());
        assertEquals(List.of("alpha", "gamma"), new ArrayList<>(s.live().keySet()));
        KvException e = assertThrows(KvException.class, s::requireIntact);
        assertEquals("demo.kv: corrupt at offset 95: incomplete record (run repair first)", e.getMessage());
    }

    @Test
    void truncateToValidCutsTailToValidEnd() throws IOException {
        Path f = dir.resolve("t.kv");
        workedExample(f);
        byte[] good = Files.readAllBytes(f);
        Files.write(f, new byte[] {0, 1, 2}, StandardOpenOption.APPEND);
        Store s = Store.load(f, "t.kv", false);
        s.truncateToValid();
        assertEquals(s.scan().validEnd(), Files.size(f));
        assertEquals(95, Files.size(f));
        assertArrayEquals(good, Files.readAllBytes(f));
        assertFalse(s.hasTail());
        assertEquals(95, s.fileLength());
        Store r = Store.load(f, "t.kv", false);
        assertFalse(r.hasTail());
        assertNull(r.scan().problem());
    }

    @Test
    void truncateToValidCutsBadChecksumRecord() throws IOException {
        Path f = dir.resolve("cs.kv");
        byte[] data = concat(ascii("KVL1"), expected(1, "a", "1"), expected(1, "b", "2"));
        data[data.length - 1] ^= 0x01;
        Files.write(f, data);
        Store s = Store.load(f, "cs.kv", false);
        assertEquals("bad checksum", s.scan().problem());
        assertEquals(17, s.scan().validEnd());
        s.truncateToValid();
        assertEquals(17, Files.size(f));
        assertEquals(List.of("a"), new ArrayList<>(Store.load(f, "cs.kv", false).live().keySet()));
    }

    @Test
    void appendToUncreatableFileIsWriteError() {
        Path f = dir.resolve("no-such-dir").resolve("x.kv");
        Store s = Store.load(f, "no-such-dir/x.kv", true);
        KvException e = assertThrows(KvException.class, () -> s.append(put("a", "1")));
        assertEquals("no-such-dir/x.kv: cannot write the file", e.getMessage());
        assertFalse(Files.exists(f));
    }

    @Test
    void storeExposesPathAndDisplay() {
        Path f = dir.resolve("p.kv");
        Store s = Store.load(f, "shown/p.kv", true);
        assertEquals(f, s.path());
        assertEquals("shown/p.kv", s.display());
    }
}
