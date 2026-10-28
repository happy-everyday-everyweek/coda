#!/usr/bin/env bash
#
# Coda 本地代码检查。
#
# 三套工具各管一段，不重叠：
#   ktlint  —— 格式规范。项目代码是 IntelliJ 默认风格，与 ktlint 官方规范差距很大，
#              首次全量扫描报了 4000+ 处。默认只报告、不自动改：真按它格式化会
#              改写全部 84 个文件，让 review 彻底失去意义。
#   detekt  —— 坏味道与潜在缺陷（吞异常、复杂条件、大类等）。
#   lint    —— Android 平台问题。这套最有价值：权限、API 级别、资源引用，
#              都是会在真机上直接崩的东西。
#
# 用法：
#   tools/lint.sh                   跑三套，末尾给汇总
#   tools/lint.sh ktlint            只跑某套（ktlint / detekt / lint）
#   tools/lint.sh ktlint --format   按 ktlint 规范自动格式化
#
# 依赖的 jar 不在仓库里（两个 all-jar 各约 70MB）。安装在 /opt/lint，
# 也可用环境变量 LINT_HOME 指到别处。首次使用按下面地址下载：
#   https://mirrors.huaweicloud.com/repository/maven/com/pinterest/ktlint/ktlint-cli/1.8.0/ktlint-cli-1.8.0-all.jar
#   https://mirrors.huaweicloud.com/repository/maven/io/gitlab/arturbosch/detekt/detekt-cli/1.23.8/detekt-cli-1.23.8-all.jar
# 走华为云镜像是因为这台机器上 github.com 不通，repo1.maven.org 虽能连但慢得多。

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LINT_HOME="${LINT_HOME:-/opt/lint}"
KTLINT_JAR="$LINT_HOME/ktlint-cli-1.8.0-all.jar"
DETEKT_JAR="$LINT_HOME/detekt-cli-1.23.8-all.jar"
REPORT_DIR="$ROOT/build/lint-reports"
WHICH="${1:-all}"
shift 2>/dev/null || true

mkdir -p "$REPORT_DIR"

need_jar() {
    if [ ! -f "$1" ]; then
        echo "缺少 $1，按脚本头部注释里的地址下载到 $LINT_HOME" >&2
        return 1
    fi
}

run_ktlint() {
    need_jar "$KTLINT_JAR" || return 1
    echo "=== ktlint（格式规范）==="
    local out="$REPORT_DIR/ktlint.txt"
    ( cd "$ROOT" && java -jar "$KTLINT_JAR" \
        'app/src/**/*.kt' 'kernel/src/**/*.kt' \
        --reporter=plain "$@" ) > "$out" 2>&1
    local n
    n=$(grep -cE '\.kt:[0-9]+:[0-9]+:' "$out" || true)
    echo "问题数：$n（报告 $out）"
    grep -E '^\s+standard:' "$out" | head -10
}

run_detekt() {
    need_jar "$DETEKT_JAR" || return 1
    echo "=== detekt（坏味道与潜在缺陷）==="
    local out="$REPORT_DIR/detekt.txt"
    ( cd "$ROOT" && java -jar "$DETEKT_JAR" \
        --input app/src/main/java \
        --build-upon-default-config \
        --max-issues 0 \
        --report "txt:$out" \
        --parallel ) > /dev/null 2>&1
    local n
    n=$(grep -cE '^[A-Za-z]+ - ' "$out" || true)
    echo "问题数：$n（报告 $out）"
    echo "按规则统计 top10："
    sed -nE 's/.*\[([A-Za-z]+)\]$/\1/p' "$out" | sort | uniq -c | sort -rn | head -10
}

run_lint() {
    echo "=== Android Lint（平台问题）==="
    ( cd "$ROOT" && ./gradlew :app:lintDebug --console=plain ) 2>&1 \
        | tee "$REPORT_DIR/lint-gradle.log" \
        | grep -E 'Lint found|BUILD' | head -4
    echo "报告：$ROOT/app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt"
}

case "$WHICH" in
    ktlint) run_ktlint "$@" ;;
    detekt) run_detekt "$@" ;;
    lint)   run_lint "$@" ;;
    all)
        run_ktlint
        echo
        run_detekt
        echo
        run_lint
        ;;
    *)
        echo "未知目标：$WHICH（可选 ktlint / detekt / lint / all）" >&2
        exit 2
        ;;
esac
