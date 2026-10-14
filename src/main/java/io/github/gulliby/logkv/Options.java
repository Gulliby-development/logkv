package io.github.gulliby.logkv;

import java.util.ArrayList;
import java.util.List;

/** Parsed command-line arguments. */
public final class Options {
    private static final List<String> COMMANDS =
            List.of("put", "get", "del", "list", "stats", "compact", "verify", "repair", "dump");

    private final boolean help;
    private final String command;
    private final String file;
    private final String key;
    private final String value;
    private final String prefix;

    private Options(boolean help, String command, String file, String key, String value, String prefix) {
        this.help = help;
        this.command = command;
        this.file = file;
        this.key = key;
        this.value = value;
        this.prefix = prefix;
    }

    public boolean help() {
        return help;
    }

    public String command() {
        return command;
    }

    public String file() {
        return file;
    }

    public String key() {
        return key;
    }

    /** The raw value argument; {@code -} means standard input. */
    public String value() {
        return value;
    }

    /** The prefix, or null when not given. */
    public String prefix() {
        return prefix;
    }

    public static Options parse(String[] args) {
        if (args.length == 0) {
            throw new KvException("missing command (put, get, del, list, stats, compact, verify, repair or dump)");
        }
        String first = args[0];
        if (first.equals("--help")) {
            if (args.length == 1) {
                return new Options(true, null, null, null, null, null);
            }
            throw new KvException("unknown option \"--help\"");
        }
        if (!COMMANDS.contains(first)) {
            throw new KvException("unknown command \"" + first + "\"");
        }
        boolean isList = first.equals("list");
        List<String> pos = new ArrayList<>();
        String prefix = null;
        boolean positionalOnly = false;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (positionalOnly) {
                pos.add(a);
            } else if (a.equals("--")) {
                positionalOnly = true;
            } else if (isList && a.equals("--prefix")) {
                if (i + 1 >= args.length) {
                    throw new KvException("--prefix needs a value");
                }
                prefix = args[++i];
            } else if (isList && a.startsWith("--prefix=")) {
                prefix = a.substring("--prefix=".length());
            } else if (a.startsWith("-") && !a.equals("-")) {
                throw new KvException("unknown option \"" + a + "\"");
            } else {
                pos.add(a);
            }
        }
        boolean needsKey = first.equals("put") || first.equals("get") || first.equals("del");
        boolean needsValue = first.equals("put");
        int expected = 1 + (needsKey ? 1 : 0) + (needsValue ? 1 : 0);
        if (pos.isEmpty()) {
            throw new KvException("missing FILE");
        }
        if (needsKey && pos.size() < 2) {
            throw new KvException("missing KEY");
        }
        if (needsValue && pos.size() < 3) {
            throw new KvException("missing VALUE");
        }
        if (pos.size() > expected) {
            throw new KvException("too many arguments");
        }
        String key = needsKey ? pos.get(1) : null;
        String value = needsValue ? pos.get(2) : null;
        if (key != null && !Keys.isValidKey(key)) {
            throw new KvException("invalid key \"" + key
                    + "\" (expected 1 to 255 printable ASCII characters, no spaces)");
        }
        if (prefix != null && !Keys.isValidPrefix(prefix)) {
            throw new KvException("invalid prefix \"" + prefix
                    + "\" (expected printable ASCII characters, no spaces)");
        }
        return new Options(false, first, pos.get(0), key, value, prefix);
    }
}
