package util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个极简的 JSON 解析器（仅依赖 JDK）。
 *
 * 为什么自写而不引 Jackson？这个项目目标是"零外部依赖、纯 JDK 手写 agent"，
 * 所以 HTTP 用内置 HttpClient，JSON 也用一个够用的自写解析器。它把一段 JSON 文本
 * 解析成 Map / List / String / Double / Boolean / null 的嵌套结构，够解析
 * wttr.in 天气返回和 LLM 的 choices[0].message.content 就够了。
 *
 * 真实工程里别这么干，直接用 Jackson/Gson；这里是"拆轮子看懂原理"。
 */
public final class Json {

    private Json() {}

    /** 解析一段 JSON 文本，返回嵌套的 Map/List/基础类型。 */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object value = p.value();
        p.skipWs();
        if (!p.atEnd()) {
            throw new IllegalArgumentException("JSON 末尾有多余内容");
        }
        return value;
    }

    /** 解析成对象（最常用）。 */
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("期望 JSON 对象，实际是 " + v.getClass().getSimpleName());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        return m;
    }

    // ---- 嵌套读取帮助方法，方便按路径取值 ----

    /** 从 map 里按 "a.b[0].c" 这种路径取字符串，取不到返回 null。 */
    public static String getString(Map<String, Object> root, String path) {
        Object v = get(root, path);
        return v == null ? null : String.valueOf(v);
    }

    /** 按路径取任意值。支持点号 . 和下标 [i]。 */
    public static Object get(Object node, String path) {
        Object cur = node;
        int i = 0;
        while (i < path.length()) {
            if (cur == null) return null;
            if (path.charAt(i) == '[') {
                int close = path.indexOf(']', i);
                int idx = Integer.parseInt(path.substring(i + 1, close));
                if (cur instanceof List) {
                    List<?> list = (List<?>) cur;
                    if (idx < 0 || idx >= list.size()) return null;
                    cur = list.get(idx);
                } else {
                    return null;
                }
                i = close + 1;
            } else {
                int dot = path.indexOf('.', i);
                int bracket = path.indexOf('[', i);
                int end;
                if (dot < 0 && bracket < 0) end = path.length();
                else if (dot < 0) end = bracket;
                else if (bracket < 0) end = dot;
                else end = Math.min(dot, bracket);
                String key = path.substring(i, end);
                if (!(cur instanceof Map)) return null;
                cur = ((Map<?, ?>) cur).get(key);
                i = end;
                // 跳过中间的 '.'，进入下一段
                if (i < path.length() && path.charAt(i) == '.') i++;
            }
        }
        return cur;
    }

    // ---- 内部递归下降解析器 ----

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s; }

        boolean atEnd() { return i >= s.length(); }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        char peek() {
            skipWs();
            if (atEnd()) throw new IllegalArgumentException("JSON 意外结束");
            return s.charAt(i);
        }

        Object value() {
            char c = peek();
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> keyword("true", Boolean.TRUE);
                case 'f' -> keyword("false", Boolean.FALSE);
                case 'n' -> keyword("null", null);
                default -> number();
            };
        }

        Map<String, Object> object() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peekSafe() == '}') { i++; return map; }
            while (true) {
                skipWs();
                String key = string();
                skipWs();
                expect(':');
                map.put(key, value());
                skipWs();
                char c = peekSafe();
                if (c == ',') { i++; continue; }
                if (c == '}') { i++; return map; }
                throw new IllegalArgumentException("JSON 对象语法错误，位置 " + i);
            }
        }

        List<Object> array() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peekSafe() == ']') { i++; return list; }
            while (true) {
                list.add(value());
                skipWs();
                char c = peekSafe();
                if (c == ',') { i++; continue; }
                if (c == ']') { i++; return list; }
                throw new IllegalArgumentException("JSON 数组语法错误，位置 " + i);
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw new IllegalArgumentException("JSON 转义不完整");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (i + 4 > s.length()) throw new IllegalArgumentException("JSON unicode 不完整");
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw new IllegalArgumentException("JSON 非法转义 \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("JSON 字符串未闭合");
        }

        Object number() {
            int start = i;
            if (peekSafe() == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            boolean isDouble = false;
            if (i < s.length() && s.charAt(i) == '.') { isDouble = true; i++; while (i < s.length() && Character.isDigit(s.charAt(i))) i++; }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                isDouble = true; i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            String raw = s.substring(start, i);
            return isDouble ? Double.parseDouble(raw) : Long.parseLong(raw);
        }

        Object keyword(String kw, Object v) {
            if (!s.startsWith(kw, i)) throw new IllegalArgumentException("JSON 关键字错误");
            i += kw.length();
            return v;
        }

        void expect(char c) {
            skipWs();
            if (atEnd() || s.charAt(i) != c) throw new IllegalArgumentException("期望 '" + c + "'，实际 " + (atEnd() ? "结尾" : "'" + s.charAt(i) + "'") + "，位置 " + i);
            i++;
        }

        char peekSafe() {
            skipWs();
            return atEnd() ? '\0' : s.charAt(i);
        }
    }
}
