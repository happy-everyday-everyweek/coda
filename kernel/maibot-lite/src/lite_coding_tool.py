"""规划模型控制 Coding Agent 会话的受限宿主工具。"""

from typing import Any, Dict, List, Optional

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


def configure_host_controller(controller: Any) -> None:
    """由 Android 桥注入宿主控制器；None 表示不提供该工具。"""

    global _controller
    _controller = controller


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
                payload = json.loads(str(_controller.listSessions()))
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
                payload = json.loads(str(_controller.listModels(session_id)))
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
                        _controller.selectModel(
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
                        _controller.approveTool(
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
                payload = json.loads(str(_controller.readFile(str(invocation.arguments.get("path") or ""))))
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
                str(_controller.controlSession(action, text, session_id))
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