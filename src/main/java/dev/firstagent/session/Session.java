package dev.firstagent.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次对话会话。由 id/cwd 标识,历史是追加式的 MessageEntry 日志。
 * 消息只能经 SessionStore.append 追加;这里只暴露只读视图,不直接改历史。
 */
public final class Session {
    private final String id;
    private final String cwd;
    private final Instant createdAt;
    private String name;
    private final List<MessageEntry> entries = new ArrayList<>();

    Session(String id, String cwd, Instant createdAt, String name) {
        this.id = id;
        this.cwd = cwd;
        this.createdAt = createdAt;
        this.name = name;
    }

    public String id() { return id; }
    public String cwd() { return cwd; }
    public Instant createdAt() { return createdAt; }
    public String name() { return name; }

    /** 会话历史只读视图;顺序即追加顺序。 */
    public List<MessageEntry> entries() {
        return List.copyOf(entries);
    }

    /** 供 SessionStore(同包)追加一条消息日志。外部只能经 SessionStore.append 调用。 */
    void append(MessageEntry entry) { entries.add(entry); }

    void rename(String name) { this.name = name; }
}