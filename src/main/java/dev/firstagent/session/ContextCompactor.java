package dev.firstagent.session;

import dev.firstagent.Message;

import java.util.List;
import java.util.Optional;

/**
 * 上下文压缩 seam —— 第 2 点(上下文/历史管理)的插槽。
 *
 * pi 真压缩的做法(见 agent-session.研读摘注 §1/§4):判定「预估 context token 超过模型窗口」
 * 后,追加一条 compaction entry(含 firstKeptEntryId),重放时丢掉 earlier 段、换成摘要,但
 * 原始日志绝不删除。本 seam 将来在此插真压缩器(基于预估 token/窗口),返回压缩后的消息列表。
 *
 * 返回空 = 不压缩,投影用完整历史。
 */
public interface ContextCompactor {
    Optional<List<Message>> maybeCompact(Session session);
}