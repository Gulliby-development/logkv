package io.github.gulliby.logkv;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/** One log record: type, key bytes, value bytes. */
public final class Record {
    public static final byte[] MAGIC = {'K', 'V', 'L', '1'};
    public static final int MAGIC_SIZE = 4;
    public static final int HEADER_SIZE = 11;
    public static final byte TYPE_PUT = 1;
    public static final byte TYPE_DELETE = 2;
    public static final int MAX_KEY_LENGTH = 255;
    public static final int MAX_VALUE_LENGTH = 1048576;

    private final byte type;
    private final byte[] key;
    private final byte[] value;

    public Record(byte type, byte[] key, byte[] value) {
        this.type = type;
        this.key = key.clone();
        this.value = value.clone();
    }

    public static Record put(String key, byte[] value) {
        return new Record(TYPE_PUT, key.getBytes(StandardCharsets.US_ASCII), value);
    }

    public static Record delete(String key) {
        return new Record(TYPE_DELETE, key.getBytes(StandardCharsets.US_ASCII), new byte[0]);
    }

    public byte type() {
        return type;
    }

    public byte[] key() {
        return key.clone();
    }

    public String keyString() {
        return new String(key, StandardCharsets.US_ASCII);
    }

    public byte[] value() {
        return value.clone();
    }

    public boolean isPut() {
        return type == TYPE_PUT;
    }

    public int size() {
        return HEADER_SIZE + key.length + value.length;
    }

    public byte[] encode() {
        ByteBuffer buf = ByteBuffer.allocate(size());
        buf.putInt(0);
        buf.put(type);
        buf.putShort((short) key.length);
        buf.putInt(value.length);
        buf.put(key);
        buf.put(value);
        byte[] out = buf.array();
        CRC32 crc = new CRC32();
        crc.update(out, 4, out.length - 4);
        ByteBuffer.wrap(out).putInt(0, (int) crc.getValue());
        return out;
    }
}
