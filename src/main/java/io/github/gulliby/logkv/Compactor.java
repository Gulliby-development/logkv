package io.github.gulliby.logkv;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Rewrites a store without superseded records. */
public final class Compactor {
    private Compactor() {
    }

    /** Outcome of a compaction. */
    public static final class Result {
        private final long records;
        private final long keys;
        private final long oldBytes;
        private final long newBytes;

        public Result(long records, long keys, long oldBytes, long newBytes) {
            this.records = records;
            this.keys = keys;
            this.oldBytes = oldBytes;
            this.newBytes = newBytes;
        }

        public long records() {
            return records;
        }

        public long keys() {
            return keys;
        }

        public long oldBytes() {
            return oldBytes;
        }

        public long newBytes() {
            return newBytes;
        }
    }

    public static Result compact(Store store) {
        Path path = store.path();
        Path tmp = path.resolveSibling(path.getFileName().toString() + ".compact");
        long size = Record.MAGIC_SIZE;
        for (Record r : store.live().values()) {
            size += r.size();
        }
        ByteBuffer buf = ByteBuffer.allocate((int) size);
        buf.put(Record.MAGIC);
        for (Record r : store.live().values()) {
            buf.put(r.encode());
        }
        buf.flip();
        try {
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                ch.force(true);
            }
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException | RuntimeException ignored) {
                // best effort cleanup
            }
            throw new KvException(store.display() + ": cannot write the file");
        }
        return new Result(store.scan().records().size(), store.live().size(), store.fileLength(), size);
    }
}
