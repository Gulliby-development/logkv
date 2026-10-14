package io.github.gulliby.logkv;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/** A log file loaded into memory: scan result plus the live-key index. */
public final class Store {
    private final Path path;
    private final String display;
    private long fileLength;
    private LogScan.Result scan;
    private final TreeMap<String, Record> live = new TreeMap<>(Keys.COMPARATOR);

    private Store(Path path, String display, long fileLength, LogScan.Result scan) {
        this.path = path;
        this.display = display;
        this.fileLength = fileLength;
        this.scan = scan;
        for (LogScan.Entry e : scan.records()) {
            apply(e.record());
        }
    }

    public static Store load(Path path, String display, boolean missingIsEmpty) {
        byte[] data;
        if (Files.isDirectory(path)) {
            throw new KvException(display + ": cannot read the file");
        }
        if (!Files.exists(path)) {
            if (!missingIsEmpty) {
                throw new KvException(display + ": cannot read the file");
            }
            data = new byte[0];
        } else {
            try {
                data = Files.readAllBytes(path);
            } catch (IOException | RuntimeException e) {
                throw new KvException(display + ": cannot read the file");
            }
        }
        if (data.length > 0) {
            boolean magic = data.length >= Record.MAGIC_SIZE
                    && Arrays.equals(Arrays.copyOf(data, Record.MAGIC_SIZE), Record.MAGIC);
            if (!magic) {
                throw new KvException(display + ": not a logkv file");
            }
        }
        return new Store(path, display, data.length, LogScan.scan(data));
    }

    private void apply(Record r) {
        if (r.isPut()) {
            live.put(r.keyString(), r);
        } else {
            live.remove(r.keyString());
        }
    }

    public Path path() {
        return path;
    }

    public String display() {
        return display;
    }

    public long fileLength() {
        return fileLength;
    }

    public LogScan.Result scan() {
        return scan;
    }

    public SortedMap<String, Record> live() {
        return live;
    }

    public long liveBytes() {
        if (fileLength == 0) {
            return 0;
        }
        long sum = Record.MAGIC_SIZE;
        for (Record r : live.values()) {
            sum += r.size();
        }
        return sum;
    }

    public boolean hasTail() {
        return scan.problem() != null;
    }

    public void requireIntact() {
        if (hasTail()) {
            throw new KvException(display + ": corrupt at offset " + scan.problemOffset() + ": "
                    + scan.problem() + " (run repair first)");
        }
    }

    public void append(Record r) {
        boolean fresh = fileLength == 0;
        byte[] encoded = r.encode();
        byte[] out;
        if (fresh) {
            out = new byte[Record.MAGIC_SIZE + encoded.length];
            System.arraycopy(Record.MAGIC, 0, out, 0, Record.MAGIC_SIZE);
            System.arraycopy(encoded, 0, out, Record.MAGIC_SIZE, encoded.length);
        } else {
            out = encoded;
        }
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            ByteBuffer buf = ByteBuffer.wrap(out);
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            ch.force(true);
        } catch (IOException | RuntimeException e) {
            throw new KvException(display + ": cannot write the file");
        }
        long offset = fileLength + (fresh ? Record.MAGIC_SIZE : 0);
        List<LogScan.Entry> entries = new ArrayList<>(scan.records());
        entries.add(new LogScan.Entry(offset, r));
        fileLength += out.length;
        scan = new LogScan.Result(entries, fileLength, null);
        apply(r);
    }

    public void truncateToValid() {
        long end = scan.validEnd();
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
            ch.truncate(end);
            ch.force(true);
        } catch (IOException | RuntimeException e) {
            throw new KvException(display + ": cannot write the file");
        }
        fileLength = end;
        scan = new LogScan.Result(new ArrayList<>(scan.records()), end, null);
    }
}
