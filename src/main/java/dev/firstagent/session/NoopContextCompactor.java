package dev.firstagent.session;

import dev.firstagent.Message;

import java.util.List;
import java.util.Optional;

/** 默认压缩器:永不压缩(no-op)。第 2 点实现真压缩时替换之。 */
public final class NoopContextCompactor implements ContextCompactor {
    @Override
    public Optional<List<Message>> maybeCompact(Session session) {
        return Optional.empty();
    }
}