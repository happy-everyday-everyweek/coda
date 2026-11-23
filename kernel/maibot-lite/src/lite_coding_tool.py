"""规划模型控制 Coding Agent 会话的受限宿主工具。"""

from typing import Any, Dict, List, Optional

import asyncio
import json

from src.core.tooling import (
    ToolAvailabilityContext,
    ToolExecutionContext,
    ToolExecutionResult,
    ToolInvocation,
    ToolProvider,
    ToolSpec,
)

_controller: Any = None

# context_run_terminal 对模型宣称是「只读检查」，此前只有提示词约束，实际能执行任意命令。
# 这里把声明落到实现：只放行只读命令，拒绝重定向、命令串联与子命令替换。
_READONLY_COMMANDS = frozenset(
    {
        "grep",
        "rg",
        "ls",
        "cat",
        "head",
        "tail",
        "wc",
        "find",
        "pwd",
        "file",
        "stat",
        "diff",
        "du",
        "tree",
        "sort",
        "uniq",
        "cut",
        "tr",
        "echo",
        "which",
        "basename",
        "dirname",
        "realpath",
    }
)

# git 只能跑这些只读子命令；clean / reset / checkout / apply 等会改工作区，一律拒绝。
_GIT_READONLY_SUBCOMMANDS = frozenset(
    {
        "status",
        "diff",
        "log",
        "show",
        "branch",
        "rev-parse",
        "ls-files",
        "grep",
        "blame",
        "remote",
        "describe",
        "shortlog",
        "cat-file",
        "count-objects",
        "diff-tree",
        "ls-tree",
        "merge-base",
        "name-rev",
        "rev-list",
        "show-ref",
        "tag",
    }
)

# 这些写法能借只读命令写盘或执行命令，按出现即拒绝处理。
_BANNED_FLAGS = (
    "-delete",
    "-exec",
    "-execdir",
    "-ok",
    "-okdir",
    "-fprint",
    "-fls",
    "--output",
    "--in-place",
    # 这些能让只读命令起进程或调用分页器执行任意命令。
    "--open-files-in-pager",
)
# 必须按**完整 token**匹配的禁用选项（#794）。
# `--pre` 用子串匹配会连带命中 `--pretty` / `--pretty=format:...`，
# 而那是 git log/diff/show 最常用的只读选项，等于把这些命令全禁掉。
_BANNED_EXACT_FLAGS = ("--pre",)

_SHELL_WRITE_TOKENS = (">", "<", "`", "$(", "${", "&&", "||", ";", "\n", "\r")


# git branch / tag 只读列出时允许出现的选项（#795）。
# 白名单而非黑名单：写操作的花样太多（长选项、位置参数、缩写），
# 黑名单永远追不完，而列出用法是有限且稳定的。
_BRANCH_LIST_FLAGS = {
    "-a", "--all", "-r", "--remotes", "-l", "--list", "-v", "-vv", "--verbose",
    "--show-current", "--contains", "--no-contains", "--merged", "--no-merged",
    "--points-at", "--sort", "--format", "--color", "--no-color", "--column",
    "--no-column", "--abbrev", "--no-abbrev", "-q", "--quiet", "--ignore-case",
}
_TAG_LIST_FLAGS = {
    "-l", "--list", "-n", "--contains", "--no-contains", "--merged", "--no-merged",
    "--points-at", "--sort", "--format", "--color", "--no-color", "--column",
    "--no-column", "-i", "--ignore-case", "--omit-empty",
}
# 这些选项后面跟一个值（提交、格式串），值本身不是位置参数。
# 注意 --list/-l 不在这里：它们是布尔模式开关，模式是**位置参数**，
# 把它们当吃值选项会盲目吞掉下一个 token——`--list --unset-upstream`
# 里的写选项就被当成「值」放过去了（OCR high）。
_REF_VALUE_FLAGS = {
    "--contains", "--no-contains", "--merged", "--no-merged", "--points-at",
    "--sort", "--format", "--abbrev", "-n",
}
# --color[=<when>] / --column[=<options>] 的可选值只能用 `=` 附着，
# 裸用时不消费下一个 token，所以它们不在 _REF_VALUE_FLAGS 里（OCR high）。
def _ref_listing_violation(sub: str, args: list) -> Optional[str]:
    """branch/tag 只允许「列出」用法；其余一律拒绝。"""
    allowed = _BRANCH_LIST_FLAGS if sub == "branch" else _TAG_LIST_FLAGS
    # 列出模式（`--list v*` / `-l 'release-*'`）是位置参数，但只在
    # 显式出现过 --list/-l 之后才允许，且必须跟在它后面。
    expect_pattern = False
    skip_next = False
    for arg in args:
        if skip_next:
            skip_next = False
            # 吃值选项的值不得长得像选项：`--sort --delete` 里的 --delete
            # 不是值而是写操作，吞掉它等于放行（OCR high）。
            if arg.startswith("-"):
                return f"只读检查不允许：git {sub} 的选项值不能是另一个选项（{arg}）"
            continue
        if not arg.startswith("-"):
            if expect_pattern:
                # --list/-l 后面的模式：合法的列出用法。
                expect_pattern = False
                continue
            # 位置参数：`git branch new HEAD` / `git tag v1` 都是创建。
            return f"只读检查不允许：git {sub} 只能列出，不能带位置参数（那会创建或删除引用）"
        name = arg.split("=", 1)[0]
        if name not in allowed:
            return f"只读检查不允许 git {sub} {name}（只接受列出类选项）"
        if name in {"--list", "-l"}:
            # `--list=v*` 自带模式；裸 --list 允许后面跟一个模式。
            expect_pattern = "=" not in arg
            continue
        expect_pattern = False
        # `--sort=xxx` 自带值；`--sort xxx` 的值在下一个 token。
        # --color/--column 的可选值只能 = 附着，裸用不吞下一个。
        if "=" not in arg and name in _REF_VALUE_FLAGS:
            skip_next = True
    return None


def _readonly_violation(command: str) -> Optional[str]:
    """返回拒绝原因；None 表示这条命令可以执行。"""

    text = command.strip()
    if not text:
        return "命令为空"
    for token in _SHELL_WRITE_TOKENS:
        if token in text:
            return f"只读检查不允许出现 {repr(token)}（重定向、命令串联、子命令替换或换行）"
    lowered = text.lower()
    for flag in _BANNED_FLAGS:
        if flag in lowered:
            return f"只读检查不允许使用 {flag}"
    # 命令经 /bin/bash -lc 执行：bash 会剥掉引号与反斜杠后才把参数交给程序。
    # 按原始空白切分会把 `'--pre=evil'`、`--p're'` 当成无害 token 放行（OCR high）。
    # 用 shlex 按 POSIX 规则分词；引号不闭合等分词失败一律拒绝（fail-closed）。
    # 但 shlex 不建模 brace expansion 与变量词拆分（OCR 复审 high）：
    # `--list {victim,-d}` 分词后是一个无害模式，bash 展开后却是删分支。
    # 只读检查用不到这些展开，出现花括号/美元符一律拒绝。
    # 波浪号只在 token 开头才是家目录展开：`HEAD~1` 是合法 revision，不能误杀。
    for ch in ("{", "}", "$"):
        if ch in text:
            return f"只读检查不允许出现 {ch}（brace/变量展开无法在守卫侧核实）"
    # 路径通配同样不建模（OCR r5 high）：`git diff *` 里若工作区有名为
    # `--output=evil` 的文件（Coding Agent 自己就能创建），bash 展开后
    # 它变成一个新 token 被目标程序当选项解析。
    # 但 `git tag --list v*` 的模式是合法列出用法，不能误杀：
    # 只拒绝「独立成词」或「以通配开头」的 token——展开结果落在词首的
    # 才可能被当选项；`v*`、`--sort=*` 这类值内通配交给程序自己处理。
    import shlex as _shlex
    try:
        _raw_tokens = _shlex.split(text, comments=False, posix=True)
    except ValueError:
        return "只读检查不允许：命令引号不闭合或无法按 shell 规则分词"
    for _tok in _raw_tokens:
        if _tok and _tok[0] in "*?[":
            return "只读检查不允许以通配符开头的词（展开后的词无法在守卫侧核实）"
        # 以 - 开头且含通配的 token 同样危险（OCR r6 high）：`git diff -*`
        # 展开后可能变成 --output=evil 这样的选项。选项名本身不含通配，
        # 含通配的「选项」一定是 glob，拒绝。
        if _tok.startswith("-") and any(c in _tok for c in "*?["):
            return "只读检查不允许含通配的选项形态（展开后可能变成任意选项）"
    import re
    if re.search(r"(^|\s)~", text):
        return "只读检查不允许以 ~ 开头的路径（家目录展开无法在守卫侧核实）"
    import shlex
    try:
        parts = shlex.split(text, comments=False, posix=True)
    except ValueError:
        return "只读检查不允许：命令引号不闭合或无法按 shell 规则分词"
    if not parts:
        return "命令为空"
    # 按完整 token 判：`--pre` 与 `--pre=x` 拒绝，`--pretty` 放行（#794）。
    for token in parts:
        low = token.lower()
        for flag in _BANNED_EXACT_FLAGS:
            if low == flag or low.startswith(flag + "="):
                return f"只读检查不允许使用 {flag}"
    first = parts[0].split("/")[-1]
    # sort/tree/uniq/du 的 -o 会把结果写进文件，属于写操作。
    if first in {"sort", "tree", "uniq", "du"} and "-o" in parts[1:]:
        return f"{first} -o 会写文件，只读检查不允许"
    if first == "git":
        if len(parts) < 2 or parts[1].startswith("-"):
            return "git 需要明确的只读子命令，例如 git status / git diff / git log"
        if parts[1] not in _GIT_READONLY_SUBCOMMANDS:
            return f"git {parts[1]} 会修改仓库，只读检查不允许"
        args = parts[2:]
        # branch/tag 既可能只读列出，也可能写 refs。
        #
        # 改成**白名单**（#795）：原来是黑名单，只挡了几个短选项，
        # `--delete/--move/--copy/--force/--annotate/--sign/--message/--file`
        # 以及位置参数建分支（`git branch new HEAD`）全部绕过。
        # 这个函数是唯一的屏障——命令最终经 /bin/bash -lc 在宿主环境执行。
        if parts[1] in {"branch", "tag"}:
            why = _ref_listing_violation(parts[1], args)
            if why:
                return why
        if "-O" in args:
            return "只读检查不允许 -O（会启动分页器执行命令）"
        return None
    if first not in _READONLY_COMMANDS:
        return f"只读检查不允许执行 {first}；可用命令：grep、ls、cat、head、tail、wc、find、git（只读子命令）等"
    return None


def configure_host_controller(controller: Any) -> None:
    """由 Android 桥注入宿主控制器；None 表示不提供该工具。"""

    global _controller
    _controller = controller


def get_host_controller() -> Any:
    """读当前注入的控制器，供桥判断自己是否还是最新的注入方。"""

    return _controller


def get_read_file_spec() -> ToolSpec:
    return ToolSpec(
        name="context_read_file",
        description=(
            "只为补充上下文或核验 Coding Agent 成果而读取文件，不是用来替用户完成任务的执行工具。"
            "只能读取附件目录或当前工作区内文件，单次最多 1MB。"
        ),
        parameters_schema={
            "type": "object",
            "properties": {"path": {"type": "string", "description": "要核验的文件路径。"}},
            "required": ["path"],
        },
        provider_name="coda_android",
        provider_type="builtin",
    )


def get_terminal_spec() -> ToolSpec:
    return ToolSpec(
        name="context_run_terminal",
        description=(
            "只在当前工作区运行只读检查命令，用于核验 Coding Agent 的成果或补充上下文。"
            "不得用它直接替用户完成开发任务，不得执行破坏性命令、后台常驻服务或交互式程序。"
            "实现层有白名单：可用 grep、rg、ls、cat、head、tail、wc、find（不带 -delete/-exec）、"
            "git 的只读子命令（status/diff/log/show/branch 等）；出现重定向、命令串联、换行或其它命令会被直接拒绝。"
        ),
        parameters_schema={
            "type": "object",
            "properties": {
                "command": {"type": "string", "description": "例如 git diff、grep、测试命令。"},
                "timeout_seconds": {"type": "integer", "minimum": 1, "maximum": 30},
            },
            "required": ["command"],
        },
        provider_name="coda_android",
        provider_type="builtin",
    )


def get_tool_spec() -> ToolSpec:
    """声明会话生命周期控制，可指定任意会话。"""

    return ToolSpec(
        name="coding_agent",
        description=(
            "控制本机 Coding Agent 会话。create 新建编码会话；send 向会话发送任务；"
            "stop 停止会话生成；close 关闭会话。省略 session_id 时作用于用户当前打开的会话，"
            "指定 session_id 时作用于对应会话。不会执行任意 Shell。"
        ),
        parameters_schema={
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["create", "send", "stop", "close"]},
                "text": {"type": "string", "description": "action=send 时发送的任务文本。"},
                "session_id": {
                    "type": "string",
                    "description": "目标会话 id；先用 session_list 查。省略则用当前打开的会话。",
                },
            },
            "required": ["action"],
        },
        provider_name="coda_android",
        provider_type="builtin",
    )


def get_session_list_spec() -> ToolSpec:
    return ToolSpec(
        name="session_list",
        description=(
            "列出本机 Coding Agent 的会话（id、标题、状态、更新时间）以及当前打开的会话 id。"
            "需要控制某个会话前先用它拿到 session_id。"
        ),
        parameters_schema={"type": "object", "properties": {}},
        provider_name="coda_android",
        provider_type="builtin",
    )


def get_model_list_spec() -> ToolSpec:
    return ToolSpec(
        name="model_list",
        description=(
            "列出指定 Coding Agent 会话可用的模型（provider_id、供应商名、模型名）。"
            "不改变 Personal Agent 的规划模型或回复模型；session_id 省略时使用当前打开的会话。"
        ),
        parameters_schema={
            "type": "object",
            "properties": {"session_id": {"type": "string", "description": "目标 Coding Agent 会话 id。"}},
        },
        provider_name="coda_android",
        provider_type="builtin",
    )


def _format_session_list(payload: Dict[str, Any]) -> str:
    """把会话清单整理成 planner 易读的文本。"""

    current = str(payload.get("current_session_id") or "")
    lines: List[str] = []
    for item in payload.get("sessions") or []:
        if not isinstance(item, dict):
            continue
        session_id = str(item.get("id") or "")
        title = str(item.get("title") or "未命名会话")
        status = str(item.get("status") or "unknown")
        mark = "（当前打开）" if session_id and session_id == current else ""
        lines.append(f"- {session_id}｜{title}｜{status}{mark}")
    if not lines:
        return "当前没有 Coding Agent 会话。"
    return "\n".join(lines)


def _format_model_list(payload: Dict[str, Any]) -> str:
    """把会话可用模型整理成 planner 易读的文本。"""

    lines: List[str] = []
    for item in payload.get("models") or []:
        if not isinstance(item, dict):
            continue
        provider_id = str(item.get("provider_id") or "")
        model_id = str(item.get("model") or "")
        label = str(item.get("label") or model_id)
        default_level = str(item.get("default_level") or "")
        level_note = f"，默认档位 {default_level}" if default_level else ""
        lines.append(f"- {provider_id} / {model_id}（{label}{level_note}）")
    if not lines:
        return "该会话没有可用模型。"
    return "\n".join(lines)


def get_approve_spec() -> ToolSpec:
    return ToolSpec(
        name="approve_tool",
        description=(
            "应答 Coding Agent 的工具审批请求。decision=allow 放行，decision=deny 拒绝。"
            "只有收到审批请求后才能调用。"
        ),
        parameters_schema={
            "type": "object",
            "properties": {
                "request_key": {"type": "string", "description": "审批事件提供的请求标识。"},
                "decision": {"type": "string", "enum": ["allow", "deny"]},
            },
            "required": ["request_key", "decision"],
        },
        provider_name="coda_android",
        provider_type="builtin",
    )


def get_model_select_spec() -> ToolSpec:
    return ToolSpec(
        name="model_select",
        description=(
            "切换指定 Coding Agent 会话使用的模型。session_id 省略时作用于用户当前打开的会话；"
            "provider_id 与 model 必须来自 model_list。"
        ),
        parameters_schema={
            "type": "object",
            "properties": {
                "session_id": {
                    "type": "string",
                    "description": "目标会话 id；省略时用用户当前打开的会话。",
                },
                "provider_id": {"type": "string"},
                "model": {"type": "string"},
                "level": {
                    "type": "string",
                    "description": "推理档位；省略时用该模型的默认档位。",
                },
            },
            "required": ["provider_id", "model"],
        },
        provider_name="coda_android",
        provider_type="builtin",
    )


class CodingAgentToolProvider(ToolProvider):
    """只向规划模型暴露 Coding Agent 会话边界。"""

    provider_name = "coda_android"
    provider_type = "builtin"

    async def list_tools(
        self,
        context: Optional[ToolAvailabilityContext] = None,
    ) -> List[ToolSpec]:
        del context
        # Personal Agent 固定单会话私聊，工具不随会话类型裁剪；这里显式声明可见性，
        # 与内置工具的元数据约定保持一致。
        if _controller is None:
            return []
        specs = [
            get_tool_spec(),
            get_session_list_spec(),
            get_model_list_spec(),
            get_model_select_spec(),
            get_approve_spec(),
            get_read_file_spec(),
            get_terminal_spec(),
        ]
        for spec in specs:
            spec.metadata.setdefault("visibility", "visible")
        return specs

    async def invoke(
        self,
        invocation: ToolInvocation,
        context: Optional[ToolExecutionContext] = None,
    ) -> ToolExecutionResult:
        del context
        if _controller is None:
            return ToolExecutionResult(
                tool_name=invocation.tool_name,
                success=False,
                error_message="当前宿主没有 Coding Agent 控制器。",
            )
        if invocation.tool_name == "session_list":
            try:
                payload = json.loads(str(await asyncio.to_thread(_controller.listSessions)))
            except Exception as exc:
                return ToolExecutionResult(
                    tool_name=invocation.tool_name, success=False, error_message=str(exc)
                )
            if not payload.get("ok"):
                return ToolExecutionResult(
                    tool_name=invocation.tool_name,
                    success=False,
                    error_message=str(payload.get("message") or "读取会话列表失败"),
                )
            return ToolExecutionResult(
                tool_name=invocation.tool_name,
                success=True,
                content=_format_session_list(payload),
            )
        if invocation.tool_name == "model_list":
            session_id = str(invocation.arguments.get("session_id") or "").strip()
            try:
                payload = json.loads(str(await asyncio.to_thread(_controller.listModels, session_id)))
            except Exception as exc:
                return ToolExecutionResult(
                    tool_name=invocation.tool_name, success=False, error_message=str(exc)
                )
            if not payload.get("ok"):
                return ToolExecutionResult(
                    tool_name=invocation.tool_name,
                    success=False,
                    error_message=str(payload.get("message") or "读取模型列表失败"),
                )
            return ToolExecutionResult(
                tool_name=invocation.tool_name,
                success=True,
                content=_format_model_list(payload),
            )
        if invocation.tool_name == "model_select":
            try:
                payload = json.loads(
                    str(
                        await asyncio.to_thread(
                            _controller.selectModel,
                            str(invocation.arguments.get("session_id") or ""),
                            str(invocation.arguments.get("provider_id") or ""),
                            str(invocation.arguments.get("model") or ""),
                            str(invocation.arguments.get("level") or ""),
                        )
                    )
                )
            except Exception as exc:
                return ToolExecutionResult(
                    tool_name=invocation.tool_name, success=False, error_message=str(exc)
                )
            return ToolExecutionResult(
                tool_name=invocation.tool_name,
                success=bool(payload.get("ok")),
                content=str(payload.get("message") or ""),
                error_message=None if payload.get("ok") else str(payload.get("message") or "切换失败"),
            )
        if invocation.tool_name == "approve_tool":
            try:
                payload = json.loads(
                    str(
                        await asyncio.to_thread(
                            _controller.approveTool,
                            str(invocation.arguments.get("request_key") or "").strip(),
                            str(invocation.arguments.get("decision") or ""),
                        )
                    )
                )
            except Exception as exc:
                return ToolExecutionResult(
                    tool_name=invocation.tool_name, success=False, error_message=str(exc)
                )
            return ToolExecutionResult(
                tool_name=invocation.tool_name,
                success=bool(payload.get("ok")),
                content=str(payload.get("message") or ""),
                error_message=None if payload.get("ok") else str(payload.get("message") or "审批失败"),
            )
        if invocation.tool_name == "context_read_file":
            try:
                payload = json.loads(
                    str(await asyncio.to_thread(_controller.readFile, str(invocation.arguments.get("path") or "")))
                )
            except Exception as exc:
                return ToolExecutionResult(
                    tool_name=invocation.tool_name, success=False, error_message=str(exc)
                )
            return ToolExecutionResult(
                tool_name=invocation.tool_name,
                success=bool(payload.get("ok")),
                content=str(payload.get("content") or payload.get("message") or ""),
                error_message=None if payload.get("ok") else str(payload.get("message") or "读取失败"),
            )
        if invocation.tool_name == "context_run_terminal":
            command = str(invocation.arguments.get("command") or "")
            violation = _readonly_violation(command)
            if violation:
                return ToolExecutionResult(
                    tool_name=invocation.tool_name, success=False, error_message=violation
                )
            try:
                payload = json.loads(
                    str(
                        await asyncio.to_thread(
                            _controller.runTerminal,
                            command,
                            int(invocation.arguments.get("timeout_seconds") or 20),
                        )
                    )
                )
            except Exception as exc:
                return ToolExecutionResult(
                    tool_name=invocation.tool_name, success=False, error_message=str(exc)
                )
            return ToolExecutionResult(
                tool_name=invocation.tool_name,
                success=bool(payload.get("ok")),
                content=str(payload.get("output") or payload.get("message") or ""),
                error_message=None if payload.get("ok") else str(payload.get("message") or "命令失败"),
            )
        action = str(invocation.arguments.get("action") or "").strip().lower()
        text = str(invocation.arguments.get("text") or "").strip()
        session_id = str(invocation.arguments.get("session_id") or "").strip()
        if action not in {"create", "send", "stop", "close"}:
            return ToolExecutionResult(
                tool_name=invocation.tool_name,
                success=False,
                error_message=f"不支持的 Coding Agent 动作：{action}",
            )
        if action == "send" and not text:
            return ToolExecutionResult(
                tool_name=invocation.tool_name,
                success=False,
                error_message="action=send 需要 text。",
            )
        try:
            response: Dict[str, Any] = json.loads(
                str(await asyncio.to_thread(_controller.controlSession, action, text, session_id))
            )
        except Exception as exc:
            return ToolExecutionResult(
                tool_name=invocation.tool_name,
                success=False,
                error_message=str(exc),
            )
        ok = bool(response.get("ok"))
        message = str(response.get("message") or ("完成" if ok else "操作失败"))
        return ToolExecutionResult(
            tool_name=invocation.tool_name,
            success=ok,
            content=message,
            error_message=None if ok else message,
        )

    async def close(self) -> None:
        return None


def get_provider() -> CodingAgentToolProvider:
    return CodingAgentToolProvider()