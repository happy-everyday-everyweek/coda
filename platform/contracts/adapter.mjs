/**
 * ProviderAdapter 契约（SPEC 8 / AGT-101）。
 *
 * 适配器只做两件事：把上游协议翻译成统一事件，把核心命令翻译成上游调用。
 * 适配器不得持久化会话权威状态，不得把原始帧直接发给 UI。
 *
 * 生命周期：
 *   const adapter = new XAdapter({ instanceId, env });
 *   const decl = await adapter.probe();      // 无副作用地发现安装/版本/登录
 *   await adapter.createSession({ workspace, model, options });
 *   for await (const evt of adapter.events()) { ... }   // 统一事件流
 *   await adapter.send({ sessionId, text, attachments });
 *   await adapter.steer({ sessionId, text });
 *   await adapter.interrupt({ sessionId, turnId });
 *   await adapter.resume({ sessionId });
 *   await adapter.fork({ sessionId });
 *   await adapter.dispose();
 */
export class ProviderAdapter {
  constructor({ instanceId, environment = "host" } = {}) {
    this.instanceId = instanceId;
    this.environment = environment;
    this._listeners = new Set();
  }

  /** @returns {Promise<object>} 能力声明，见 contracts/capabilities.mjs */
  async probe() {
    throw new Error("probe() not implemented");
  }

  /** 统一事件推送：适配器内部调用 emit()。 */
  emit(event) {
    for (const fn of this._listeners) fn(event);
  }

  /** 异步迭代器形式的事件流。 */
  async *events() {
    const queue = [];
    let wake = null;
    let done = false;
    const listener = (e) => {
      queue.push(e);
      wake?.();
    };
    this._listeners.add(listener);
    const unsubscribe = () => this._listeners.delete(listener);
    try {
      while (!done) {
        if (queue.length === 0) {
          await new Promise((resolve) => {
            wake = resolve;
            setTimeout(resolve, 200).unref?.();
          });
          wake = null;
        }
        while (queue.length) yield queue.shift();
      }
    } finally {
      unsubscribe();
    }
  }

  async createSession() { throw new Error("createSession() not implemented"); }
  async send() { throw new Error("send() not implemented"); }
  async steer() { throw new Error("steer() not implemented"); }
  async interrupt() { throw new Error("interrupt() not implemented"); }
  async resume() { throw new Error("resume() not implemented"); }
  async fork() { throw new Error("fork() not implemented"); }
  async dispose() { this._listeners.clear(); }
}

/** 适配器可用性探针结果的统一形状（无副作用）。 */
export function probeResult({ installed, version = null, loggedIn = null, path = null, details = {} }) {
  return { installed: !!installed, version, loggedIn, path, details };
}
