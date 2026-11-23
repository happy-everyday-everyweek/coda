"""供应商协议映射（#34）。

宿主现在会把 api_type / context_window / supports_tool_call 一并传下来。
桥这边原来无论什么供应商都写死 client_type="openai" + auth_type="bearer"，
于是 anthropic-messages、doubao-web-anonymous 这类协议必然失败。
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from lite_android_bridge import _auth_fields, _client_type  # noqa: E402


def test_openai_default():
    assert _client_type({"api_type": "openai-chat-completions"}) == "openai"


def test_missing_api_type_falls_back_to_openai():
    # 旧版宿主不传这个字段，必须仍能工作。
    assert _client_type({}) == "openai"


def test_anthropic_messages():
    assert _client_type({"api_type": "anthropic-messages"}) == "anthropic"


def test_anthropic_alias():
    assert _client_type({"api_type": "anthropic"}) == "anthropic"


def test_gemini():
    assert _client_type({"api_type": "gemini"}) == "gemini"


def test_unknown_type_is_not_silently_openai():
    # 未知协议宁可显式标成 unsupported，让上层给出可读失败，
    # 也不要伪装成 openai 然后拿到一个难以理解的 400。
    assert _client_type({"api_type": "doubao-web-anonymous"}) == "unsupported"


def test_bearer_auth_for_openai():
    fields = _auth_fields({"api_type": "openai-chat-completions", "api_key": "sk-x"})
    assert fields["auth_type"] == "bearer"
    assert fields["auth_header_name"] == "Authorization"
    assert fields["auth_header_prefix"] == "Bearer"


def test_anthropic_auth_shape_kept_for_gateway():
    # 客户端未注册，但鉴权形状仍保留：经兼容网关接入时宿主要能取到正确的头。
    fields = _auth_fields({"api_type": "anthropic-messages", "api_key": "sk-a"})
    assert fields["auth_header_name"] == "x-api-key"
    # Anthropic 的值不带 Bearer 前缀。
    assert fields["auth_header_prefix"] == ""


def test_empty_key_means_gateway_auth():
    # OAuth 供应商的凭据在本地网关，api_key 为空。
    # 这时不能再带 Authorization: Bearer（空令牌会被上游拒绝）。
    fields = _auth_fields({"api_type": "openai-chat-completions", "api_key": ""})
    assert fields["auth_type"] == "none"


def test_missing_key_means_gateway_auth():
    fields = _auth_fields({"api_type": "openai-chat-completions"})
    assert fields["auth_type"] == "none"