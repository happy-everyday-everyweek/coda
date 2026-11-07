/**
 * 桌面 PA 本地运行器与独立存储（PA-103，验收 PA-001 / PA-008）。
 *
 * 独立性：
 * - 只有本文件自己的 SQLite 库（默认 <dataDir>/pa.sqlite），不读 Coding Agent 的库/配置；
 * - 不 import adapters/、core/ 或 clients/；协调能力只通过 tools.mjs 的统一命令层；
 * - core 可以为 null：没有 Coding Agent / 共享核心时，PA 仍可对话和保存单会话；
 * - LLM 完全注入（测试用脚本化 fake，不联网）；不内置任何外部依赖。
 *
 * 单会话：一个 runner 只对应一个 PAProfile；消息用 paId 归属，不建第二条默认会话。
 */
import { isIdOf, nowIso } from "../contracts/ids.mjs";
import { createAuditLog } from "./audit.mjs";
import { mergePaMessages } from "./merge.mjs";
import {
  PA_MESSAGE_ROLES,
  assertSinglePaSession,
  compareHlc,
  createHybridClock,
  createPaMessage,
  createPaProfile,
  tsForHlc,
} from "./schema.mjs";
import { createPaStore } from "./store.mjs";
import { createPaTools } from "./tools.mjs";

function resolvePhysicalNow(clock) {
  if (typeof clock === "function") return clock;
  if (clock && typeof clock.now === "function") return () => clock.now();
  return () => Date.now();
}

function callLlm(llm, request) {
  if (!llm) throw new Error("PA LLM is not configured");
  if (typeof llm === "function") return llm(request);
  const fn = [llm.complete, llm.reply, llm.generate].find((candidate) => typeof candidate === "function");
  if (!fn) throw new Error("PA LLM must be a function or expose complete()/reply()/generate()");
  return fn.call(llm, request);
}

function toLlmMessage(message) {
  return { role: message.role, content: message.content };
}

export function createPaRunner({
  core = null,
  store: providedStore = null,
  dataDir = null,
  llm = null,
  clock = null,
  hlcClock = null,
  // 契约最小签名是 createPaRunner({core, store})；未传 nodeId 时退回核心本机节点 ID。
  // 真机/多设备部署必须显式传本设备 nodeId，否则不同设备的 sourceNode 会冲突。
  nodeId = "dev_local",
  profile: profileOverrides = {},
  maxToolSteps = 4,
  historyLimit = 40,
} = {}) {
  if (!isIdOf(nodeId, "dev")) throw new Error("createPaRunner requires nodeId (dev_ ID)");
  if (!providedStore && !dataDir) throw new Error("createPaRunner requires store or dataDir");
  if (!Number.isInteger(maxToolSteps) || maxToolSteps < 1) throw new Error("maxToolSteps must be a positive integer");
  if (!Number.isInteger(historyLimit) || historyLimit < 1) throw new Error("historyLimit must be a positive integer");

  const ownsStore = !providedStore;
  const store = providedStore ?? createPaStore({ dataDir });
  const physicalNow = resolvePhysicalNow(clock);
  const hybridClock = hlcClock ?? createHybridClock({ nodeId, now: physicalNow });
  const audit = createAuditLog(store);

  let profile = store.getProfile();
  if (profile) {
    if (profileOverrides.id && profileOverrides.id !== profile.id) {
      throw new Error("PA profile id mismatch: refusing to start a second default PA session");
    }
  } else {
    profile = createPaProfile(profileOverrides);
    store.saveProfile(profile);
  }

  let closed = false;
  const tools = createPaTools({ core, audit, nodeId, profileId: profile.id });

  function ensureOpen() {
    if (closed) throw new Error("PA runner is closed");
  }

  function appendMessage({ id, role, content, attachments = [], replyTo = null, origin = "local" } = {}) {
    ensureOpen();
    if (!PA_MESSAGE_ROLES.includes(role)) throw new Error(`invalid PA message role: ${role}`);
    if (id !== undefined) {
      if (!isIdOf(id, "pm")) throw new Error("message id must be a pm_ ID");
      const existing = store.getMessage(id);
      if (existing) return { ...existing, duplicate: true };
    }
    return store.transaction(() => {
      const hlc = hybridClock.now();
      const seq = store.nextSeq(nodeId);
      const message = createPaMessage({
        id,
        paId: profile.id,
        sourceNode: nodeId,
        role,
        content,
        attachments,
        replyTo,
        seq,
        hlc,
        ts: tsForHlc(hlc),
        origin,
      });
      store.insertMessage(message);
      return message;
    });
  }

  function history({ limit = null, afterHlc = null, withView = true } = {}) {
    ensureOpen();
    const merged = mergePaMessages({ local: store.listMessages() });
    assertSinglePaSession(profile.id, merged.messages);
    let view = merged.view;
    if (afterHlc) {
      view = view.filter((message) => compareHlc(message.hlc, afterHlc) > 0);
    }
    if (Number.isInteger(limit) && limit > 0) view = view.slice(-limit);
    return withView ? view : view.map(({ replyToResolved, replyToMissing, ...message }) => message);
  }

  function buildLlmContext() {
    const recent = history({ limit: historyLimit });
    const system = [];
    if (profile.systemPrompt && profile.systemPrompt.trim().length > 0) {
      system.push({ role: "system", content: profile.systemPrompt });
    }
    const messages = recent.filter((message) => message.role !== "system").map(toLlmMessage);
    return [...system, ...messages];
  }

  async function respond({ replyTo }) {
    const toolResults = [];
    const extra = [];
    for (let step = 0; step < maxToolSteps; step += 1) {
      const response = await callLlm(llm, {
        profile: { ...profile },
        messages: [...buildLlmContext(), ...extra],
        tools: tools.specs(),
        step,
      });
      if (!response || typeof response !== "object") {
        throw new Error("PA LLM returned an invalid response");
      }
      const toolCalls = Array.isArray(response.toolCalls) ? response.toolCalls : [];
      if (toolCalls.length > 0) {
        const stepResults = [];
        for (const call of toolCalls) {
          const name = call?.name ?? call?.tool;
          const args = call?.args ?? call?.arguments ?? {};
          stepResults.push(await tools.invoke(name, args));
        }
        toolResults.push(...stepResults);
        // 工具过程不复制成 PA 的第二条完整会话；只把结果摘要喂回规划轮次。
        extra.push({
          role: "system",
          content: `[PA tool results] ${JSON.stringify(
            stepResults.map((result) => ({
              tool: result.tool,
              ok: result.ok,
              state: result.state,
              commandId: result.commandId,
              error: result.error ?? null,
            })),
          )}`,
        });
        continue;
      }
      const text = typeof response.text === "string" ? response.text : "";
      if (text.trim().length === 0) throw new Error("PA LLM returned an empty reply");
      const message = appendMessage({ role: "assistant", content: text, replyTo });
      return { message, toolResults };
    }
    throw new Error(`PA tool loop exceeded ${maxToolSteps} steps`);
  }

  async function sendUserMessage(text, { attachments = [], messageId = undefined } = {}) {
    ensureOpen();
    if (typeof text !== "string" || text.trim().length === 0) throw new Error("sendUserMessage requires non-empty text");
    if (messageId !== undefined) {
      if (!isIdOf(messageId, "pm")) throw new Error("messageId must be a pm_ ID");
      const existing = store.getMessage(messageId);
      if (existing) {
        if (existing.role !== "user") throw new Error("messageId already belongs to a non-user message");
        return { ok: true, status: "duplicate", userMessage: existing, reply: null, toolResults: [] };
      }
    }
    const userMessage = appendMessage({ id: messageId, role: "user", content: text, attachments, origin: "local" });
    if (!llm) return { ok: true, status: "no_llm", userMessage, reply: null, toolResults: [] };
    try {
      const reply = await respond({ replyTo: userMessage.id });
      return { ok: true, status: "replied", userMessage, reply: reply.message, toolResults: reply.toolResults };
    } catch (error) {
      try {
        audit.append({
          actor: "pa",
          action: "pa.reply",
          decision: "dispatch",
          sourceNode: nodeId,
          params: { userMessageId: userMessage.id },
          result: { state: "failed", ok: false },
          error: error.message,
        });
      } catch {
        // 审计失败不能覆盖原始回复失败。
      }
      return { ok: false, status: "failed", userMessage, reply: null, error: error.message, toolResults: [] };
    }
  }

  function updateProfile(patch = {}) {
    ensureOpen();
    if (patch && typeof patch === "object" && patch.id !== undefined && patch.id !== profile.id) {
      throw new Error("cannot change PA profile id after creation");
    }
    const next = createPaProfile({
      ...profile,
      ...patch,
      id: profile.id,
      createdAt: profile.createdAt,
      updatedAt: nowIso(),
    });
    store.transaction(() => {
      store.saveProfile(next);
    });
    profile = next;
    return { ...profile };
  }

  function mergeIncoming(messages, { fromNode = null } = {}) {
    ensureOpen();
    if (!Array.isArray(messages)) throw new Error("mergeIncoming requires an array");
    const mismatched = [];
    const sameSession = [];
    for (const message of messages) {
      if (message?.paId !== profile.id) {
        mismatched.push({ id: message?.id ?? null, reason: "paId mismatch (single PA session)" });
        continue;
      }
      sameSession.push(message);
      if (typeof message.hlc === "string") {
        try {
          hybridClock.observe(message.hlc);
        } catch {
          // 无效 HLC 会在 mergePaMessages 中作为 rejected 返回。
        }
      }
    }
    const merged = mergePaMessages({ local: store.listMessages(), incoming: sameSession });
    assertSinglePaSession(profile.id, merged.messages);
    const rejectedIds = new Set(merged.rejected.map((entry) => entry.id));
    const candidates = sameSession.filter((message) => !rejectedIds.has(message.id) && !store.hasMessage(message.id));
    const inserted = candidates.length > 0 ? store.insertMessages(candidates) : [];
    const cursorKey = `sync:${nodeId}:lastHlc`;
    if (merged.nextCursor) store.metaSet(cursorKey, merged.nextCursor);
    if (fromNode) store.metaSet(`sync:${fromNode}:lastHlc`, merged.nextCursor ?? "");
    return {
      ok: merged.ok,
      added: inserted.length,
      duplicates: merged.duplicates,
      conflicts: { id: merged.idConflicts, seq: merged.seqConflicts },
      orphans: merged.orphans,
      rejected: [...merged.rejected, ...mismatched],
      cursor: merged.nextCursor ?? store.metaGet(cursorKey),
      viewCount: merged.view.length,
      addedIds: inserted,
    };
  }

  function autonomy() {
    ensureOpen();
    return audit.autonomyState(profile.id);
  }

  function acknowledgeAutonomy({ by = "user" } = {}) {
    ensureOpen();
    return audit.acknowledge({ profileId: profile.id, sourceNode: nodeId, by });
  }

  function diagnostics() {
    let messages = null;
    let audits = null;
    let cursor = null;
    if (!closed || !ownsStore) {
      try {
        messages = store.countMessages();
        audits = store.countAudit();
        cursor = store.metaGet(`sync:${nodeId}:lastHlc`);
      } catch {
        messages = null;
        audits = null;
        cursor = null;
      }
    }
    return {
      schemaVersion: 1,
      profileId: profile.id,
      nodeId,
      closed,
      hasCore: core !== null,
      hasCommandLayer: tools.hasCommandLayer,
      hasLlm: llm !== null,
      messages,
      audits,
      cursor,
      dbPath: store.dbPath,
    };
  }

  const runner = {
    nodeId,
    store,
    tools,
    audit,
    get profile() {
      return { ...profile };
    },
    appendMessage,
    history,
    sendUserMessage,
    updateProfile,
    mergeIncoming,
    autonomy,
    acknowledgeAutonomy,
    diagnostics,
    getProfile: () => ({ ...profile }),
    close() {
      if (closed) return;
      closed = true;
      if (ownsStore) store.close();
    },
    isClosed: () => closed,
  };

  return runner;
}
