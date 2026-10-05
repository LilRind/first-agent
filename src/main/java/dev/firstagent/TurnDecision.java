package dev.firstagent;

/**
 * 一轮结束的裁决 —— 对齐 pi 的 finishTurn 返回 {action: end|continue}（MVP 剪掉 retry）。
 * END    = 本轮结束并返回模型文本
 * CONTINUE = 再走一轮(经外层 follow-up 衔接)
 */
public record TurnDecision(Action action) {
    public enum Action { END, CONTINUE }

    public static TurnDecision end() { return new TurnDecision(Action.END); }
    public static TurnDecision continueTurn() { return new TurnDecision(Action.CONTINUE); }
}
