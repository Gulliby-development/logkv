<p align="center"><img src="docs/logo.svg" width="96" alt="" /></p>

# logkv

[![CI](https://github.com/Gulliby-development/logkv/actions/workflows/ci.yml/badge.svg)](https://github.com/Gulliby-development/logkv/actions/workflows/ci.yml) [![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

logkv is a tiny durable key-value store kept in one file. It is for scripts and tests that need to keep a few settings or counters between runs without a database. It works offline, has no daemon and no locking, and its file is byte-identical on Windows, macOS and Linux.

Every change is appended to the file as a checksummed record. A crash in the middle of a write leaves a damaged tail that logkv detects (`verify`), ignores while reading and cuts off (`repair`). `compact` rewrites the file without the superseded records.

## Install

Build and test with Maven (Java 21, JDK standard library only):

```
mvn -B -q verify
```

Run the program with:

```
java -cp target/classes io.github.gulliby.logkv.Main ARGS
```

## Usage

```
usage: logkv put FILE KEY VALUE
       logkv get FILE KEY
       logkv del FILE KEY
       logkv incr FILE KEY [DELTA]
       logkv list FILE [--prefix PREFIX] [--count]
       logkv stats|compact|verify|repair|dump FILE
```

A key is 1 to 255 printable ASCII characters (`!` to `~`, no spaces). For `put`, a VALUE of `-` reads the value from standard input.

Example commands on a new file:

```
logkv put demo.kv alpha one
logkv put demo.kv beta two
logkv put demo.kv alpha uno
logkv del demo.kv beta
logkv put demo.kv gamma tres
```

`logkv stats demo.kv` prints

```
keys: 2
records: 5
file: 95 bytes
live: 43 bytes
garbage: 52 bytes (55%)
```

`logkv dump demo.kv` prints

```
4 put alpha 3
23 put beta 3
41 put alpha 3
60 del beta
75 put gamma 4
```

`logkv list demo.kv` prints `alpha` and `gamma`. With `--count` it prints only how many keys it would list, as a decimal number and a line feed: `logkv list demo.kv --count` prints `2`, `logkv list demo.kv --prefix a --count` prints `1`, and when no key matches (or the file is empty) it prints `0`. `--count` is an option of `list` only; it may stand before or after `--prefix` and may be repeated.

## Help

`logkv --help`, `logkv -h` and `logkv help` each print the usage text above and exit with 0 when they are the only argument. Anywhere else `-h` is an unknown option (`logkv: unknown option "-h"`, exit 2) and `help` is an unknown command. The usage text itself is unchanged.

## Counters

`incr` keeps a counter in a key. The value is the decimal digits of a signed 64-bit integer; a key that does not exist counts as 0. `DELTA` is the amount to add, a whole number such as `5` or `-20` (default 1), and `incr` prints the new value. A negative `DELTA` is written as it is, without `--`.

```
logkv incr visits.kv hits
logkv incr visits.kv hits
logkv incr visits.kv hits 5
logkv incr visits.kv balance -20
```

print

```
1
2
7
-20
```

Every `incr` appends one `put` record, also when `DELTA` is 0. A value that is not a whole number (`abc`, `1.5`, an empty value, a number with a plus sign or leading zeros) and a result outside the 64-bit range are errors with exit code 2 and the file stays as it is. Like `put`, `incr` creates a missing file and refuses to work on a damaged one until you run `repair`.

## File format

A file is the 4 bytes `KVL1` followed by records. An empty file of 0 bytes is a valid empty store; the magic is written with the first record.

A record is: CRC (4 bytes), type (1 byte: 1 put, 2 delete), key length (2 bytes), value length (4 bytes), the key bytes, the value bytes. All numbers are big-endian. The CRC is the standard CRC-32 (`java.util.zip.CRC32`) of the bytes from the type to the end of the value. A delete record has value length 0. A value is at most 1048576 bytes.

The whole file after `logkv put demo.kv alpha one` (the spaces are only for reading):

```
4b564c31 be0a2fa5 01 0005 00000003 616c706861 6f6e65
```

The last record of a key wins; a delete removes the key.

## Recovery

- `verify` checks the whole file and reports the first damaged record, if any.
- `repair` cuts off the damaged tail, so the file ends after its last valid record.
- `compact` rewrites the file with one record per live key.

`get`, `list`, `stats` and `dump` read the valid part of a damaged file and print a warning on stderr. `put`, `del` and `compact` refuse to work until you run `repair`.

There is no locking: use one process at a time.

## Exit codes

- 0: success.
- 1: key not found, or `verify` found damage.
- 2: usage, input or file error.

## Development

Run the tests and all checks with:

```
mvn -B -q verify
```

## License

MIT, see [LICENSE](LICENSE).

---

Built with BAKED, an open-source coding agent.
