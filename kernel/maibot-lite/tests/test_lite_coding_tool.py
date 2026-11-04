"""规划模型的 Coding Agent 控制边界测试。"""

from types import SimpleNamespace

import json

import pytest

from src.lite_coding_tool import configure_host_controller, get_provider


class _Host:
    def __init__(self) -> None:
        self.calls = []

    def controlSession(self, action: str, text: str) -> str:
        self.calls.append((action, text))
        return json.dumps({"ok": True, "message": "完成"})

    def readFile(self, path: str) -> str:
        return json.dumps({"ok": True, "content": f"file:{path}"})

    def runTerminal(self, command: str, timeout_seconds: int) -> str:
        return json.dumps({"ok": True, "output": f"run:{command}:{timeout_seconds}"})


@pytest.mark.asyncio
async def test_coding_agent_tool_is_hidden_without_host() -> None:
    configure_host_controller(None)
    assert await get_provider().list_tools() == []


@pytest.mark.asyncio
async def test_coding_agent_tool_passes_only_session_actions() -> None:
    host = _Host()
    configure_host_controller(host)
    provider = get_provider()
    invocation = SimpleNamespace(tool_name="coding_agent", arguments={"action": "send", "text": "修复构建"})

    result = await provider.invoke(invocation)

    assert result.success
    assert host.calls == [("send", "修复构建")]
    configure_host_controller(None)


@pytest.mark.asyncio
async def test_context_tools_are_limited_to_context_and_verification() -> None:
    configure_host_controller(_Host())
    provider = get_provider()
    specs = {spec.name: spec for spec in await provider.list_tools()}
    assert "不是用来替用户完成任务" in specs["context_read_file"].description
    assert "不得用它直接替用户完成开发任务" in specs["context_run_terminal"].description

    read_result = await provider.invoke(
        SimpleNamespace(tool_name="context_read_file", arguments={"path": "/tmp/a.py"})
    )
    command_result = await provider.invoke(
        SimpleNamespace(
            tool_name="context_run_terminal",
            arguments={"command": "git diff", "timeout_seconds": 10},
        )
    )
    assert read_result.content == "file:/tmp/a.py"
    assert command_result.content == "run:git diff:10"
    configure_host_controller(None)