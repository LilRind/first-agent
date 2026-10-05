package dev.firstagent.tools;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
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
            return decodeAtBoundary(head) + "[... 截断于 " + maxBytes + " bytes]";
        }
        if (truncatedLines) {
            result += "[... " + (lines.length - maxLines) + " more lines]";
        }
        return result.strip();
    }

    /** 严格解码，从末尾回退 0..3 字节直到输入合法，避免切断多字节 UTF-8 字符产生 U+FFFD。 */
    private static String decodeAtBoundary(byte[] bytes) {
        for (int drop = 0; drop <= 3 && drop <= bytes.length; drop++) {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                CharBuffer out = decoder.decode(ByteBuffer.wrap(bytes, 0, bytes.length - drop));
                return out.toString();
            } catch (CharacterCodingException ignored) {
                // 尝试再多回退一个尾字节。
            }
        }
        return new String(bytes, StandardCharsets.UTF_8); // 兜底：除非输入全为非法，否则不可达。
    }
}
