package io.github.gulliby.logkv;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Command dispatcher. */
public final class Cli {
    private Cli() {
    }

    public static int run(String[] args, InputStream stdin, PrintStream stdout, PrintStream stderr) {
        try {
            return execute(args, stdin, stdout, stderr);
        } catch (KvException e) {
            stderr.print(Report.error(e.getMessage()));
            return 2;
        }
    }

    private static int execute(String[] args, InputStream stdin, PrintStream stdout, PrintStream stderr) {
        Options o = Options.parse(args);
        if (o.help()) {
            stdout.print(Report.usage());
            return 0;
        }
        String display = o.file().replace('\\', '/');
        String cmd = o.command();
        byte[] value = null;
        if (cmd.equals("put")) {
            value = readValue(o.value(), stdin);
        }
        Store store = Store.load(Path.of(o.file()), display, cmd.equals("put"));
        switch (cmd) {
            case "put":
                store.requireIntact();
                store.append(Record.put(o.key(), value));
                return 0;
            case "del":
                store.requireIntact();
                if (!store.live().containsKey(o.key())) {
                    stderr.print(Report.keyNotFound(o.key()));
                    return 1;
                }
                store.append(Record.delete(o.key()));
                return 0;
            case "compact":
                store.requireIntact();
                Compactor.Result r = Compactor.compact(store);
                stdout.print(Report.compacted(r.records(), r.keys(), r.oldBytes(), r.newBytes()));
                return 0;
            case "verify":
                return verify(store, stdout);
            case "repair":
                return repair(store, stdout);
            default:
                break;
        }
        warn(store, stderr);
        switch (cmd) {
            case "get":
                Record rec = store.live().get(o.key());
                if (rec == null) {
                    stderr.print(Report.keyNotFound(o.key()));
                    return 1;
                }
                byte[] v = rec.value();
                stdout.write(v, 0, v.length);
                return 0;
            case "list":
                for (String k : store.live().keySet()) {
                    if (o.prefix() == null || k.startsWith(o.prefix())) {
                        stdout.print(Report.listLine(k));
                    }
                }
                return 0;
            case "stats":
                stdout.print(Report.stats(store.live().size(), store.scan().records().size(),
                        store.fileLength(), store.liveBytes()));
                return 0;
            default:
                for (LogScan.Entry e : store.scan().records()) {
                    stdout.print(Report.dumpLine(e));
                }
                return 0;
        }
    }

    private static byte[] readValue(String arg, InputStream stdin) {
        byte[] v;
        if (arg.equals("-")) {
            try {
                v = stdin.readNBytes(Record.MAX_VALUE_LENGTH + 1);
            } catch (IOException | RuntimeException e) {
                throw new KvException("cannot read standard input");
            }
        } else {
            v = arg.getBytes(StandardCharsets.UTF_8);
        }
        if (v.length > Record.MAX_VALUE_LENGTH) {
            throw new KvException("value too large (limit " + Record.MAX_VALUE_LENGTH + " bytes)");
        }
        return v;
    }

    private static void warn(Store store, PrintStream stderr) {
        if (store.hasTail()) {
            LogScan.Result s = store.scan();
            stderr.print(Report.warning(store.display(), store.fileLength() - s.problemOffset(),
                    s.problemOffset(), s.problem()));
        }
    }

    private static int verify(Store store, PrintStream stdout) {
        LogScan.Result s = store.scan();
        if (store.hasTail()) {
            stdout.print(Report.verifyCorrupt(s.problemOffset(), s.problem(),
                    store.fileLength() - s.problemOffset()));
            return 1;
        }
        stdout.print(Report.verifyOk(s.records().size(), store.live().size()));
        return 0;
    }

    private static int repair(Store store, PrintStream stdout) {
        if (!store.hasTail()) {
            stdout.print(Report.repairNothing());
            return 0;
        }
        long offset = store.scan().validEnd();
        long removed = store.fileLength() - offset;
        store.truncateToValid();
        stdout.print(Report.repaired(removed, offset));
        return 0;
    }
}
