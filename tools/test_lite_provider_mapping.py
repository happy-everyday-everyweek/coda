"""供应商协议映射与模型字段（#796、#797）。

内核 lite 只注册了 openai / openai_responses / gemini 三种客户端；
ModelInfo 的 schema 里也没有 max_context_size / supports_tool_call。
这两件事都会让「看起来修好了」的配置在运行期失效。

与 kernel/maibot-lite/tests 的 pytest 版本互补：这一份用 unittest，
跟 CI 的 `unittest discover -s tools` 一起跑。
"""

import pathlib
import unittest

SRC = pathlib.Path(__file__).resolve().parents[1] / "kernel/maibot-lite/src/lite_android_bridge.py"


def load_funcs():
    """只取纯函数，避开模块顶部的运行时依赖。"""
    text = SRC.read_text(encoding="utf-8")
    start = text.index("def _client_type")
    end = text.index("def _write_model_config")
    ns = {"Dict": dict, "Any": object}
    exec(compile(text[start:end], str(SRC), "exec"), ns)
    return ns


class ProviderMappingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        ns = load_funcs()
        cls.client_type = staticmethod(ns["_client_type"])
        cls.auth_fields = staticmethod(ns["_auth_fields"])
        cls.capability = staticmethod(ns["_model_capability_lines"])

    def test_openai_default(self):
        self.assertEqual(self.client_type({}), "openai")
        self.assertEqual(self.client_type({"api_type": "openai-chat-completions"}), "openai")

    def test_responses_not_downgraded(self):
        # startswith("openai") 曾把 responses 协议悄悄降级成普通 openai。
        self.assertEqual(self.client_type({"api_type": "openai-responses"}), "openai_responses")
        self.assertEqual(self.client_type({"api_type": "openai_responses"}), "openai_responses")

    def test_gemini(self):
        self.assertEqual(self.client_type({"api_type": "gemini"}), "gemini")

    def test_anthropic_unsupported(self):
        # 内核没有注册 anthropic 客户端，映射过去会在运行期抛 KeyError。
        self.assertEqual(self.client_type({"api_type": "anthropic"}), "unsupported")
        self.assertEqual(self.client_type({"api_type": "anthropic-messages"}), "unsupported")

    def test_unknown_unsupported(self):
        self.assertEqual(self.client_type({"api_type": "doubao-web-anonymous"}), "unsupported")

    def test_anthropic_auth_shape_kept(self):
        # 客户端未注册，但经兼容网关接入时仍要能取到 x-api-key 形状。
        f = self.auth_fields({"api_type": "anthropic-messages", "api_key": "sk-a"})
        self.assertEqual(f["auth_header_name"], "x-api-key")
        self.assertEqual(f["auth_header_prefix"], "")

    def test_openai_bearer(self):
        f = self.auth_fields({"api_type": "openai", "api_key": "sk-x"})
        self.assertEqual(f["auth_type"], "bearer")
        self.assertEqual(f["auth_header_prefix"], "Bearer")

    def test_empty_key_means_gateway(self):
        f = self.auth_fields({"api_type": "openai", "api_key": ""})
        self.assertEqual(f["auth_type"], "none")

    def test_no_phantom_model_fields(self):
        # 这两个键不在 ModelInfo schema 里，写了会被 from_dict 剥掉。
        lines = self.capability({"context_window": 128000, "supports_tool_call": False})
        joined = "\n".join(lines)
        self.assertNotIn("max_context_size", joined)
        self.assertNotIn("supports_tool_call", joined)

    def test_output_limit_maps_to_max_tokens(self):
        lines = self.capability({"max_output_tokens": 4096})
        self.assertEqual(lines, ["max_tokens = 4096"])

    def test_zero_output_limit_omitted(self):
        self.assertEqual(self.capability({"max_output_tokens": 0}), [])
        self.assertEqual(self.capability({}), [])


if __name__ == "__main__":
    unittest.main()