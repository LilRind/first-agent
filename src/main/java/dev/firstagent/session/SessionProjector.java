package dev.firstagent.session;

import dev.firstagent.Message;

import java.util.List;

/**
 * 从会话日志派生投影:把每条 MessageEntry 存的 Message 直通(passthrough)成给 LLM 的消息列表
 * (pi 式「日志即事实源」,无字段级映射)。顺序 = 追加顺序。
 * 经过上下文压缩 seam;默认(Noop)不压缩、原样返回完整历史。
 */
public final class SessionProjector {
    private final ContextCompactor compactor;

    public SessionProjector() {
        this(new NoopContextCompactor());
    }

    public SessionProjector(ContextCompactor compactor) {
        this.compactor = compactor;
    }

    public List<Message> project(Session session) {
        List<Message> full = session.entries().stream()
                .map(MessageEntry::message)
                .toList();
        return compactor.maybeCompact(session).orElse(full);
    }
}