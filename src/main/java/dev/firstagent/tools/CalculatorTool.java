package dev.firstagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.firstagent.AgentTool;
import dev.firstagent.ToolExecutionException;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * 离线确定性计算工具 —— 接收 {"expression":"(123+456)*789/12"}，返回四则运算结果。
 *
 * 自实现四则解析（shunting-yard + 双栈求值），不依赖 JDK 内嵌脚本引擎（JDK15+ 移除了 Nashorn）。
 * 仅支持 + - * / 与括号；结果格式：整数返回整型，小数 stripTrailingZeros。
 * 演示"知识不足 → 调工具 → 观察 → 总结"的 ReAct 闭环，且可离线、可断言。
 */
public class CalculatorTool implements AgentTool {
    private static final ObjectMapper M = new ObjectMapper();

    @Override public String name() { return "calculate"; }
    @Override public String description() {
        return "执行算术四则运算并返回结果。参数 JSON: {\"expression\":\"(123+456)*789/12\"}，"
                + "仅支持数字、+ - * / 与括号。用于需要精确计算的场景（避免 LLM 心算出错）。";
    }

    @Override public String execute(String argumentsJson) throws ToolExecutionException {
        JsonNode node;
        try {
            node = M.readTree(argumentsJson);
        } catch (Exception e) {
            throw new ToolExecutionException("参数不完整，请提供合法 JSON 对象 {\"expression\":\"...\"}", e);
        }
        String expr = node.path("expression").asText(null);
        if (expr == null || expr.isBlank()) {
            throw new ToolExecutionException("缺少 expression 字段，参数格式: {\"expression\":\"2+3*4\"}");
        }
        double v;
        try {
            v = evaluate(expr);
        } catch (IllegalArgumentException e) {
            throw new ToolExecutionException("表达式无法计算: " + e.getMessage(), e);
        }
        return format(v);
    }

    // --- 自实现四则解析：shunting-yard + 双栈求值 ---

    private static double evaluate(String expr) {
        String s = expr.replaceAll("\\s+", "");
        if (s.isEmpty()) throw new IllegalArgumentException("空表达式");

        List<String> tokens = tokenize(s);
        // 转为 RPN（shunting-yard），然后单栈求值
        return evalRpn(shuntingYard(tokens));
    }

    /** 逐字符切 token：数字(支持小数/负号仅作为一元)、+ - * / ( ) */
    static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if ("+-*/()".indexOf(c) >= 0) {
                out.add(String.valueOf(c));
                i++;
            } else if (Character.isDigit(c) || c == '.') {
                int j = i;
                while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) j++;
                out.add(s.substring(i, j));
                i = j;
            } else {
                throw new IllegalArgumentException("非法字符: " + c);
            }
        }
        return out;
    }

    /** shunting-yard：中缀 → 后缀（处理一元负号、运算符优先级）。 */
    static List<String> shuntingYard(List<String> tokens) {
        Deque<String> ops = new ArrayDeque<>();
        List<String> out = new ArrayList<>();
        boolean expectOperand = true;   // 一元负号判定：运算符/左括号/起点后

        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (isNumber(t)) {
                out.add(t);
                expectOperand = false;
            } else if ("(".equals(t)) {
                ops.push(t);
                expectOperand = true;
            } else if (")".equals(t)) {
                boolean found = false;
                while (!ops.isEmpty()) {
                    String top = ops.pop();
                    if ("(".equals(top)) { found = true; break; }
                    out.add(top);
                }
                if (!found) throw new IllegalArgumentException("括号不匹配");
                expectOperand = false;
            } else if (isOperator(t)) {
                // 一元负号：紧跟开头的 '-' 或在运算符/( 之后
                if ("-".equals(t) && expectOperand) { out.add("0"); }
                while (!ops.isEmpty() && isOperator(ops.peek()) && precedence(ops.peek()) >= precedence(t)) {
                    out.add(ops.pop());
                }
                ops.push(t);
                expectOperand = true;
            } else {
                throw new IllegalArgumentException("非法 token: " + t);
            }
        }
        while (!ops.isEmpty()) {
            String top = ops.pop();
            if ("(".equals(top)) throw new IllegalArgumentException("括号不匹配");
            out.add(top);
        }
        return out;
    }

    /** 后缀表达式单栈求值。 */
    static double evalRpn(List<String> rpn) {
        Deque<Double> stack = new ArrayDeque<>();
        for (String t : rpn) {
            if (isNumber(t)) {
                stack.push(Double.parseDouble(t));
            } else if (isOperator(t)) {
                if (stack.size() < 2) throw new IllegalArgumentException("运算符缺少操作数: " + t);
                double b = stack.pop(), a = stack.pop();
                switch (t) {
                    case "+" -> stack.push(a + b);
                    case "-" -> stack.push(a - b);
                    case "*" -> stack.push(a * b);
                    case "/" -> {
                        if (b == 0.0) throw new IllegalArgumentException("除数为零");
                        stack.push(a / b);
                    }
                }
            }
        }
        if (stack.size() != 1) throw new IllegalArgumentException("表达式不完整");
        return stack.pop();
    }

    /** 结果格式化：整数显示为整数，小数去掉尾随零。 */
    static String format(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) throw new IllegalArgumentException("结果不是有限数");
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString(Math.round(v));
        }
        return new BigDecimal(Double.toString(v)).stripTrailingZeros().toPlainString();
    }

    private static boolean isNumber(String t) {
        return t.chars().allMatch(c -> Character.isDigit(c) || c == '.');
    }

    private static boolean isOperator(String t) {
        return Arrays.asList("+", "-", "*", "/").contains(t);
    }

    private static int precedence(String op) {
        return switch (op) {
            case "+", "-" -> 1;
            case "*", "/" -> 2;
            default -> 0;
        };
    }
}