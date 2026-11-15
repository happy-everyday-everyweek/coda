/**
 * CORE-103：核心内部事件总线。
 *
 * 只负责「已持久化事件」到订阅者的扇出：
 * - 订阅者异常不得影响其它订阅者和核心主流程（错误交给 onError 记录）。
 * - 推送顺序严格等于持久化顺序（journal.seq 单调 +1）。
 * - 关闭后不再推送；subscribe 返回的取消函数必须幂等。
 */
import { ClosedError } from "./errors.mjs";

export function createEventBus({ onError = null } = {}) {
  /** @type {Set<{fn: Function}>} */
  const subscribers = new Set();
  let closed = false;
  let published = 0;

  function subscribe(fn) {
    if (typeof fn !== "function") {
      throw new TypeError("subscribe(fn) requires a function");
    }
    if (closed) throw new ClosedError("event bus is closed");
    const entry = { fn };
    subscribers.add(entry);
    return () => {
      subscribers.delete(entry);
    };
  }

  function publish(event) {
    if (closed) return 0;
    published += 1;
    let delivered = 0;
    // 快照式遍历：订阅者在回调里退订不会跳过其它订阅者。
    for (const entry of [...subscribers]) {
      if (!subscribers.has(entry)) continue;
      try {
        entry.fn(event);
        delivered += 1;
      } catch (err) {
        if (typeof onError === "function") {
          try {
            onError(err, event);
          } catch {
            // onError 自身失败也不能影响扇出。
          }
        }
      }
    }
    return delivered;
  }

  function close() {
    if (closed) return;
    closed = true;
    subscribers.clear();
  }

  return {
    subscribe,
    publish,
    close,
    get closed() {
      return closed;
    },
    get subscriberCount() {
      return subscribers.size;
    },
    get published() {
      return published;
    },
  };
}
