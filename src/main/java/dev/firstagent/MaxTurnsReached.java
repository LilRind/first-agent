package dev.firstagent;

/** 连续多轮未收敛、达到 maxIterations 上限时抛出，防无限循环（hermes 计数兜底）。 */
public class MaxTurnsReached extends RuntimeException {
    public MaxTurnsReached(int maxIterations) {
        super("达到最大轮数 " + maxIterations + " 仍未收敛，已中止循环");
    }
}