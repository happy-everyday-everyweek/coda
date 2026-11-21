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

# 任何一套「执行失败」都要让脚本以非 0 结束：否则 jar 损坏、配置缺失、路径写错时
# 报告是空的、问题数算成 0，在 CI 或提交钩子里就是一次假通过。
FAILED=0

need_jar() {
    if [ ! -f "$1" ]; then
        echo "缺少 $1，按脚本头部注释里的地址下载到 $LINT_HOME" >&2
        FAILED=1
        return 1
    fi
}

run_ktlint() {
    need_jar "$KTLINT_JAR" || return 1
    echo "=== ktlint（格式规范）==="
    local out="$REPORT_DIR/ktlint.txt"
    # 传目录不传 glob：ktlint 自己会递归。
    # 写成 'app/src/**/*.kt' 会被它当成字面文件名，扫不到东西、还会长时间空转。
    ( cd "$ROOT" && java -jar "$KTLINT_JAR" \
        app/src kernel/src \
        --reporter=plain "$@" ) > "$out" 2>&1
    local rc=$?
    # ktlint 约定：0 无问题、1 有问题、>=2 执行失败（jar 坏、参数错等）。
    if [ "$rc" -gt 1 ]; then
        echo "!! ktlint 执行失败（退出码 $rc），详见 $out" >&2
        tail -5 "$out" >&2
        FAILED=1
        return 1
    fi
    local n
    n=$(grep -cE '\.kt:[0-9]+:[0-9]+:' "$out" || true)
    echo "问题数：$n（报告 $out）"
    # plain reporter 的行是 path:Line:Col: message (standard:rule-id)，
    # 规则名在行尾括号里，不在行首。按规则名聚合比打印前几行更有用。
    sed -nE 's/.*\(([a-z-]+:[a-z0-9-]+)\)$/\1/p' "$out" \
        | sort | uniq -c | sort -rn | head -10 || true
}

run_detekt() {
    need_jar "$DETEKT_JAR" || return 1
    echo "=== detekt（坏味道与潜在缺陷）==="
    local out="$REPORT_DIR/detekt.txt"
    local log="$REPORT_DIR/detekt-run.log"
    # 扫描范围与 ktlint 对齐：只扫 app/src/main/java 会漏掉 kernel 模块与测试源码，
    # 汇总里看起来「kernel 很干净」其实是根本没扫。
    ( cd "$ROOT" && java -jar "$DETEKT_JAR" \
        --input app/src,kernel/src \
        --config detekt.yml \
        --max-issues 0 \
        --report "txt:$out" \
        --parallel ) > "$log" 2>&1
    local rc=$?
    # detekt：0 无问题、2 达到 max-issues（这里设 0 表示不限，正常不会命中）、
    # 其余非 0 是执行失败。报告文件没生成一律按失败处理。
    if [ ! -f "$out" ]; then
        echo "!! detekt 未生成报告（退出码 $rc），详见 $log" >&2
        tail -5 "$log" >&2
        FAILED=1
        return 1
    fi
    if [ "$rc" -ne 0 ] && [ "$rc" -ne 2 ]; then
        echo "!! detekt 执行失败（退出码 $rc），详见 $log" >&2
        tail -5 "$log" >&2
        FAILED=1
        return 1
    fi
    # txt 报告的每条 finding 以 [RuleId] 结尾，计数与按规则统计用同一种匹配，
    # 不再一处按行首 `Rule - `、一处按行尾 `[Rule]`（两者互不兼容）。
    local n
    n=$(grep -cE '\[[A-Za-z0-9]+\]$' "$out" || true)
    echo "问题数：$n（报告 $out）"
    echo "按规则统计 top10："
    sed -nE 's/.*\[([A-Za-z0-9]+)\]$/\1/p' "$out" \
        | sort | uniq -c | sort -rn | head -10 || true
}

run_lint() {
    echo "=== Android Lint（平台问题）==="
    local log="$REPORT_DIR/lint-gradle.log"
    # 不用 `| head -4`：pipefail 下 head 提前关管道会让整条管道因 SIGPIPE 失败。
    ( cd "$ROOT" && ./gradlew :app:lintDebug --console=plain ) > "$log" 2>&1
    local rc=$?
    grep -E 'Lint found|BUILD' "$log" | head -4 || true
    if [ "$rc" -ne 0 ]; then
        echo "!! Android Lint 执行失败（退出码 $rc），详见 $log" >&2
        tail -5 "$log" >&2
        FAILED=1
        return 1
    fi
    # 报告路径由 build.gradle.kts 的 lint.textOutput 显式指定，
    # 不再依赖 AGP 内部的 lint_intermediate_text_report 目录结构（随版本会变）。
    local out="$REPORT_DIR/android-lint.txt"
    if [ ! -f "$out" ]; then
        echo "!! 找不到 Android Lint 报告：$out" >&2
        FAILED=1
        return 1
    fi
    local n
    n=$(grep -cE '^[^ ].*: (Error|Warning):' "$out" || true)
    echo "问题数：$n（报告 $out）"
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

# 执行失败必须以非 0 结束：报告为空不等于代码干净。
# 「发现了问题」不算失败（本仓库既有风格与 ktlint 规范差距很大，默认只报告）。
if [ "$FAILED" -ne 0 ]; then
    echo >&2
    echo "!! 有检查未能正常执行，详见 $REPORT_DIR" >&2
    exit 1
fi
