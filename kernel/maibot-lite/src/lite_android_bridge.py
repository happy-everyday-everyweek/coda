"""Android 宿主桥：先写模型配置，再加载 MaiBot 业务模块。"""

from pathlib import Path
from typing import Any, Dict

import json
import os


def _toml_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)


def _client_type(item: Dict[str, Any]) -> str:
    """把宿主的 api_type 映射成内核的 client_type（#34）。

    不传该字段时按 openai 处理，兼容旧版宿主。未知协议显式返回 unsupported，
    让上层给出可读失败，而不是伪装成 openai 再拿一个难以理解的 400。
    """

    raw = str(item.get("api_type") or "").strip().lower()
    if not raw:
        return "openai"
    # responses 必须先判（#796）：`startswith("openai")` 会把 openai-responses
    # 悄悄降级成普通 openai，两种协议的请求体完全不同。
    if "responses" in raw:
        return "openai_responses"
    if raw.startswith("openai"):
        return "openai"
    if raw.startswith("gemini"):
        return "gemini"
    # 这个 lite 内核**没有注册 anthropic 客户端**（#796）：
    # client_registry 只有 openai / openai_responses / gemini。
    # 映射成 "anthropic" 会在运行期抛 KeyError，比早报 unsupported 更难排查。
    # Anthropic 供应商需要经 OpenAI 兼容网关接入，宿主侧应把 api_type 填 openai。
    return "unsupported"


def _model_capability_lines(item: Dict[str, Any]) -> list:
    """ModelInfo 真正消费的能力字段（#797）。

    只写 schema 里存在的键。`max_tokens` 是**输出**上限，不是上下文窗口；
    宿主给的 context_window 是输入窗口，语义不同，不能拿来填这里。
    上下文条数由全局 chat.max_context_size 控制，工具能力内核没有模型级开关，
    两者都不在模型表里表达。
    """
    out = []
    limit = int(item.get("max_output_tokens") or 0)
    if limit > 0:
        out.append(f"max_tokens = {limit}")
    return out


def _auth_fields(item: Dict[str, Any]) -> Dict[str, str]:
    """鉴权头随协议与凭据来源变化（#34）。

    OAuth 供应商的凭据在本地网关，api_key 为空；这时不能再带
    `Authorization: Bearer`（空令牌会被上游直接拒绝）。
    """

    # 按**原始 api_type** 判鉴权形状，不看 _client_type（#796）：
    # anthropic 现在映射为 unsupported（内核没有对应客户端），
    # 但经 OpenAI 兼容网关接入时仍需要 x-api-key 这个头形状。
    raw = str(item.get("api_type") or "").strip().lower()
    has_key = bool(str(item.get("api_key") or "").strip())
    if not has_key:
        return {
            "auth_type": "none",
            "auth_header_name": "Authorization",
            "auth_header_prefix": "",
            "auth_query_name": "api_key",
        }
    if raw.startswith("anthropic"):
        return {
            "auth_type": "header",
            "auth_header_name": "x-api-key",
            "auth_header_prefix": "",
            "auth_query_name": "api_key",
        }
    return {
        "auth_type": "bearer",
        "auth_header_name": "Authorization",
        "auth_header_prefix": "Bearer",
        "auth_query_name": "api_key",
    }


def _write_model_config(root: Path, planner: Dict[str, Any], reply: Dict[str, Any]) -> None:
    """生成只属于 Personal Agent 的模型配置，不读取 Coda 的 Coding Agent 配置。"""

    config_dir = root / "config"
    config_dir.mkdir(parents=True, exist_ok=True)
    planner_provider = f"{planner['name']} · 规划"
    reply_provider = f"{reply['name']} · 回复"
    lines = [
        "[inner]",
        'version = "1.17.24"',
        "",
        "[web]",
        "preview_chars = 4000",
        "services = {}",
        "",
    ]
    for provider_name, item in ((planner_provider, planner), (reply_provider, reply)):
        auth = _auth_fields(item)
        lines.extend(
            [
                "[[api_providers]]",
                f"name = {_toml_string(provider_name)}",
                f"base_url = {_toml_string(item['base_url'])}",
                f"api_key = {_toml_string(item['api_key'])}",
                f"client_type = {_toml_string(_client_type(item))}",
                f"auth_type = {_toml_string(auth['auth_type'])}",
                f"auth_header_name = {_toml_string(auth['auth_header_name'])}",
                f"auth_header_prefix = {_toml_string(auth['auth_header_prefix'])}",
                f"auth_query_name = {_toml_string(auth['auth_query_name'])}",
                "default_headers = {}",
                "default_query = {}",
                'model_list_endpoint = "/models"',
                'reasoning_parse_mode = "auto"',
                'tool_argument_parse_mode = "auto"',
                "max_retry = 2",
                "timeout = 180",
                "retry_interval = 4",
                "",
            ]
        )
    for model_name, provider_name, item in (
        ("personal_planner", planner_provider, planner),
        ("personal_reply", reply_provider, reply),
    ):
        lines.extend(
            [
                "[[models]]",
                'model_type = "llm"',
                f"model_identifier = {_toml_string(item['model'])}",
                f"name = {_toml_string(model_name)}",
                f"api_provider = {_toml_string(provider_name)}",
                # 这里曾经写 max_context_size / supports_tool_call（#797）。
                # 两个键都不在 ModelInfo 的 schema 里，ConfigBase.from_dict 每次加载
                # 都把它们当冗余属性剥掉并打日志——那段「修复」是空操作。
                # 内核的真实情况：上下文预算是全局 chat.max_context_size（**消息条数**，
                # 默认 30），不是模型级的 token 窗口；工具能力也没有模型级开关。
                # 所以这里只写 ModelInfo 真正消费的字段，窗口与工具能力另行处理。
                *_model_capability_lines(item),
                "price_in = 0.0",
                "cache = false",
                "cache_price_in = 0.0",
                "price_out = 0.0",
                "price_periods = []",
                "send_temperature = true",
                "force_stream_mode = false",
                "visual = false",
                "extra_params = {}",
                "",
            ]
        )
    task_models = {
        "planner": ["personal_planner"],
        "replyer": ["personal_reply"],
        "memory": ["personal_reply"],
        "utils": ["personal_reply"],
        "learner": ["personal_reply"],
        "fast_model": ["personal_reply"],
        "emoji": ["personal_reply"],
        "vlm": [],
        "voice": [],
        "embedding": [],
        "image_embedding": [],
    }
    for task, models in task_models.items():
        lines.extend(
            [
                f"[model_task_config.{task}]",
                f"model_list = {json.dumps(models)}",
                "max_tokens = 8192",
                "temperature = 0.7",
                'selection_strategy = "sequential"',
                "hard_timeout = 180.0",
                "",
            ]
        )
    (config_dir / "model_config.toml").write_text("\n".join(lines), encoding="utf-8")


class _AndroidBotBridge:
    def __init__(
        self,
        instance_dir: str,
        host: Any,
        nickname: str,
        user_name: str,
        persona: str,
        user_about: str,
    ) -> None:
        from src.lite_runtime import LiteRuntimeBridge
        # 记下注入方身份：close 时只有自己仍是当前注入方才能清空全局控制器。
        self._host = host
        def on_message(payload: Dict[str, str]) -> None:
            host.onMessage(str(payload.get("id", "")), str(payload.get("text", "")))

        def on_error(message: str) -> None:
            host.onError(message)

        self._bridge = LiteRuntimeBridge(
            instance_dir,
            on_message,
            on_error,
            user_name=user_name,
            nickname=nickname,
        )

    def send(self, text: str, reply_to: str = "", message_id: str = "") -> str:
        return self._bridge.send(text, reply_to=reply_to, message_id=message_id)

    def notify_event(self, text: str) -> str:
        """把宿主事件作为一条消息送进内核，触发规划轮次。"""
        return self._bridge.notify_event(text)

    def close(self) -> None:
        from src.lite_coding_tool import configure_host_controller, get_host_controller

        try:
            self._bridge.close()
        finally:
            # 先关运行时再清控制器；且只清自己注入的那份。
            # 旧运行时的 close 可能晚于新运行时的创建（Kotlin 侧异步关闭），
            # 无条件置 None 会让新运行时的 Coding Agent 工具全部消失。
            if get_host_controller() is self._host:
                configure_host_controller(None)



def create_bridge(
    instance_dir: str,
    host: Any,
    nickname: str,
    user_name: str,
    persona: str,
    user_about: str,
    planner_json: str,
    reply_json: str,
) -> _AndroidBotBridge:
    """供 Chaquopy 调用的入口；模型配置必须在导入 src 前落盘。"""

    planner = json.loads(planner_json)
    reply = json.loads(reply_json)
    root = Path(instance_dir).expanduser().resolve()
    root.mkdir(parents=True, exist_ok=True)
    # Python 进程的 cwd 是宿主启动目录（Android 上通常是只读的 /）。
    # src.common.logger 等模块用相对路径 Path("logs") 落盘，不切到可写实例目录
    # 会直接抛 OSError: [Errno 30] Read-only file system: 'logs'。
    os.chdir(root)
    Path("logs").mkdir(parents=True, exist_ok=True)
    os.environ["MAIBOT_INSTANCE_DIR"] = str(root)
    _write_model_config(root, planner, reply)

    from src.config.config import global_config
    from src.lite_coding_tool import configure_host_controller

    global_config.bot.nickname = nickname
    global_config.personality.personality = (
        persona
        + "\n你可以使用这些宿主工具：session_list 查看本机 Coding Agent 会话并拿到 session_id；"
        "coding_agent 新建会话、向任意会话发送任务、停止生成或关闭会话；"
        "model_list 与 model_select 查看并切换指定 Coding Agent 会话的模型，不改变 Personal Agent 的模型；"
        "context_read_file 读取附件或工作区文件的内容；"
        "context_run_terminal 在 Coding Agent 的 Linux 环境里执行命令，用于复核成果。"
        "读取文件与执行终端是两个不同工具，不要互相替代。"
        "这些工具都不是替用户直接完成工作的工具，需要交付代码时应规划并控制 Coding Agent 完成。"
    )
    global_config.personality.behavior_style = persona
    if user_about.strip():
        global_config.personality.personality += f"\n你正在和{user_name}相处。关于对方：{user_about.strip()}"
    configure_host_controller(host)
    return _AndroidBotBridge(str(root), host, nickname, user_name, persona, user_about)


def create_android_bridge(
    instance_dir: str,
    host: Any,
    nickname: str,
    user_name: str,
    persona: str,
    user_about: str,
    planner_json: str,
    reply_json: str,
) -> _AndroidBotBridge:
    return create_bridge(
        instance_dir,
        host,
        nickname,
        user_name,
        persona,
        user_about,
        planner_json,
        reply_json,
    )