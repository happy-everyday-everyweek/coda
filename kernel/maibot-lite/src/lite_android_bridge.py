"""Android 宿主桥：先写模型配置，再加载 MaiBot 业务模块。"""

from pathlib import Path
from typing import Any, Dict

import json
import os


def _toml_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)


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
        lines.extend(
            [
                "[[api_providers]]",
                f"name = {_toml_string(provider_name)}",
                f"base_url = {_toml_string(item['base_url'])}",
                f"api_key = {_toml_string(item['api_key'])}",
                'client_type = "openai"',
                'auth_type = "bearer"',
                'auth_header_name = "Authorization"',
                'auth_header_prefix = "Bearer"',
                'auth_query_name = "api_key"',
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

    def send(self, text: str) -> str:
        return self._bridge.send(text)

    def notify_event(self, text: str) -> str:
        """把宿主事件作为一条消息送进内核，触发规划轮次。"""
        return self._bridge.notify_event(text)

    def close(self) -> None:
        from src.lite_coding_tool import configure_host_controller

        configure_host_controller(None)
        self._bridge.close()


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
    os.environ["MAIBOT_INSTANCE_DIR"] = str(root)
    _write_model_config(root, planner, reply)

    from src.config.config import global_config
    from src.lite_coding_tool import configure_host_controller

    global_config.bot.nickname = nickname
    global_config.personality.personality = (
        persona
        + "\n你可以使用这些宿主工具：session_list 查看本机 Coding Agent 会话并拿到 session_id；"
        "coding_agent 新建会话、向任意会话发送任务、停止生成或关闭会话；"
        "model_list 与 model_select 查看并切换你自己的规划模型与回复模型；"
        "context_read_file 读取附件或工作区文件的内容；"
        "context_run_terminal 在 Coding Agent 的 Linux 环境里执行命令，用于复核成果。"
        "读取文件与执行终端是两个不同工具，不要互相替代。"
        "但它们都不是替用户直接完成工作的工具，需要交付代码时应规划并控制 Coding Agent 完成。"
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