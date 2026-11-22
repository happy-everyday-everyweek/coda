"""单用户 Android 嵌入入口，不创建网络服务或插件子进程。"""

from pathlib import Path
from typing import TYPE_CHECKING, Any, Callable, Dict, List, Optional
from uuid import uuid4

import asyncio
import os
import threading

from src.common.paths import INSTANCE_DIRECTORY_ENV
from src.platform_io import DeliveryReceipt, DeliveryStatus, DriverDescriptor, DriverKind, RouteBinding, RouteKey
from src.platform_io.drivers.base import PlatformIODriver

if TYPE_CHECKING:
    from src.chat.message_receive.message import SessionMessage

MessageListener = Callable[[Dict[str, str]], None]
ErrorListener = Callable[[str], None]
PLATFORM = "android"
ACCOUNT_ID = "assistant"
USER_ID = "local_user"


class AndroidLiteDriver(PlatformIODriver):
    """把主链已经决定发送的消息交给宿主，不模拟模型回复。"""

    def __init__(self, listener: MessageListener) -> None:
        super().__init__(
            DriverDescriptor(
                driver_id="android.local",
                kind=DriverKind.LOCAL,
                platform=PLATFORM,
                account_id=ACCOUNT_ID,
            )
        )
        self._listener = listener

    async def send_message(
        self,
        message: "SessionMessage",
        route_key: RouteKey,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> DeliveryReceipt:
        """将一段真实回复投递给宿主监听器。"""
        text = message.processed_plain_text.strip()
        if text:
            self._listener({"id": message.message_id, "text": text})
        return DeliveryReceipt(
            internal_message_id=message.message_id,
            route_key=route_key,
            status=DeliveryStatus.SENT,
            driver_id=self.driver_id,
            driver_kind=DriverKind.LOCAL,
            external_message_id=message.message_id,
        )


# start 的串行锁（#118）。模块级：send 与 notify_event 可能落在不同协程，
# 但注册 driver / 启动 Broker 这些动作对整个进程只能做一次。
_START_LOCK = asyncio.Lock()

# 当前活动的运行时实例（#119）。旧实例的异步 stop 据此判断自己是否还拥有
# heartflow / platform io 这些模块级单例，不拥有就只清理自己的资源。
_ACTIVE_RUNTIME: "LiteRuntime | None" = None


class LiteRuntime:
    """MaiBot 的单会话异步入口；所有公开调用必须在同一事件循环执行。"""

    def __init__(
        self,
        instance_dir: Path,
        on_message: MessageListener,
        on_error: Optional[ErrorListener] = None,
        user_name: str = "你",
        nickname: str = "麦麦",
    ) -> None:
        self._instance_dir = Path(instance_dir).expanduser().resolve()
        self._on_message = on_message
        self._on_error = on_error
        self._user_name = user_name
        self._nickname = nickname
        self._chat_bot: Any = None
        self._plugin_manager: Any = None
        self._prompt_manager: Any = None
        self._driver: Optional[AndroidLiteDriver] = None
        self._started = False

    @property
    def is_started(self) -> bool:
        return self._started

    def _prepare_configuration(self) -> None:
        """在首次业务导入前固定实例目录，隔离 Android 私有数据。"""
        if self._chat_bot is not None:
            return
        self._instance_dir.mkdir(parents=True, exist_ok=True)
        os.environ[INSTANCE_DIRECTORY_ENV] = str(self._instance_dir)

        from src.chat.message_receive.bot import chat_bot
        from src.chat.message_receive.chat_manager import chat_manager
        from src.common.runtime_loop import set_main_loop
        from src.config.config import config_manager, global_config
        from src.plugin_runtime.integration import get_plugin_runtime_manager
        from src.prompt.prompt_manager import prompt_manager

        global_config.bot.platforms = [f"{PLATFORM}:{ACCOUNT_ID}"]
        global_config.bot.nickname = self._nickname
        global_config.maim_message.enabled = False
        global_config.webui.enabled = False
        global_config.plugin_runtime.enabled = False
        global_config.a_memorix.plugin.enabled = False
        global_config.agent.enabled = False
        global_config.mcp.enable = False
        global_config.mcp.servers = []
        self._chat_bot = chat_bot
        self._plugin_manager = get_plugin_runtime_manager()
        self._prompt_manager = prompt_manager
        self._chat_manager = chat_manager
        self._config_manager = config_manager
        set_main_loop(asyncio.get_running_loop())

    async def start(self) -> None:
        """初始化聊天、持久化和进程内路由，不启动原项目 MainSystem。"""
        if self._started:
            return
        # start 必须串行（#118）：_started 要到函数末尾才置 True，
        # send 与 notify_event 并发进来会两次执行 register_driver，
        # Broker 已启动时那里直接 raise，本次消息/事件就失败了。
        async with _START_LOCK:
            if self._started:
                return
            await self._start_locked()
        # 谁最后 start，谁就是当前活动实例；旧实例的 stop 据此跳过全局关停（#119）。
        global _ACTIVE_RUNTIME
        _ACTIVE_RUNTIME = self

    async def _start_locked(self) -> None:
        self._prepare_configuration()
        # 重新进入 Bot 页会重建运行时，但心流管理器是模块级单例：
        # 上次关闭把它置为关停后不会自己恢复，这里显式复位，否则消息会被静默丢弃。
        from src.chat.heart_flow.heartflow_manager import heartflow_manager

        await heartflow_manager.reset()
        # create_bridge 只把模型配置写到磁盘，这里的 ConfigManager 是 import 时读进内存的，
        # 不重载就会出现「设置里换了模型、实际仍走旧模型」。
        from src.config.config import config_manager

        try:
            await config_manager.reload_config(changed_scopes=["model"])
        except Exception as exc:  # noqa: BLE001 - 配置坏了也不能拦住启动，先用上次读到的值
            from src.common.logger import get_logger

            get_logger("lite").warning(f"模型配置重载失败，继续使用上次的配置：{exc}")
        await asyncio.to_thread(self._prompt_manager.load_prompts)
        # 原项目按 disabled 直接 return，未注册 Hook；Lite 仍需这些无插件 Hook 通过。
        self._plugin_manager.ensure_builtin_hook_specs_registered()
        await self._chat_manager.initialize()

        from src.platform_io import get_platform_io_manager

        manager = get_platform_io_manager()
        self._driver = AndroidLiteDriver(self._on_message)
        manager.register_driver(self._driver)
        route = RouteKey(platform=PLATFORM, account_id=ACCOUNT_ID)
        binding = RouteBinding(
            route_key=route,
            driver_id=self._driver.driver_id,
            driver_kind=DriverKind.LOCAL,
        )
        manager.bind_send_route(binding)
        manager.bind_receive_route(binding)
        await manager.start()
        self._started = True

    async def send(self, text: str, reply_to: str | None = None, message_id: str | None = None) -> None:
        """发送用户文本；reply_to 非空时按 MaiBot 标准 reply 段引用原消息。

        message_id 允许由宿主给出，使本地消息记录与内核消息库使用同一个 id，
        这样后续 reply 段回查原消息时也能命中。
        """
        content = text.strip()
        if not content:
            raise ValueError("消息不能为空")
        await self.start()
        segments = []
        if reply_to:
            segments.append({"type": "reply", "data": reply_to})
        segments.append({"type": "text", "data": content})
        payload = {
            "message_info": {
                "platform": PLATFORM,
                "user_info": {
                    "user_id": USER_ID,
                    "user_nickname": self._user_name,
                },
                "group_info": None,
                "additional_config": {
                    "account_id": ACCOUNT_ID,
                    "platform_io_target_user_id": USER_ID,
                },
            },
            "message_segment": (
                {"type": "seglist", "data": segments} if reply_to else {"type": "text", "data": content}
            ),
            "raw_message": content,
            "message_id": message_id or f"android_{uuid4().hex}",
        }
        await self._chat_bot.message_process(payload)

    async def notify_event(self, text: str) -> None:
        """宿主事件作为内部任务注入：走 proactive 轮次，不经过消息门控，也不冒充用户消息。"""
        content = text.strip()
        if not content:
            raise ValueError("事件内容不能为空")
        await self.start()

        from src.chat.heart_flow.heartflow_manager import heartflow_manager
        from src.common.utils.utils_session import SessionUtils

        session_id = SessionUtils.calculate_session_id(
            PLATFORM,
            user_id=USER_ID,
            account_id=ACCOUNT_ID,
        )
        chat = await heartflow_manager.get_or_create_heartflow_chat(session_id)
        chat.enqueue_internal_task(
            source_kind="coda_host_event",
            text=content,
            reason="Coding Agent 事件",
        )

    async def stop(self) -> None:
        """停止主链后台任务并释放本地驱动。

        每一步各自兜住异常：任一步失败也要把后面的清理跑完，
        否则 driver 留在 platform io 里，下次 start 会重复注册同一 id。
        """

        if not self._started:
            return
        global _ACTIVE_RUNTIME
        self._started = False
        from src.chat.heart_flow.heartflow_manager import heartflow_manager
        from src.platform_io import get_platform_io_manager

        # 旧实例的异步 stop 可能晚于新实例的 start（#119）。
        # heartflow_manager 与 platform io 都是模块级单例，无条件 shutdown
        # 会把新实例刚建立的会话链路一起关掉，表现为 Bot 又不回话且没有日志。
        owns_globals = _ACTIVE_RUNTIME is self or _ACTIVE_RUNTIME is None
        if owns_globals:
            _ACTIVE_RUNTIME = None

        steps = (
            ("heartflow shutdown", heartflow_manager.shutdown),
            ("remove android driver", lambda: get_platform_io_manager().remove_driver("android.local")),
            ("stop platform io", get_platform_io_manager().stop),
            ("stop hook dispatcher", self._plugin_manager._hook_dispatcher.stop),
        )
        failures: List[str] = []
        for label, step in steps:
            # 不是当前活动实例时，只停自己的 hook dispatcher，不动全局单例。
            if not owns_globals and label != "stop hook dispatcher":
                continue
            try:
                await step()
            except Exception as exc:  # noqa: BLE001 - 清理阶段要把异常汇总而不是中断后续清理
                failures.append(f"{label}: {exc}")
        self._driver = None
        # 关停结束就要把入口重新打开：管理器是单例，退出 Bot 页只是停一次运行时，
        # 下一次进入必须还能创建会话。这里放在最后，避免和 reset() 抢时序。
        self._stopping = False
        if failures:
            raise RuntimeError("停止 MaiBot Lite 时出错：" + "；".join(failures))



class LiteRuntimeBridge:
    """供 Kotlin 调用的同步外观；Python 异步主链在专用守护线程运行。"""

    def __init__(
        self,
        instance_dir: str,
        on_message: MessageListener,
        on_error: Optional[ErrorListener] = None,
        user_name: str = "你",
        nickname: str = "麦麦",
    ) -> None:
        self._runtime = LiteRuntime(
            Path(instance_dir),
            on_message=on_message,
            on_error=on_error,
            user_name=user_name,
            nickname=nickname,
        )
        self._ready = threading.Event()
        self._failure: Optional[BaseException] = None
        self._loop = asyncio.new_event_loop()
        self._thread = threading.Thread(target=self._run, name="MaiBotLite", daemon=True)
        self._thread.start()
        self._ready.wait()

    def _run(self) -> None:
        asyncio.set_event_loop(self._loop)
        self._ready.set()
        try:
            self._loop.run_forever()
        except BaseException as exc:
            self._failure = exc
        finally:
            self._loop.close()

    def send(self, text: str, reply_to: str = "", message_id: str = "") -> str:
        """提交一条消息；reply_to 非空时按标准 reply 段引用原消息。"""
        if self._failure is not None:
            raise RuntimeError("MaiBot Lite 事件循环已停止") from self._failure
        future = asyncio.run_coroutine_threadsafe(
            self._runtime.send(
                text,
                reply_to=reply_to or None,
                message_id=message_id or None,
            ),
            self._loop,
        )
        future.add_done_callback(self._report_failure)
        return "accepted"

    def notify_event(self, text: str) -> str:
        """宿主事件走 proactive 轮次，不经过消息门控。"""
        if self._failure is not None:
            raise RuntimeError("MaiBot Lite 事件循环已停止") from self._failure
        future = asyncio.run_coroutine_threadsafe(self._runtime.notify_event(text), self._loop)
        future.add_done_callback(self._report_failure)
        return "accepted"

    def _report_failure(self, future: "asyncio.Future[None]") -> None:
        if future.cancelled() or self._on_error is None:
            return
        error = future.exception()
        if error is not None:
            self._on_error(str(error))

    def close(self) -> None:
        if self._loop.is_closed():
            return
        future = asyncio.run_coroutine_threadsafe(self._runtime.stop(), self._loop)
        future.result(timeout=30)
        self._loop.call_soon_threadsafe(self._loop.stop)
        self._thread.join(timeout=30)
