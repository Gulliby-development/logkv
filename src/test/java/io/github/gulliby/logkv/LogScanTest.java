package io.github.gulliby.logkv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class LogScanTest {
    private static final byte[] MAGIC = "KVL1".getBytes(StandardCharsets.US_ASCII);

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** Builds a record with the given header fields; the CRC covers type..end of the bytes given. */
    private static byte[] raw(int type, int keyLen, long valueLen, byte[] key, byte[] value) {
        ByteBuffer buf = ByteBuffer.allocate(11 + key.length + value.length);
        buf.putInt(0);
        buf.put((byte) type);
        buf.putShort((short) keyLen);
        buf.putInt((int) valueLen);
        buf.put(key);
        buf.put(value);
        byte[] out = buf.array();
        CRC32 crc = new CRC32();
        crc.update(out, 4, out.length - 4);
        ByteBuffer.wrap(out).putInt(0, (int) crc.getValue());
        return out;
    }

    private static byte[] put(String key, byte[] value) {
        return raw(1, key.length(), value.length, ascii(key), value);
    }

    private static byte[] del(String key) {
        return raw(2, key.length(), 0, ascii(key), new byte[0]);
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        Arrays.fill(b, (byte) 'v');
        return b;
    }

    static Stream<Arguments> badRecords() {
        byte[] flipped = put("k", ascii("value"));
        flipped[flipped.length - 1] ^= 0x01;
        byte[] flippedCrc = put("k", ascii("value"));
        flippedCrc[0] ^= 0x01;
        byte[] header = raw(1, 1, 1048576, ascii("k"), new byte[0]);
        return Stream.of(
                Arguments.of("10 bytes left", new byte[10], "incomplete record"),
                Arguments.of("type 3 key length 0", raw(3, 0, 0, new byte[0], new byte[0]),
                        "bad record type"),
                Arguments.of("type 0", raw(0, 1, 0, ascii("k"), new byte[0]), "bad record type"),
                Arguments.of("key length 0", raw(1, 0, 1, new byte[0], ascii("v")), "bad length"),
                Arguments.of("key length 256", raw(1, 256, 0, bytes(256), new byte[0]),
                        "bad length"),
                Arguments.of("value length 1048577", raw(1, 1, 1048577L, ascii("k"), new byte[0]),
                        "bad length"),
                Arguments.of("delete with value length 1", raw(2, 1, 1, ascii("k"), ascii("v")),
                        "bad length"),
                Arguments.of("missing value bytes", header, "incomplete record"),
                Arguments.of("flipped value byte", flipped, "bad checksum"),
                Arguments.of("flipped crc byte", flippedCrc, "bad checksum"));
    }

    @ParameterizedTest(name = "{0} at the end")
    @MethodSource("badRecords")
    void badRecordAtEnd(String name, byte[] bad, String reason) {
        byte[] good = put("a", ascii("1"));
        LogScan.Result r = LogScan.scan(cat(MAGIC, good, bad));
        assertEquals(1, r.records().size());
        assertEquals(4, r.records().get(0).offset());
        assertEquals(4 + good.length, r.validEnd());
        assertEquals(r.validEnd(), r.problemOffset());
        assertEquals(reason, r.problem());
    }

    /** "10 bytes left" can only happen at the end of the data, so it is not a middle case. */
    static Stream<Arguments> badRecordsForMiddle() {
        return badRecords().skip(1);
    }

    @ParameterizedTest(name = "{0} in the middle")
    @MethodSource("badRecordsForMiddle")
    void badRecordInMiddle(String name, byte[] bad, String reason) {
        byte[] g1 = put("a", ascii("1"));
        byte[] g2 = put("b", ascii("2"));
        byte[] g3 = del("a");
        LogScan.Result r = LogScan.scan(cat(MAGIC, g1, g2, bad, g3, g3));
        assertEquals(2, r.records().size());
        assertEquals(4, r.records().get(0).offset());
        assertEquals(4 + g1.length, r.records().get(1).offset());
        assertEquals(4 + g1.length + g2.length, r.validEnd());
        assertEquals(r.validEnd(), r.problemOffset());
        assertEquals(reason, r.problem());
    }

    @Test
    void bareMagic() {
        LogScan.Result r = LogScan.scan(MAGIC);
        assertEquals(0, r.records().size());
        assertEquals(4, r.validEnd());
        assertNull(r.problem());
    }

    @Test
    void emptyData() {
        LogScan.Result r = LogScan.scan(new byte[0]);
        assertEquals(0, r.records().size());
        assertEquals(0, r.validEnd());
        assertEquals(0, r.problemOffset());
        assertNull(r.problem());
    }

    @Test
    void oneByteAfterMagicIsIncomplete() {
        LogScan.Result r = LogScan.scan(cat(MAGIC, new byte[] {1}));
        assertEquals(0, r.records().size());
        assertEquals(4, r.validEnd());
        assertEquals("incomplete record", r.problem());
    }

    @Test
    void workedExampleOffsets() {
        byte[] data = cat(MAGIC, put("alpha", ascii("one")), put("beta", ascii("two")),
                put("alpha", ascii("uno")), del("beta"), put("gamma", ascii("tres")));
        assertEquals(95, data.length);
        LogScan.Result r = LogScan.scan(data);
        long[] offsets = {4, 23, 41, 60, 75};
        String[] keys = {"alpha", "beta", "alpha", "beta", "gamma"};
        assertEquals(5, r.records().size());
        for (int i = 0; i < 5; i++) {
            assertEquals(offsets[i], r.records().get(i).offset());
            assertEquals(keys[i], r.records().get(i).record().keyString());
        }
        assertTrue(r.records().get(0).record().isPut());
        assertEquals(2, r.records().get(3).record().type());
        assertEquals(95, r.validEnd());
        assertNull(r.problem());
    }

    @Test
    void valueLengthExactlyLimitIsValid() {
        byte[] rec = put("k", bytes(1048576));
        LogScan.Result r = LogScan.scan(cat(MAGIC, rec));
        assertEquals(1, r.records().size());
        assertEquals(1048576, r.records().get(0).record().value().length);
        assertEquals(4 + rec.length, r.validEnd());
        assertNull(r.problem());
    }

    @Test
    void keyLengthExactly255IsValid() {
        byte[] rec = raw(1, 255, 0, bytes(255), new byte[0]);
        LogScan.Result r = LogScan.scan(cat(MAGIC, rec));
        assertEquals(1, r.records().size());
        assertEquals(255, r.records().get(0).record().key().length);
        assertNull(r.problem());
    }

    @Test
    void deleteRecordAndEmptyValueDecode() {
        LogScan.Result r = LogScan.scan(cat(MAGIC, put("k", new byte[0]), del("k")));
        assertEquals(2, r.records().size());
        assertEquals(0, r.records().get(0).record().value().length);
        assertEquals("k", r.records().get(1).record().keyString());
        assertEquals(4 + 12 + 12, r.validEnd());
        assertNull(r.problem());
    }

    @Test
    void firstRecordBadKeepsMagicOnly() {
        LogScan.Result r = LogScan.scan(cat(MAGIC, raw(9, 1, 0, ascii("k"), new byte[0])));
        assertEquals(0, r.records().size());
        assertEquals(4, r.validEnd());
        assertEquals("bad record type", r.problem());
    }
}
