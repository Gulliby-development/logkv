package io.github.gulliby.logkv;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RecordTest {

    private static byte[] expected(int type, byte[] key, byte[] value) {
        ByteBuffer body = ByteBuffer.allocate(7 + key.length + value.length);
        body.put((byte) type);
        body.putShort((short) key.length);
        body.putInt(value.length);
        body.put(key);
        body.put(value);
        CRC32 crc = new CRC32();
        crc.update(body.array(), 0, body.capacity());
        ByteBuffer out = ByteBuffer.allocate(4 + body.capacity());
        out.putInt((int) crc.getValue());
        out.put(body.array());
        return out.array();
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void putAlphaOneEncodesToExactBytes() {
        byte[] encoded = Record.put("alpha", ascii("one")).encode();
        byte[] hex = HexFormat.of().parseHex("be0a2fa5" + "01" + "0005" + "00000003" + "616c706861" + "6f6e65");
        assertEquals(19, encoded.length);
        assertArrayEquals(hex, encoded);
        assertArrayEquals(expected(1, ascii("alpha"), ascii("one")), encoded);
    }

    @Test
    void deleteEncodesTypeTwoWithEmptyValue() {
        byte[] encoded = Record.delete("alpha").encode();
        assertEquals(16, encoded.length);
        assertEquals(2, encoded[4]);
        assertArrayEquals(new byte[] {0, 5}, Arrays.copyOfRange(encoded, 5, 7));
        assertArrayEquals(new byte[] {0, 0, 0, 0}, Arrays.copyOfRange(encoded, 7, 11));
        assertArrayEquals(expected(2, ascii("alpha"), new byte[0]), encoded);
    }

    @Test
    void emptyValuePutHasHeaderPlusKeyBytes() {
        Record r = Record.put("k", new byte[0]);
        byte[] encoded = r.encode();
        assertEquals(12, encoded.length);
        assertEquals(12, r.size());
        assertEquals(1, encoded[4]);
        assertArrayEquals(expected(1, ascii("k"), new byte[0]), encoded);
    }

    @ParameterizedTest
    @CsvSource({"a,0,12", "a,1,13", "alpha,3,19", "abcdefghij,100,121", "k,1048576,1048588"})
    void sizeIsHeaderPlusKeyPlusValue(String key, int valueLength, int size) {
        Record r = Record.put(key, new byte[valueLength]);
        assertEquals(size, r.size());
        assertEquals(size, r.encode().length);
    }

    @Test
    void deleteSizeHasNoValue() {
        assertEquals(16, Record.delete("alpha").size());
        assertEquals(12, Record.delete("a").size());
    }

    @Test
    void constantsHaveSpecifiedValues() {
        assertEquals(1048576, Record.MAX_VALUE_LENGTH);
        assertEquals(255, Record.MAX_KEY_LENGTH);
        assertEquals(11, Record.HEADER_SIZE);
        assertEquals(4, Record.MAGIC_SIZE);
        assertEquals(1, Record.TYPE_PUT);
        assertEquals(2, Record.TYPE_DELETE);
        assertArrayEquals(ascii("KVL1"), Record.MAGIC);
    }

    @Test
    void maxLengthKeyIsEncoded() {
        String key = "k".repeat(255);
        byte[] encoded = Record.put(key, ascii("v")).encode();
        assertEquals(11 + 255 + 1, encoded.length);
        assertEquals(0, encoded[5]);
        assertEquals((byte) 255, encoded[6]);
        assertArrayEquals(expected(1, ascii(key), ascii("v")), encoded);
    }

    @Test
    void maxLengthValueIsEncoded() {
        byte[] value = new byte[1048576];
        Arrays.fill(value, (byte) 0xAB);
        byte[] encoded = Record.put("big", value).encode();
        assertEquals(11 + 3 + 1048576, encoded.length);
        assertArrayEquals(new byte[] {0, 0x10, 0, 0}, Arrays.copyOfRange(encoded, 7, 11));
        assertArrayEquals(expected(1, ascii("big"), value), encoded);
    }

    @Test
    void binaryValueIsKeptByteForByte() {
        byte[] value = {0, (byte) 0xff, 10, 13, 26};
        Record r = Record.put("bin", value);
        assertArrayEquals(value, r.value());
        assertArrayEquals(expected(1, ascii("bin"), value), r.encode());
    }

    @Test
    void accessorsReportTypeKeyAndValue() {
        Record p = Record.put("alpha", ascii("one"));
        assertTrue(p.isPut());
        assertEquals(Record.TYPE_PUT, p.type());
        assertEquals("alpha", p.keyString());
        assertArrayEquals(ascii("alpha"), p.key());
        assertArrayEquals(ascii("one"), p.value());
        Record d = Record.delete("beta");
        assertFalse(d.isPut());
        assertEquals(Record.TYPE_DELETE, d.type());
        assertEquals("beta", d.keyString());
        assertEquals(0, d.value().length);
    }

    @Test
    void recordCopiesItsArrays() {
        byte[] key = ascii("abc");
        byte[] value = ascii("xyz");
        Record r = new Record(Record.TYPE_PUT, key, value);
        key[0] = 'Z';
        value[0] = 'Z';
        assertEquals("abc", r.keyString());
        assertArrayEquals(ascii("xyz"), r.value());
        assertNotSame(r.key(), r.key());
        assertNotSame(r.value(), r.value());
    }

    @Test
    void differentValuesGiveDifferentChecksums() {
        byte[] a = Record.put("k", ascii("1")).encode();
        byte[] b = Record.put("k", ascii("2")).encode();
        assertFalse(Arrays.equals(Arrays.copyOfRange(a, 0, 4), Arrays.copyOfRange(b, 0, 4)));
    }
}
