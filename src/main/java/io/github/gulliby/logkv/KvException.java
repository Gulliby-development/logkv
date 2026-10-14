package io.github.gulliby.logkv;

/** Runtime exception whose message is the text printed after "logkv: ". */
public class KvException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public KvException(String message) {
        super(message);
    }
}
