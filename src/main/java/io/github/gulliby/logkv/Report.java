package io.github.gulliby.logkv;

/** All printed text. Every returned String ends with LF. */
public final class Report {
    private Report() {
    }

    public static String usage() {
        return "usage: logkv put FILE KEY VALUE\n"
                + "       logkv get FILE KEY\n"
                + "       logkv del FILE KEY\n"
                + "       logkv incr FILE KEY [DELTA]\n"
                + "       logkv list FILE [--prefix PREFIX] [--count]\n"
                + "       logkv stats|compact|verify|repair|dump FILE\n";
    }

    public static long percent(long g, long b) {
        return b == 0 ? 0 : (200 * g + b) / (2 * b);
    }

    public static String stats(long keys, long records, long fileBytes, long liveBytes) {
        long g = fileBytes - liveBytes;
        return "keys: " + keys + "\n"
                + "records: " + records + "\n"
                + "file: " + fileBytes + " bytes\n"
                + "live: " + liveBytes + " bytes\n"
                + "garbage: " + g + " bytes (" + percent(g, fileBytes) + "%)\n";
    }

    public static String verifyOk(long records, long keys) {
        return "ok: " + records + " records, " + keys + " keys\n";
    }

    public static String verifyCorrupt(long offset, String reason, long ignored) {
        return "corrupt: offset " + offset + ": " + reason + ", " + ignored + " bytes ignored\n";
    }

    public static String repairNothing() {
        return "ok: nothing to repair\n";
    }

    public static String repaired(long removed, long offset) {
        return "repaired: removed " + removed + " bytes at offset " + offset + "\n";
    }

    public static String dumpLine(LogScan.Entry entry) {
        Record r = entry.record();
        if (r.isPut()) {
            return entry.offset() + " put " + r.keyString() + " " + r.value().length + "\n";
        }
        return entry.offset() + " del " + r.keyString() + "\n";
    }

    public static String compacted(long records, long keys, long oldBytes, long newBytes) {
        return "compacted: " + records + " records, " + keys + " keys, " + oldBytes + " -> " + newBytes
                + " bytes\n";
    }

    public static String listLine(String key) {
        return key + "\n";
    }

    public static String count(long keys) {
        return Long.toString(keys) + "\n";
    }

    public static String warning(String display, long ignored, long offset, String reason) {
        return "logkv: warning: " + display + ": " + ignored + " bytes after offset " + offset
                + " are corrupt (" + reason + "); run repair\n";
    }

    public static String incremented(long value) {
        return Long.toString(value) + "\n";
    }

    public static String error(String message) {
        return "logkv: " + message + "\n";
    }

    public static String keyNotFound(String key) {
        return error("key not found: " + key);
    }
}
