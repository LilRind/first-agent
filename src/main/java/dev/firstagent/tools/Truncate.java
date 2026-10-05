package dev.firstagent.tools;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 行数/字节截断，防止工具输出无限膨胀。定值上限：2000 行、16KB。 */
public final class Truncate {
    public static final int DEFAULT_MAX_LINES = 2000;
    public static final int DEFAULT_MAX_BYTES = 16 * 1024;

    private Truncate() {}

    /** 先按行截断，再对保留内容按字节截断；超限追加可行动提示。 */
    public static String truncate(String text, int maxLines, int maxBytes) {
        if (text == null) return "";
        String[] lines = text.split("\n", -1);
        boolean truncatedLines = lines.length > maxLines;
        int kept = Math.min(lines.length, maxLines);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kept; i++) sb.append(lines[i]).append('\n');
        String result = sb.toString();

        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            byte[] head = Arrays.copyOf(bytes, maxBytes);
            String cut = new String(head, StandardCharsets.UTF_8);
            // 剪到最后一个换行，避免切断 UTF-8 多字节字符。
            int lastNl = cut.lastIndexOf('\n');
            String safe = lastNl > 0 ? cut.substring(0, lastNl + 1) : cut;
            return safe + "[... 截断于 " + maxBytes + " bytes]";
        }
        if (truncatedLines) {
            result += "[... " + (lines.length - maxLines) + " more lines]";
        }
        return result.strip();
    }
}
