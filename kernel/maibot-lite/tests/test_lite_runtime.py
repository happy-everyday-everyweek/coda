"""Android Lite 入口的公开消息边界测试。"""

from pathlib import Path
from types import SimpleNamespace
from typing import Any, Dict, List
from unittest.mock import AsyncMock

import pytest

from src.lite_runtime import AndroidLiteDriver, LiteRuntime
from src.platform_io import DeliveryStatus, DriverKind, RouteKey


@pytest.mark.asyncio
async def test_local_driver_delivers_text_to_android_listener() -> None:
    received: List[Dict[str, Any]] = []
    driver = AndroidLiteDriver(received.append)
    message = SimpleNamespace(
        message_id="reply-1",
        processed_plain_text="我在。",
        raw_message=SimpleNamespace(components=[]),
    )
    route = RouteKey(platform="android", account_id="assistant")

    receipt = await driver.send_message(message, route)

    assert received == [{"id": "reply-1", "text": "我在。"}]
    assert receipt.status == DeliveryStatus.SENT
    assert receipt.driver_kind == DriverKind.LOCAL
    assert receipt.external_message_id == "reply-1"


@pytest.mark.asyncio
async def test_runtime_routes_user_text_through_chat_bot(tmp_path: Path) -> None:
    runtime = LiteRuntime(tmp_path, on_message=lambda _: None)
    fake_bot = SimpleNamespace(message_process=AsyncMock())
    fake_manager = SimpleNamespace(
        start=AsyncMock(),
        ensure_builtin_hook_specs_registered=lambda: None,
        _hook_dispatcher=SimpleNamespace(stop=AsyncMock()),
    )
    fake_prompts = SimpleNamespace(load_prompts=lambda: None)
    runtime._chat_bot = fake_bot
    runtime._plugin_manager = fake_manager
    runtime._prompt_manager = fake_prompts
    runtime._chat_manager = SimpleNamespace(initialize=AsyncMock())

    await runtime.start()
    await runtime.send("你好")

    fake_manager.start.assert_not_awaited()
    payload = fake_bot.message_process.await_args.args[0]
    assert payload["message_info"]["platform"] == "android"
    assert payload["message_info"]["user_info"]["user_id"] == "local_user"
    assert payload["message_info"]["group_info"] is None
    assert payload["message_segment"] == {"type": "text", "data": "你好"}
    assert payload["message_info"]["additional_config"]["account_id"] == "assistant"

    await runtime.stop()
