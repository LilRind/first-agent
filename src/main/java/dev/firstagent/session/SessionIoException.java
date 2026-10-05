package dev.firstagent.session;

import java.nio.file.Path;

/** 会话持久化运行时异常。 */
public final class SessionIoException extends RuntimeException {
    public SessionIoException(String op, Path path, Throwable cause) {
        super(op + (path != null ? " (" + path + ")" : "") + (cause == null ? "" : ": " + cause.getMessage()),
                cause);
    }
}