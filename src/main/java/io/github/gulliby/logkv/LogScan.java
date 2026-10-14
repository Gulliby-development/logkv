package io.github.gulliby.logkv;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;

/** Scans log bytes and returns the valid records and the first problem. */
public final class LogScan {
    public static final String INCOMPLETE = "incomplete record";
    public static final String BAD_TYPE = "bad record type";
    public static final String BAD_LENGTH = "bad length";
    public static final String BAD_CHECKSUM = "bad checksum";

    private LogScan() {
    }

    /** A valid record and its offset in the file. */
    public record Entry(long offset, Record record) {
    }

    /** Scan result. */
    public static final class Result {
        private final List<Entry> records;
        private final long validEnd;
        private final String problem;

        public Result(List<Entry> records, long validEnd, String problem) {
            this.records = Collections.unmodifiableList(records);
            this.validEnd = validEnd;
            this.problem = problem;
        }

        public List<Entry> records() {
            return records;
        }

        public long validEnd() {
            return validEnd;
        }

        public String problem() {
            return problem;
        }

        public long problemOffset() {
            return validEnd;
        }
    }

    public static Result scan(byte[] data) {
        List<Entry> records = new ArrayList<>();
        if (data.length == 0) {
            return new Result(records, 0, null);
        }
        ByteBuffer buf = ByteBuffer.wrap(data);
        int pos = Record.MAGIC_SIZE;
        while (pos < data.length) {
            long remaining = (long) data.length - pos;
            if (remaining < Record.HEADER_SIZE) {
                return new Result(records, pos, INCOMPLETE);
            }
            byte type = data[pos + 4];
            if (type != Record.TYPE_PUT && type != Record.TYPE_DELETE) {
                return new Result(records, pos, BAD_TYPE);
            }
            int keyLen = buf.getShort(pos + 5) & 0xFFFF;
            long valueLen = buf.getInt(pos + 7) & 0xFFFFFFFFL;
            if (keyLen == 0 || keyLen > Record.MAX_KEY_LENGTH
                    || valueLen > Record.MAX_VALUE_LENGTH
                    || (type == Record.TYPE_DELETE && valueLen != 0)) {
                return new Result(records, pos, BAD_LENGTH);
            }
            long total = Record.HEADER_SIZE + keyLen + valueLen;
            if (total > remaining) {
                return new Result(records, pos, INCOMPLETE);
            }
            CRC32 crc = new CRC32();
            crc.update(data, pos + 4, (int) total - 4);
            if ((int) crc.getValue() != buf.getInt(pos)) {
                return new Result(records, pos, BAD_CHECKSUM);
            }
            byte[] key = new byte[keyLen];
            byte[] value = new byte[(int) valueLen];
            System.arraycopy(data, pos + Record.HEADER_SIZE, key, 0, keyLen);
            System.arraycopy(data, pos + Record.HEADER_SIZE + keyLen, value, 0, value.length);
            records.add(new Entry(pos, new Record(type, key, value)));
            pos += (int) total;
        }
        return new Result(records, pos, null);
    }
}
