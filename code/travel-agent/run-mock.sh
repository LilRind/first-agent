#!/usr/bin/env bash
# 编译并运行（mock 模式离线演示，无需任何 API key）。
# 用法: bash run-mock.sh
set -euo pipefail
cd "$(dirname "$0")"

# 钉死到 JDK17 —— 不信任 JAVA_HOME
JAVAC="/xxx/.jdks/ms-17.0.16/bin/javac"
JAVA="/c/xxx/.jdks/ms-17.0.16/bin/java"

echo "[编译] 所有 src 下的 .java 到 out/ ..."
find src -name '*.java' > /tmp/sources.txt
"$JAVAC" -encoding UTF-8 -d out @/tmp/sources.txt

echo "[运行] mock 模式 ..."
"$JAVA" -Dfile.encoding=UTF-8 -cp out agent.Main mock
