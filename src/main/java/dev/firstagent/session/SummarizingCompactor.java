package dev.firstagent.session;

import dev.firstagent.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 朴素折叠压缩 —— 实现 ContextCompactor seam。Token 估算简化为「字符数/4」。
 * 预算内 → 不压缩；超预算且消息足够多 → 把最旧(超出 KEEP_RECENT)的消息折叠成一条
 * "[压缩摘要]" system 消息前置，保留最新 KEEP_RECENT 条原文。原始输入列表绝不修改。
 * 同时暴露 compact(List<Message>)，供 prepareRequest 直接对「发给模型的请求」压缩，不依赖持久化。
 */
public final class SummarizingCompactor implements ContextCompactor {

    /** 保留最新 N 条原文，更早的全部折叠进摘要。 */
    static final int KEEP_RECENT = 4;
    private static final int CHARS_PER_TOKEN = 4;

    private final int budgetTokens;

    public SummarizingCompactor(int budgetTokens) {
        this.budgetTokens = budgetTokens;
    }

    /** 直接作用于消息列表的压缩入口（prepareRequest 用它，不依赖持久化）。 */
    public Optional<List<Message>> compact(List<Message> messages) {
        int tokens = messages.stream().mapToInt(this::sizeTokens).sum();
        if (tokens <= budgetTokens) return Optional.empty();
        if (messages.size() <= KEEP_RECENT) return Optional.empty();

        List<Message> older = messages.subList(0, messages.size() - KEEP_RECENT);
        List<Message> recent = messages.subList(messages.size() - KEEP_RECENT, messages.size());
        String summaryText = older.stream()
                .map(Message::text).filter(s -> s != null && !s.isBlank())
                .reduce((a, b) -> a + "\n" + b).orElse("(无文本)");

        List<Message> out = new ArrayList<>();
        out.add(Message.system("[压缩摘要] " + summaryText));
        out.addAll(recent);
        return Optional.of(out);
    }

    /** ContextCompactor seam：适配会话日志投影。 */
    @Override
    public Optional<List<Message>> maybeCompact(Session session) {
        return compact(session.entries().stream().map(MessageEntry::message).toList());
    }

    private int sizeTokens(Message m) {
        String t = m.text();
        return Math.max(1, t.length() / CHARS_PER_TOKEN);
    }
}