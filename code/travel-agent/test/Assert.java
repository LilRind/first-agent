package test;

/**
 * 纯 JDK 最小断言工具 —— 稳定项目无 JUnit / Maven，用静态断言凑合。
 *
 * 用法:失败直接抛 AssertionError(带原因)，让测试进程以非零码退出。
 * run-tests.sh 遍历 test/ 下所有 *Test 的 main()，一个失败即整批判红。
 */
public final class Assert {

    private Assert() {}

    public static void equal(String expected, String actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("expected=<" + expected + "> actual=<" + actual + ">");
        }
    }

    public static void equal(boolean expected, boolean actual) {
        if (expected != actual) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }

    public static void equal(int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }

    public static void equal(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + " (expected=" + expected + " actual=" + actual + ")");
        }
    }

    public static void true_(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void equalsContract(Object a, Object b, Object different) {
        // 自反
        true_(a.equals(a), "reflexive failed");
        // 对称 + 相等对象相等
        true_(a.equals(b) && b.equals(a), "equal objects not symmetric");
        // 不同对象不等
        true_(!a.equals(different), "different objects are equal");
        // hashCode 一致性
        true_(a.hashCode() == b.hashCode(), "equal objects differ in hashCode");
    }
}