#!/usr/bin/env bash
# 纯 JDK 测试运行器:编译 src + test 到 out/,遍历运行所有 *Test 类的 main()。
# 用法: bash run-tests.sh [TestClass 过滤]
set -euo pipefail
cd "$(dirname "$0")"

# 钉死链路到 JDK 17 —— 不信任 JAVA_HOME(本机被污染为 JDK8,编不了 switch 表达式)
JAVAC="/c/Users/13374/.jdks/ms-17.0.16/bin/javac"
JAVA="/c/Users/13374/.jdks/ms-17.0.16/bin/java"

rm -rf out
mkdir -p out
find src test -name '*.java' > /tmp/srcs.txt
"$JAVAC" -encoding UTF-8 -d out @/tmp/srcs.txt

# 找出所有 *Test 类的全限定名(把 out/ 下的 .class 转成包名.类名)
FILTER="${1:-}"
fails=0
runs=0
for cls in $(find out -name '*Test.class' | sed 's#out/##; s#\.class$##; s#/#.#g' | sort); do
    if [ -n "$FILTER" ] && ! [[ "$cls" == *"$FILTER"* ]]; then
        continue
    fi
    runs=$((runs+1))
    if "$JAVA" -Dfile.encoding=UTF-8 -cp out "$cls" >/dev/null 2>&1; then
        echo "  PASS  $cls"
    else
        echo "  FAIL  $cls"
        fails=$((fails+1))
    fi
done

echo "----"
echo "ran $runs test class(es), $fails failed"
[ "$fails" -eq 0 ]
