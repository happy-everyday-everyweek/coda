"""规划模型的 Coding Agent 控制边界测试。"""

from types import SimpleNamespace

import json

import pytest

from src.lite_coding_tool import configure_host_controller, get_provider


class _Host:
    def __init__(self) -> None:
        self.calls = []

    def controlSession(self, action: str, text: str, session_id: str) -> str:
        self.calls.append((action, text, session_id))
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
async def test_coding_agent_tool_passes_session_id_to_host() -> None:
    host = _Host()
    configure_host_controller(host)
    provider = get_provider()
    invocation = SimpleNamespace(
        tool_name="coding_agent",
        arguments={"action": "send", "text": "修复构建", "session_id": "session-1"},
    )

    result = await provider.invoke(invocation)

    assert result.success
    assert host.calls == [("send", "修复构建", "session-1")]
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


@pytest.mark.asyncio
async def test_context_run_terminal_rejects_writes() -> None:
    """只读白名单必须真正拦住写操作与命令串联，而不只是提示词约束。"""

    host = _Host()
    configure_host_controller(host)
    provider = get_provider()
    for command in (
        "git clean -fdx",
        "sed -i s/a/b/ f",
        "find . -delete",
        "sort -o out in",
        "ls\nrm -rf /",
        "rm -rf build",
    ):
        result = await provider.invoke(
            SimpleNamespace(tool_name="context_run_terminal", arguments={"command": command})
        )
        assert not result.success, command
        assert "只读检查" in (result.error_message or ""), command
    configure_host_controller(None)
