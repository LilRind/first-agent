package dev.firstagent.session;

import dev.firstagent.Message;

import java.time.Instant;

/**
 * 会话日志里的一条消息。带 id/parentId/timestamp 三个树字段:MVP 只沿线性链走,
 * parentId 恒指上一条(首条为 null)。以后要 fork/分支,字段现成、直接加遍历逻辑即可,不用改文件格式。
 */
public final class MessageEntry {
    static final String TYPE = "message";

    private final String id;
    private final String parentId;
    private final Instant timestamp;
    private final Message message;

    MessageEntry(String id, String parentId, Instant timestamp, Message message) {
        this.id = id;
        this.parentId = parentId;
        this.timestamp = timestamp;
        this.message = message;
    }

    public String id() { return id; }
    public String parentId() { return parentId; }
    public Instant timestamp() { return timestamp; }
    public Message message() { return message; }
}