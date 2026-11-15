/**
 * Mock 适配器（CORE-106 / AGT-101 测试替身）。
 *
 * 只用于测试与联调：它能流式、能被停止、能发审批，但**不是**任何真实 Agent。
 * 能力声明全部落在 experimental/true 的测试语义上，矩阵里绝不能标成真实产品的 supported。
 */
import { AdapterError, BaseAdapter, sideEffectFreeProbe } from "./base.mjs";
import { InteractionRegistry, interactionEventPayload } from "./interactions.mjs";
import { makeId } from "../contracts/ids.mjs";

const sleep = (ms) => (ms > 0 ? new Promise((resolve) => setTimeout(resolve, ms)) : Promise.resolve());

export function mockCapabilityDeclaration({ nativeSteer = false } = {}) {
  return {
    session: {
      multiThread: true,
      modelSwitch: true,
      permissionModeSwitch: true,
      resume: "experimental",
      requestDurability: "experimental",
    },
    thread: { empty: true, snapshot: false, fork: false, fromTurn: false, nativeId: "experimental" },
    turn: {
      startEndEvents: true,
      interrupt: true,
      nativeSteer: nativeSteer ? "experimental" : false,
      stopThenAppend: "experimental",
      queue: "experimental",
      terminalTrust: "experimental",
    },
    streaming: { text: true, reasoning: true, toolOutput: true, planText: false, messageCompleted: true },
    tools: true,
    approvals: { command: true, fileRead: true, fileWrite: true, patch: false, subagent: false, nativeRequestId: true },
    plan: false,
    subagent: false,
    context: { system: "experimental", summary: false, handoffFull: false, handoffIncremental: false },
    checkpoint: { filesystem: false, sessionRollback: false, consistent: false },
    identity: { thread: true, turn: true, item: true, request: true },
    attachments: { input: true, output: true },
  };
}

export class MockAdapter extends BaseAdapter {
  constructor({
    instanceId = "mock-1",
    environment = "host",
    script = {},
    supportsSteer = false,
    stepDelayMs = 0,
    resumeFails = false,
    approval = null,
    clock = () => new Date(),
  } = {}) {
    super({ instanceId, environment, provider: "mock", version: "mock/1.0.0", clock });
    this.script = script;
    this.supportsSteer = supportsSteer;
    this.stepDelayMs = stepDelayMs;
    this.resumeFails = resumeFails;
    this.approval = approval;
    this._active = new Map();
    this.interactions = new InteractionRegistry({
      onChange: (interaction) => {
        if (this._disposed) return;
        this.emitEvent({ kind: "interaction", sessionId: interaction.sessionId, payload: interactionEventPayload(interaction) });
      },
    });
  }

  async probe() {
    this.declareCapabilities(mockCapabilityDeclaration({ nativeSteer: this.supportsSteer }));
    return sideEffectFreeProbe({ installed: true, version: "mock/1.0.0", loggedIn: null, details: { testDouble: true } });
  }

  async createSession({ sessionId, providerSessionId = null, workspace = null, model = null, options = {} } = {}) {
    this._assertUsable();
    const record = this.registerSession({
      sessionId: sessionId ?? makeId("ses"),
      providerSessionId,
      workspace,
      model,
      options,
    });
    this.emitEvent({ kind: "session", payload: { state: "created", sessionId: record.sessionId, provider: "mock" } });
    return record;
  }

  async send({ sessionId, text = null, attachments = [], turnId = null } = {}) {
    this._assertUsable();
    const record = this.getSession(sessionId);
    if (this._active.has(sessionId)) throw new AdapterError("TURN_IN_PROGRESS", "mock turn already running");
    const id = turnId ?? makeId("turn");
    const state = { turnId: id, cancelled: false };
    this._active.set(sessionId, state);
    record.state = "running";
    this.emitEvent({ kind: "turn", sessionId, payload: { turnId: id, state: "running" } });
    this.emitEvent({ kind: "message", sessionId, payload: { role: "user", text, attachments: attachments.length } });

    const chunks = this.script.chunks ?? ["hello", " world"];
    try {
      for (const chunk of chunks) {
        if (state.cancelled) {
          this.emitEvent({ kind: "turn", sessionId, payload: { turnId: id, state: "stopped", stopReason: "cancelled" } });
          return { turnId: id, state: "stopped", stopReason: "cancelled" };
        }
        this.emitEvent({ kind: "message", sessionId, payload: { role: "assistant", text: chunk, delta: true } });
        if (this.stepDelayMs > 0) await sleep(this.stepDelayMs);
      }
      if (this.script.failWith) {
        throw new AdapterError(
          this.script.failWith.code ?? "UNKNOWN",
          this.script.failWith.message ?? "mock failure",
        );
      }
      if (this.approval) {
        const interaction = this.interactions.createPermission({
          sessionId,
          nativeRequestId: `mock-perm-${id}`,
          title: this.approval.title ?? "Mock approval",
          options: this.approval.options ?? [
            { optionId: "allow", label: "Allow", kind: "allow_once" },
            { optionId: "deny", label: "Deny", kind: "reject_once" },
          ],
        });
        await this.interactions.waitForAnswer(interaction.interactionId, this.approval.timeoutMs ?? 3000);
        if (interaction.answer?.optionId !== "allow") {
          this.emitEvent({ kind: "turn", sessionId, payload: { turnId: id, state: "stopped", stopReason: "refusal" } });
          return { turnId: id, state: "stopped", stopReason: "refusal" };
        }
      }
      this.emitEvent({ kind: "turn", sessionId, payload: { turnId: id, state: "completed", stopReason: "end_turn" } });
      return { turnId: id, state: "completed", stopReason: "end_turn" };
    } finally {
      this._active.delete(sessionId);
      record.state = "idle";
    }
  }

  async steer({ sessionId, text = null } = {}) {
    this._assertUsable();
    if (!this.supportsSteer) throw new AdapterError("UNSUPPORTED_CAPABILITY", "mock does not support native steer");
    this.emitEvent({ kind: "message", sessionId, payload: { role: "user", text, steered: true } });
    return { steered: true, native: true };
  }

  async interrupt({ sessionId } = {}) {
    this._assertUsable();
    const state = this._active.get(sessionId);
    if (!state) return { sessionId, state: "idle", upstreamConfirmed: false, alreadyIdle: true };
    state.cancelled = true;
    const closed = this.interactions.cancelForSession(sessionId, "turn-stopped");
    return { sessionId, turnId: state.turnId, state: "stopping", upstreamConfirmed: "pending", closedInteractions: closed.length };
  }

  async resume({ sessionId } = {}) {
    this._assertUsable();
    const record = this.getSession(sessionId);
    if (this.resumeFails) {
      throw new AdapterError("PROTOCOL_ERROR", "mock resume failed: upstream session lost");
    }
    record.state = "idle";
    return { sessionId, providerSessionId: record.providerSessionId, resumed: true };
  }

  async fork() {
    throw new AdapterError("UNSUPPORTED_CAPABILITY", "mock has no native fork");
  }
}

export function createMockAdapter(options = {}) {
  return new MockAdapter(options);
}
