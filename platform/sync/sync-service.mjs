/**
 * 同步服务入口（CONTRACT.md §8 / SYNC-104..107 / SYNC-111..112）。
 *
 * `createSyncService({storage, identity})` 负责：
 * - 加密连接与设备授权（crypto + identity）；
 * - 快照 + 增量操作日志 + 游标 + 缺口补洞；
 * - PA 追加日志的确定性合并；
 * - 会话元数据、活跃事件窗口、按需历史拉取与在线订阅；
 * - 命令投递/收据/离线队列（commands-queue）；
 * - 审批与提问的跨端竞争（interactions-sync）；
 * - 成果清单同步与按需分块拉取（artifacts）。
 *
 * 本模块不产生权威业务状态：它只搬运已持久化的对象与命令收据（CONTRACT §1）。
 * 无中心服务：所有交换都发生在已配对的两个节点之间；中继不可用不影响局域网。
 */
import { makeId } from "../contracts/ids.mjs";
import { createCommand } from "../contracts/commands.mjs";
import { EVENT_KINDS } from "../contracts/events.mjs";
import { createHybridClock, createPaMessage } from "../pa/schema.mjs";
import { redact } from "../security/redact.mjs";

import {
  createSyncCursor,
  advanceCursor,
  acknowledgeCursor,
  contentHash,
  sortPaMessages,
} from "./cursors.mjs";
import { createCommandQueue } from "./commands-queue.mjs";
import { createInteractionsSync } from "./interactions-sync.mjs";
import { createArtifactTransfer } from "./artifacts.mjs";
import { createSecureSession, SYNC_PROTOCOL } from "./crypto.mjs";

export const SNAPSHOT_VERSION = 1;
export const SYNC_DEFAULTS = Object.freeze({
  eventWindow: 200,
  maxOpsPerMessage: 200,
  historyLimit: 100,
  artifactRequestTimeoutMs: 15_000,
  historyTimeoutMs: 10_000,
});

export class SyncServiceError extends Error {
  constructor(code, message, details = {}) {
    super(message);
    this.name = "SyncServiceError";
    this.code = code;
    this.details = details;
  }
}

function clone(value) {
  return value === undefined ? undefined : structuredClone(value);
}

export function createSyncService({
  storage,
  identity,
  discovery = null,
  clock = Date.now,
  now,
  eventWindow = SYNC_DEFAULTS.eventWindow,
  maxOpsPerMessage = SYNC_DEFAULTS.maxOpsPerMessage,
  commandHandler = null,
  closeStorage = false,
  logger = null,
  paId = null,
} = {}) {
  if (!storage) throw new SyncServiceError("STORAGE_REQUIRED", "createSyncService requires {storage}");
  if (!identity?.deviceId || !identity?.sign) {
    throw new SyncServiceError("IDENTITY_REQUIRED", "createSyncService requires a device identity");
  }
  const ts = () => (typeof now === "function" ? now() : new Date(clock()).toISOString());
  const listeners = new Set();
  const links = new Map(); // peerId → link
  const pendingOps = new Map(); // peerId → Map(seq → op)
  const historyRequests = new Map(); // requestId → {resolve,reject,timer}
  const artifactRequests = new Map(); // artifactId → {resolve,reject,timer}

  // PA 消息严格使用 pa/schema.mjs 的形状与 HLC 全序；单会话 PA 的 paId 默认稳定保存在本地。
  let resolvedPaId = paId;
  if (!resolvedPaId) {
    resolvedPaId = storage.kvGet("pa.id", null);
    if (!resolvedPaId) {
      resolvedPaId = makeId("pa");
      storage.kvSet("pa.id", resolvedPaId);
    }
  }
  const paClock = createHybridClock({ nodeId: identity.deviceId, now: clock });
  const storedHlc = storage.kvGet("pa.hlc", null);
  if (storedHlc) {
    try {
      paClock.observe(storedHlc);
    } catch {
      /* 旧格式/损坏的 HLC 不应阻塞启动；下一条消息重新生成。 */
    }
  }
  const observePaHlc = (message) => {
    if (!message?.hlc) return;
    try {
      paClock.observe(message.hlc);
      storage.kvSet("pa.hlc", paClock.current() ?? message.hlc);
    } catch {
      /* 非 HLC 消息（旧数据）忽略 */
    }
  };

  const commands = createCommandQueue({ storage, deviceId: identity.deviceId, clock });
  const interactions = createInteractionsSync({ storage, deviceId: identity.deviceId, clock });
  const artifacts = createArtifactTransfer({ storage, deviceId: identity.deviceId, clock });

  const log = (level, message, details = {}) => {
    if (typeof logger === "function") {
      try {
        logger(level, message, details);
      } catch {
        /* logger 不得影响同步 */
      }
    }
  };

  function emit(event) {
    for (const listener of [...listeners]) {
      try {
        listener(event);
      } catch (err) {
        log("warn", "subscriber failed", { error: redact(err.message ?? String(err)) });
      }
    }
  }

  function getCursor(peerId) {
    const existing = storage.getCursor(peerId);
    if (existing) return existing;
    const cursor = createSyncCursor({ peerId, now: clock });
    storage.putCursor(cursor);
    return cursor;
  }

  function saveCursor(peerId, cursor) {
    cursor.updatedAt = ts();
    storage.putCursor(cursor);
  }

  function commitOp(type, data) {
    const op = storage.appendOp({ type, data, origin: identity.deviceId, ts: ts() });
    for (const [peerId, link] of links) {
      if (link.paused) continue; // 背压/暂停投递：不消耗加密帧序号
      try {
        link.session.sendEnvelope({ t: "sync.ops", ops: [op] });
      } catch (err) {
        dropPeer(peerId, { reason: "send_failed", code: err.code ?? null });
      }
    }
    return op;
  }

  function dropPeer(peerId, info = {}) {
    const link = links.get(peerId);
    if (!link) return;
    links.delete(peerId);
    pendingOps.delete(peerId);
    try {
      link.session.close();
    } catch {
      /* ignore */
    }
    emit({ type: "peer.offline", peerId, ...info });
  }

  // ── 本地变更（由 core 调用；sync 不产生领域权威状态）────────────────
  function appendSession(session = {}) {
    const id = session.id ?? makeId("ses");
    const existing = storage.getSession(id);
    if (existing && existing.originDeviceId !== identity.deviceId) {
      throw new SyncServiceError("NOT_SESSION_OWNER", "session is owned by another device", {
        sessionId: id,
        owner: existing.originDeviceId,
      });
    }
    const record = {
      title: session.title ?? "Session",
      state: session.state ?? "idle",
      createdAt: existing?.createdAt ?? ts(),
      ...clone(session),
      id,
      originDeviceId: identity.deviceId,
      updatedAt: ts(),
    };
    storage.upsertSession(record);
    commitOp("session.upsert", { session: record });
    emit({ type: "session", session: clone(record), local: true });
    return clone(record);
  }

  function appendEvent({ sessionId, kind, payload = {}, seq = null, ts: eventTs = null } = {}) {
    if (!sessionId) throw new SyncServiceError("SESSION_REQUIRED", "event requires sessionId");
    if (!EVENT_KINDS.includes(kind)) throw new SyncServiceError("INVALID_EVENT_KIND", `unknown event kind ${kind}`);
    const session = storage.getSession(sessionId);
    if (!session) throw new SyncServiceError("UNKNOWN_SESSION", `unknown session ${sessionId}`);
    if (session.originDeviceId !== identity.deviceId) {
      throw new SyncServiceError("NOT_SESSION_OWNER", "only the origin device may append events", {
        sessionId,
        owner: session.originDeviceId,
      });
    }
    const range = storage.eventRange(sessionId);
    const nextSeq = seq ?? (range?.lastSeq ?? 0) + 1;
    const event = {
      id: makeId("evt"),
      sessionId,
      seq: nextSeq,
      kind,
      payload: clone(payload),
      ts: eventTs ?? ts(),
      originDeviceId: identity.deviceId,
    };
    const result = storage.appendEvent(event);
    commitOp("event.append", { event: result.event });
    emit({ type: "event", event: clone(result.event), local: true });
    return clone(result.event);
  }

  /**
   * 追加 PA 消息（pa/schema.mjs 形状）。
   * 旧调用方可用 `{text}` 作为 `content` 的别名；排序使用 HLC，不依赖本机时钟。
   */
  function appendPaMessage({
    paId: targetPaId = null,
    role = "user",
    content = null,
    text = null,
    attachments = [],
    replyTo = null,
    origin = "local",
  } = {}) {
    const seq = storage.listPaMessages().reduce((max, entry) => Math.max(max, Number.isInteger(entry.seq) ? entry.seq : 0), 0) + 1;
    const hlc = paClock.now();
    const message = createPaMessage({
      paId: targetPaId ?? resolvedPaId,
      sourceNode: identity.deviceId,
      role,
      content: content ?? String(text ?? ""),
      attachments,
      replyTo,
      seq,
      ts: ts(),
      hlc,
      origin,
    });
    storage.kvSet("pa.hlc", hlc);
    const result = storage.appendPaMessage(message);
    const stored = result.message;
    commitOp("pa.append", { message: stored });
    emit({ type: "pa", message: clone(stored), local: true });
    return clone(stored);
  }

  function registerInteraction(request) {
    const record = interactions.register(request);
    commitOp("interaction.upsert", { interaction: record });
    emit({ type: "interaction", interaction: clone(record), local: true });
    return record;
  }

  function answerInteraction(interactionId, { value = null } = {}) {
    const result = interactions.answer(interactionId, { deviceId: identity.deviceId, value, at: ts() });
    const answer = result.record.answers.find((a) => a.answerId === result.record.winner?.answerId) ?? null;
    if (result.accepted && answer) {
      commitOp("interaction.answer", { interactionId, answer });
    } else if (result.record.answers.length > 0) {
      const last = result.record.answers[result.record.answers.length - 1];
      commitOp("interaction.answer", { interactionId, answer: last });
    }
    emit({ type: "interaction", interaction: clone(result.record), local: true });
    return result;
  }

  function cancelInteraction(interactionId, { reason = "cancelled" } = {}) {
    const result = interactions.cancel(interactionId, { reason, at: ts() });
    if (result.ok) {
      commitOp("interaction.upsert", { interaction: result.record });
      emit({ type: "interaction", interaction: clone(result.record), local: true });
    }
    return result;
  }

  function offerArtifact(request) {
    const manifest = artifacts.offer(request);
    commitOp("artifact.offer", { artifact: manifest });
    emit({ type: "artifact", artifact: clone(manifest), local: true });
    return manifest;
  }

  // ── 命令 ────────────────────────────────────────────────────
  function sendCommand({ kind, targetDeviceId, payload = {}, ttlMs = 60_000, supersedes = null } = {}) {
    if (!targetDeviceId) throw new SyncServiceError("TARGET_REQUIRED", "sendCommand requires targetDeviceId");
    const command = createCommand({
      kind,
      source: identity.deviceId,
      target: { deviceId: targetDeviceId },
      payload,
      ttlMs,
      supersedes,
    });
    if (supersedes) {
      try {
        commands.supersede(supersedes, { by: command.id });
      } catch {
        /* 旧命令不存在或已终态：不阻塞新命令 */
      }
    }
    const record = commands.enqueue({ command, targetDeviceId });
    emit({ type: "command", command: clone(record) });
    if (targetDeviceId === identity.deviceId) {
      deliverLocalCommand(record.commandId);
    } else if (links.has(targetDeviceId)) {
      deliverCommand(record.commandId);
    } else {
      commands.markWaiting(targetDeviceId);
      const waiting = commands.get(record.commandId);
      emit({ type: "command", command: clone(waiting), waitingForTarget: true });
      return clone(waiting);
    }
    return commands.get(record.commandId);
  }

  function deliverCommand(commandId) {
    const record = commands.get(commandId);
    if (!record) return { ok: false, reason: "UNKNOWN_COMMAND" };
    if (["succeeded", "failed", "expired", "superseded", "unknown"].includes(record.state)) {
      return { ok: false, reason: "TERMINAL" };
    }
    if (Date.parse(record.expiresAt) <= clock()) {
      const expired = commands.expire(commandId, { reason: "ttl_elapsed" });
      emit({ type: "command", command: clone(expired.record), expired: true });
      return { ok: false, reason: "EXPIRED" };
    }
    const link = links.get(record.targetDeviceId);
    if (!link) {
      commands.markWaiting(record.targetDeviceId);
      emit({ type: "command", command: clone(commands.get(commandId)), waitingForTarget: true });
      return { ok: false, reason: "WAITING_FOR_TARGET" };
    }
    const sent = commands.markSent(commandId);
    try {
      link.session.sendEnvelope({ t: "cmd.deliver", command: record.command, sourceDeviceId: identity.deviceId });
    } catch (err) {
      dropPeer(record.targetDeviceId, { reason: "send_failed" });
      commands.markWaiting(record.targetDeviceId);
      return { ok: false, reason: "SEND_FAILED", error: err.message };
    }
    emit({ type: "command", command: clone(sent.record) });
    return { ok: true };
  }

  function deliverLocalCommand(commandId) {
    const record = commands.get(commandId);
    if (!record) return;
    void handleCommandDelivery({ command: record.command }, identity.deviceId, { local: true }).catch((err) => {
      log("warn", "local command failed", { code: err.code ?? null, message: redact(err.message ?? String(err)) });
    });
  }

  function flushCommands(peerId) {
    const expired = commands.expireDue({ at: clock(), targetDeviceId: peerId });
    for (const record of expired) emit({ type: "command", command: clone(record), expired: true });
    for (const record of commands.pendingFor(peerId, { at: clock() })) {
      deliverCommand(record.commandId);
    }
  }

  async function handleCommandDelivery(envelope, peerId, { local = false } = {}) {
    const command = envelope?.command;
    if (!command?.id) return;
    // 本机目标：命令记录已由 sendCommand 持久化为 accepted，不能按“重复投递”处理。
    const existing = local ? commands.get(command.id) : null;
    const received =
      local && existing
        ? { ok: true, duplicate: false, record: existing, receipt: existing.receipt }
        : commands.receive(command);
    if (!received.ok) {
      const receipt = {
        receiptId: `rcpt_rejected_${command.id}`,
        commandId: command.id,
        state: "failed",
        at: ts(),
        result: { reason: received.reason ?? "rejected" },
      };
      sendToPeer(peerId, { t: "cmd.receipt", receipt });
      return;
    }
    sendToPeer(peerId, { t: "cmd.receipt", receipt: received.receipt });
    emit({ type: "command", command: clone(received.record), remote: true });
    if (received.duplicate) {
      if (["succeeded", "failed", "unknown"].includes(received.record.state)) {
        sendToPeer(peerId, { t: "cmd.receipt", receipt: received.record.receipt });
      }
      return;
    }
    if (typeof commandHandler !== "function") return;
    const claimed = commands.begin(command.id);
    if (!claimed.ok) return;
    emit({ type: "command", command: clone(claimed.record), started: true });
    let outcome;
    try {
      const value = await commandHandler(command, { deviceId: identity.deviceId, queue: commands });
      outcome = {
        state: value?.state === "failed" ? "failed" : value?.state === "unknown" ? "unknown" : "succeeded",
        result: value?.result ?? null,
      };
    } catch (err) {
      outcome = { state: "failed", result: { error: redact(err.message ?? String(err)) } };
    }
    const completed = commands.complete(command.id, outcome);
    if (completed.ok) {
      sendToPeer(peerId, { t: "cmd.receipt", receipt: completed.receipt });
      commitOp("command.receipt", { commandId: command.id, receipt: completed.receipt });
      emit({ type: "command.result", commandId: command.id, receipt: clone(completed.receipt) });
    }
  }

  function handleCommandReceipt(receipt) {
    if (!receipt?.commandId) return;
    const result = commands.acknowledge(receipt.commandId, receipt);
    const record = commands.get(receipt.commandId);
    if (record) emit({ type: "command", command: clone(record) });
    if (result.ok && receipt.state && !["accepted", "dispatched"].includes(receipt.state)) {
      emit({ type: "command.result", commandId: receipt.commandId, receipt: clone(receipt) });
    }
  }

  // ── 同步交换 ────────────────────────────────────────────────
  function buildSnapshot() {
    const sessions = storage.listSessions();
    const events = {};
    for (const session of sessions) {
      const range = storage.eventRange(session.id);
      const fromSeq = range ? Math.max(1, range.lastSeq - eventWindow + 1) : 1;
      const window = storage.listEvents(session.id, { fromSeq });
      events[session.id] = {
        firstSeq: range?.firstSeq ?? null,
        lastSeq: range?.lastSeq ?? null,
        truncated: !!range && range.firstSeq < fromSeq,
        events: window,
      };
    }
    return {
      t: "sync.snapshot",
      snapshotVersion: SNAPSHOT_VERSION,
      opSeq: storage.opSeq,
      eventWindow,
      sessions,
      events,
      paMessages: sortPaMessages(storage.listPaMessages()),
      interactions: storage.listInteractions(),
      artifacts: storage.listArtifacts(),
      commandReceipts: storage.listCommandReceipts(),
    };
  }

  function sendSnapshot(peerId) {
    sendToPeer(peerId, buildSnapshot());
  }

  function sendOpsSince(peerId, since) {
    const ops = storage.listOpsAfter(since);
    for (let index = 0; index < ops.length; index += maxOpsPerMessage) {
      sendToPeer(peerId, { t: "sync.ops", ops: ops.slice(index, index + maxOpsPerMessage) });
    }
    sendToPeer(peerId, { t: "sync.ops.done", latestSeq: storage.opSeq });
  }

  function sendToPeer(peerId, envelope) {
    const link = links.get(peerId);
    if (!link || link.paused) return false;
    try {
      link.session.sendEnvelope(envelope);
      return true;
    } catch (err) {
      dropPeer(peerId, { reason: "send_failed", error: redact(err.message ?? String(err)) });
      return false;
    }
  }

  function handleSyncHello(peerId, msg) {
    const link = links.get(peerId);
    if (!link) return;
    const since = Number.isInteger(msg.since) ? msg.since : null;
    const earliest = storage.earliestOpSeq();
    const canResume = since !== null && since >= earliest - 1 && since <= storage.opSeq;
    if (since === null || since === 0 || !canResume) {
      sendSnapshot(peerId);
      return;
    }
    sendOpsSince(peerId, since);
  }

  function mergeSession(remote) {
    if (!remote?.id) return null;
    const local = storage.getSession(remote.id);
    if (!local) {
      storage.upsertSession(remote);
      return clone(remote);
    }
    if (local.originDeviceId === identity.deviceId) {
      return clone(local);
    }
    const newer = (remote.updatedAt ?? "") > (local.updatedAt ?? "");
    if (newer) {
      storage.upsertSession(remote);
      return clone(remote);
    }
    if ((remote.updatedAt ?? "") === (local.updatedAt ?? "")) {
      if (contentHash(remote) < contentHash(local)) {
        storage.upsertSession(remote);
        return clone(remote);
      }
    }
    return clone(local);
  }

  function applySnapshot(peerId, snapshot) {
    if (snapshot.snapshotVersion !== SNAPSHOT_VERSION) {
      throw new SyncServiceError(
        "VERSION_MISMATCH",
        `unsupported snapshot version ${snapshot.snapshotVersion}; upgrade Coda on both devices`,
        { local: SNAPSHOT_VERSION, remote: snapshot.snapshotVersion },
      );
    }
    for (const session of snapshot.sessions ?? []) mergeSession(session);
    for (const [sessionId, bundle] of Object.entries(snapshot.events ?? {})) {
      for (const event of bundle.events ?? []) {
        const result = storage.appendEvent({ ...event, sessionId });
        if (!result.duplicate) emit({ type: "event", event: clone(result.event), remote: true });
      }
      storage.kvSet(`history.${sessionId}`, {
        firstSeq: bundle.firstSeq,
        lastSeq: bundle.lastSeq,
        truncated: bundle.truncated,
        syncedAt: ts(),
      });
    }
    for (const message of snapshot.paMessages ?? []) {
      observePaHlc(message);
      const result = storage.appendPaMessage(message);
      if (!result.duplicate) emit({ type: "pa", message: clone(result.message), remote: true });
    }
    for (const interaction of snapshot.interactions ?? []) {
      const applied = interactions.applyRequest(interaction);
      emit({ type: "interaction", interaction: applied, remote: true });
    }
    for (const artifact of snapshot.artifacts ?? []) {
      try {
        artifacts.applyManifest(artifact);
        emit({ type: "artifact", artifact: clone(artifact), remote: true });
      } catch {
        /* 清单非法/冲突：拒绝该条，不影响其他同步 */
      }
    }
    for (const entry of snapshot.commandReceipts ?? []) {
      if (entry.receipt) handleCommandReceipt(entry.receipt);
    }
    const cursor = getCursor(peerId);
    cursor.remoteSeq = Math.max(cursor.remoteSeq, snapshot.opSeq ?? 0);
    cursor.snapshotVersion = SNAPSHOT_VERSION;
    saveCursor(peerId, cursor);
    emit({ type: "snapshot", peerId, opSeq: cursor.remoteSeq });
  }

  function applyRemoteOp(peerId, op) {
    if (!op || typeof op !== "object" || !Number.isInteger(op.seq)) return { applied: false, reason: "INVALID_OP" };
    if (op.origin !== peerId) return { applied: false, reason: "FOREIGN_ORIGIN" };
    switch (op.type) {
      case "session.upsert": {
        const session = mergeSession(op.data?.session);
        if (session) emit({ type: "session", session, remote: true });
        break;
      }
      case "event.append": {
        const result = storage.appendEvent(op.data.event);
        if (!result.duplicate) emit({ type: "event", event: clone(result.event), remote: true });
        break;
      }
      case "pa.append": {
        const message = op.data?.message;
        if (!message?.id) break;
        observePaHlc(message);
        const result = storage.appendPaMessage(message);
        if (!result.duplicate) emit({ type: "pa", message: clone(result.message), remote: true });
        break;
      }
      case "interaction.upsert": {
        const applied = interactions.applyRequest(op.data.interaction);
        emit({ type: "interaction", interaction: applied, remote: true });
        break;
      }
      case "interaction.answer": {
        try {
          const result = interactions.applyAnswer(op.data.interactionId, op.data.answer);
          emit({ type: "interaction", interaction: result.record, remote: true });
        } catch {
          /* 请求尚未同步：忽略该回答，等待快照/重传 */
        }
        break;
      }
      case "artifact.offer": {
        try {
          artifacts.applyManifest(op.data.artifact);
          emit({ type: "artifact", artifact: clone(op.data.artifact), remote: true });
        } catch {
          /* 非法清单：拒绝 */
        }
        break;
      }
      case "command.receipt": {
        handleCommandReceipt(op.data.receipt);
        break;
      }
      default:
        return { applied: false, reason: "UNKNOWN_OP" };
    }
    return { applied: true };
  }

  function drainBuffered(peerId) {
    const link = links.get(peerId);
    const buffered = pendingOps.get(peerId);
    if (!link || !buffered) return;
    let progressed = true;
    while (progressed) {
      progressed = false;
      const next = buffered.get(link.cursor.remoteSeq + 1);
      if (!next) break;
      buffered.delete(next.seq);
      const advanced = advanceCursor(link.cursor, next.seq, { at: clock() });
      if (advanced.advanced) {
        applyRemoteOp(peerId, next);
        progressed = true;
      }
    }
  }

  function receiveOps(peerId, ops = []) {
    const link = links.get(peerId);
    if (!link) throw new SyncServiceError("UNKNOWN_PEER", `unknown peer ${peerId}`);
    if (!Array.isArray(ops)) return { applied: 0 };
    let buffered = pendingOps.get(peerId);
    if (!buffered) {
      buffered = new Map();
      pendingOps.set(peerId, buffered);
    }
    let applied = 0;
    for (const op of [...ops].sort((a, b) => a.seq - b.seq)) {
      if (!op || !Number.isInteger(op.seq)) continue;
      if (op.seq <= link.cursor.remoteSeq) continue;
      if (op.seq === link.cursor.remoteSeq + 1) {
        advanceCursor(link.cursor, op.seq, { at: clock() });
        applyRemoteOp(peerId, op);
        applied += 1;
        drainBuffered(peerId);
      } else {
        buffered.set(op.seq, op);
      }
    }
    saveCursor(peerId, link.cursor);
    sendToPeer(peerId, { t: "sync.ack", seq: link.cursor.remoteSeq });
    if (buffered.size > 0) {
      sendToPeer(peerId, { t: "sync.missed", fromSeq: link.cursor.remoteSeq + 1 });
    }
    return { applied, buffered: buffered.size, remoteSeq: link.cursor.remoteSeq };
  }

  // ── 历史窗口 ────────────────────────────────────────────────
  async function fetchHistory(sessionId, { beforeSeq = null, limit = SYNC_DEFAULTS.historyLimit } = {}) {
    const session = storage.getSession(sessionId);
    if (!session) throw new SyncServiceError("UNKNOWN_SESSION", `unknown session ${sessionId}`);
    const range = storage.eventRange(sessionId);
    const coverage = storage.kvGet(`history.${sessionId}`, null);
    const firstAvailable = range?.firstSeq ?? null;
    const threshold = beforeSeq ?? (firstAvailable ?? 1);
    const knownFirst = coverage?.firstSeq ?? null;
    const historyComplete = knownFirst !== null && firstAvailable !== null && firstAvailable <= knownFirst;
    if (firstAvailable !== null && firstAvailable < threshold) {
      return {
        available: true,
        fromLocalCopy: true,
        events: storage.listEvents(sessionId, { beforeSeq: threshold, limit }),
      };
    }
    if (historyComplete && beforeSeq === null) {
      return { available: true, fromLocalCopy: true, events: storage.listEvents(sessionId, { beforeSeq: threshold, limit }) };
    }
    if (session.originDeviceId === identity.deviceId) {
      return { available: true, fromLocalCopy: true, events: storage.listEvents(sessionId, { beforeSeq: threshold, limit }) };
    }
    const link = links.get(session.originDeviceId);
    if (!link) {
      return {
        available: false,
        requiresOriginOnline: true,
        reason: "ORIGIN_OFFLINE",
        unsyncedFrom: coverage?.firstSeq ?? null,
        message: "requires the origin device to be online for this history window",
        events: [],
      };
    }
    const requestId = `hist_${makeId("evt").slice(4)}`;
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        historyRequests.delete(requestId);
        resolve({
          available: false,
          requiresOriginOnline: true,
          reason: "HISTORY_TIMEOUT",
          unsyncedFrom: coverage?.firstSeq ?? null,
          events: [],
        });
      }, SYNC_DEFAULTS.historyTimeoutMs);
      timer.unref?.();
      historyRequests.set(requestId, { resolve, timer, sessionId });
      sendToPeer(session.originDeviceId, {
        t: "sync.history.request",
        requestId,
        sessionId,
        beforeSeq: threshold,
        limit,
      });
    });
  }

  function handleHistoryRequest(peerId, msg) {
    const events = storage.listEvents(msg.sessionId, {
      beforeSeq: msg.beforeSeq ?? null,
      limit: Math.min(Number.isInteger(msg.limit) ? msg.limit : SYNC_DEFAULTS.historyLimit, 500),
    });
    const range = storage.eventRange(msg.sessionId);
    sendToPeer(peerId, {
      t: "sync.history.response",
      requestId: msg.requestId,
      sessionId: msg.sessionId,
      events,
      firstSeq: range?.firstSeq ?? null,
      lastSeq: range?.lastSeq ?? null,
    });
  }

  function handleHistoryResponse(msg) {
    const pending = historyRequests.get(msg.requestId);
    if (!pending) return;
    clearTimeout(pending.timer);
    historyRequests.delete(msg.requestId);
    for (const event of msg.events ?? []) storage.appendEvent(event);
    storage.kvSet(`history.${msg.sessionId}`, {
      firstSeq: msg.firstSeq,
      lastSeq: msg.lastSeq,
      truncated: false,
      syncedAt: ts(),
    });
    pending.resolve({ available: true, fromLocalCopy: true, events: msg.events ?? [] });
  }

  // ── 成果按需拉取 ────────────────────────────────────────────
  async function fetchArtifact(artifactId, { timeoutMs = SYNC_DEFAULTS.artifactRequestTimeoutMs } = {}) {
    const manifest = storage.getArtifact(artifactId);
    if (!manifest) throw new SyncServiceError("UNKNOWN_ARTIFACT", `unknown artifact ${artifactId}`);
    if (artifacts.isAvailable(artifactId)) {
      return { artifactId, bytes: artifacts.read(artifactId), manifest: artifacts.manifest(artifactId), fromLocalCopy: true };
    }
    if (manifest.originDeviceId === identity.deviceId) {
      throw new SyncServiceError("NO_BYTES", "origin copy is unavailable on this node");
    }
    const link = links.get(manifest.originDeviceId);
    if (!link) {
      throw new SyncServiceError("ORIGIN_OFFLINE", "artifact bytes require the origin device to be online", {
        originDeviceId: manifest.originDeviceId,
        requiresOriginOnline: true,
      });
    }
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        artifactRequests.delete(artifactId);
        reject(new SyncServiceError("ARTIFACT_TIMEOUT", "artifact transfer timed out", { artifactId }));
      }, timeoutMs);
      timer.unref?.();
      artifactRequests.set(artifactId, { resolve, reject, timer });
      sendToPeer(manifest.originDeviceId, {
        t: "artifact.request",
        artifactId,
        missing: artifacts.missingChunks(artifactId),
      });
    });
  }

  function handleArtifactRequest(peerId, msg) {
    try {
      if (!artifacts.hasBytes(msg.artifactId)) {
        sendToPeer(peerId, { t: "artifact.error", artifactId: msg.artifactId, code: "NO_BYTES" });
        return;
      }
      const manifest = artifacts.manifest(msg.artifactId);
      const missing = new Set(Array.isArray(msg.missing) ? msg.missing : []);
      const have = [];
      for (let index = 0; index < manifest.chunkCount; index += 1) {
        if (!missing.has(index)) have.push(index);
      }
      const { chunks } = artifacts.chunksFor(msg.artifactId, { have });
      for (const chunk of chunks) {
        sendToPeer(peerId, { t: "artifact.chunk", ...chunk });
      }
      sendToPeer(peerId, { t: "artifact.done", artifactId: msg.artifactId });
    } catch (err) {
      sendToPeer(peerId, { t: "artifact.error", artifactId: msg.artifactId, code: err.code ?? "TRANSFER_FAILED" });
    }
  }

  function handleArtifactChunk(msg) {
    try {
      const result = artifacts.putChunk(msg);
      if (result.complete) {
        const finalized = artifacts.finalize(msg.artifactId);
        const pending = artifactRequests.get(msg.artifactId);
        if (pending) {
          clearTimeout(pending.timer);
          artifactRequests.delete(msg.artifactId);
          emit({ type: "artifact", artifact: artifacts.manifest(msg.artifactId), transferred: true });
          pending.resolve({
            artifactId: msg.artifactId,
            bytes: artifacts.read(msg.artifactId),
            manifest: artifacts.manifest(msg.artifactId),
            sha256: finalized.sha256,
            fromLocalCopy: false,
          });
        }
      }
    } catch (err) {
      const pending = artifactRequests.get(msg.artifactId);
      if (pending) {
        clearTimeout(pending.timer);
        artifactRequests.delete(msg.artifactId);
        pending.reject(new SyncServiceError("ARTIFACT_TRANSFER_FAILED", err.message, { code: err.code }));
      }
      emit({ type: "artifact.error", artifactId: msg.artifactId, code: err.code ?? "TRANSFER_FAILED" });
    }
  }

  // ── 入站分发 ────────────────────────────────────────────────
  function receiveEnvelope(peerId, envelope) {
    if (!envelope || typeof envelope !== "object") return;
    const link = links.get(peerId);
    if (!link) throw new SyncServiceError("UNKNOWN_PEER", `envelope from unknown peer ${peerId}`);
    switch (envelope.t) {
      case "sync.hello":
        handleSyncHello(peerId, envelope);
        break;
      case "sync.ops":
        receiveOps(peerId, envelope.ops ?? []);
        break;
      case "sync.ops.done":
        if (Number.isInteger(envelope.latestSeq) && envelope.latestSeq > link.cursor.remoteSeq) {
          sendToPeer(peerId, { t: "sync.missed", fromSeq: link.cursor.remoteSeq + 1 });
        }
        break;
      case "sync.missed":
        sendSnapshot(peerId);
        break;
      case "sync.ack":
        if (Number.isInteger(envelope.seq)) {
          acknowledgeCursor(link.cursor, envelope.seq);
          saveCursor(peerId, link.cursor);
          link.ackSeq = envelope.seq;
        }
        break;
      case "sync.snapshot":
        applySnapshot(peerId, envelope);
        break;
      case "sync.history.request":
        handleHistoryRequest(peerId, envelope);
        break;
      case "sync.history.response":
        handleHistoryResponse(envelope);
        break;
      case "cmd.deliver":
        void handleCommandDelivery(envelope, peerId).catch((err) => {
          log("warn", "command delivery failed", { code: err.code ?? null, message: redact(err.message ?? String(err)) });
        });
        break;
      case "cmd.receipt":
        handleCommandReceipt(envelope.receipt);
        break;
      case "artifact.request":
        handleArtifactRequest(peerId, envelope);
        break;
      case "artifact.chunk":
        handleArtifactChunk(envelope);
        break;
      case "artifact.error":
        {
          const pending = artifactRequests.get(envelope.artifactId);
          if (pending) {
            clearTimeout(pending.timer);
            artifactRequests.delete(envelope.artifactId);
            pending.reject(
              new SyncServiceError("ARTIFACT_UNAVAILABLE", `artifact not available: ${envelope.code}`, {
                code: envelope.code,
              }),
            );
          }
        }
        break;
      case "artifact.done":
        break;
      case "ping":
        sendToPeer(peerId, { t: "pong", at: ts() });
        break;
      case "pong":
        break;
      default:
        log("warn", "unknown sync envelope", { type: envelope.t, peerId });
    }
  }

  // ── 连接管理 ────────────────────────────────────────────────
  async function attachTransport(transport) {
    let peerId = null;
    const early = [];
    const session = createSecureSession({
      identity,
      transport,
      clock,
      verifier: (peer) => {
        const verdict = identity.authorizePeer(peer);
        if (!verdict.ok) {
          throw new SyncServiceError(verdict.code ?? "PEER_NOT_AUTHORIZED", "peer is not paired/authorized", {
            deviceId: peer.deviceId,
          });
        }
        return verdict;
      },
      onError: (err) => {
        log("warn", "secure session error", { code: err.code ?? null, message: redact(err.message ?? String(err)) });
      },
    });
    session.onEnvelope((envelope) => {
      if (!peerId) {
        early.push(envelope);
        return;
      }
      try {
        receiveEnvelope(peerId, envelope);
      } catch (err) {
        log("warn", "envelope rejected", { code: err.code ?? null, peerId });
      }
    });
    let info;
    try {
      info = await session.ready;
    } catch (err) {
      session.close();
      throw err;
    }
    peerId = info.peer.deviceId;
    const existing = links.get(peerId);
    if (existing) existing.session.close();
    const cursor = getCursor(peerId);
    const link = {
      peerId,
      session,
      peer: info.peer,
      cursor,
      ackSeq: 0,
      connectedAt: ts(),
      transportKind: transport.kind ?? "custom",
      paused: false,
    };
    links.set(peerId, link);
    transport.onClose?.((infoClose) => {
      if (links.get(peerId)?.session === session) {
        dropPeer(peerId, { reason: infoClose?.reason ?? "closed" });
      }
    });
    identity.touchDevice?.(peerId, { at: clock() });
    emit({
      type: "peer.online",
      peerId,
      peer: { ...info.peer, fingerprint: info.peer.fingerprint },
      protocol: info.version,
      transport: link.transportKind,
    });
    sendToPeer(peerId, {
      t: "sync.hello",
      since: cursor.remoteSeq,
      snapshotVersion: cursor.snapshotVersion,
      protocol: info.version,
      deviceId: identity.deviceId,
      eventWindow,
    });
    for (const envelope of early.splice(0)) {
      try {
        receiveEnvelope(peerId, envelope);
      } catch (err) {
        log("warn", "early envelope rejected", { code: err.code ?? null, peerId });
      }
    }
    flushCommands(peerId);
    return { peerId, peer: info.peer, protocol: info.version, cursor: clone(cursor) };
  }

  function disconnectPeer(peerId, { reason = "local_disconnect" } = {}) {
    dropPeer(peerId, { reason });
  }

  function isPeerOnline(peerId) {
    return links.has(peerId);
  }

  /**
   * 暂停/恢复向某对端的投递（背压或测试注入）。
   * 暂停期间不调用 sendEnvelope，因此不会消耗加密帧序号；恢复后帧序号保持连续，
   * 不会触发 SEQUENCE_GAP。业务操作仍照常写入本地 op log，恢复后可补发。
   */
  function pauseDelivery(peerId) {
    const link = links.get(peerId);
    if (!link) return false;
    link.paused = true;
    return true;
  }

  function resumeDelivery(peerId) {
    const link = links.get(peerId);
    if (!link) return false;
    link.paused = false;
    return true;
  }

  function listPeers() {
    return [...links.values()].map((link) => ({
      peerId: link.peerId,
      fingerprint: link.peer.fingerprint,
      protocol: SYNC_PROTOCOL,
      transport: link.transportKind,
      cursor: clone(link.cursor),
      connectedAt: link.connectedAt,
    }));
  }

  const service = {
    deviceId: identity.deviceId,
    identity,
    storage,
    discovery,
    commands,
    interactions,
    artifacts,

    subscribe(listener) {
      if (typeof listener !== "function") throw new SyncServiceError("INVALID_LISTENER", "listener must be a function");
      listeners.add(listener);
      return () => listeners.delete(listener);
    },

    appendSession,
    appendEvent,
    appendPaMessage,
    registerInteraction,
    answerInteraction,
    cancelInteraction,
    offerArtifact,
    fetchArtifact,
    sendCommand,
    fetchHistory,

    attachTransport,
    disconnectPeer,
    isPeerOnline,
    pauseDelivery,
    resumeDelivery,
    listPeers,
    receiveEnvelope,
    receiveOps,
    buildSnapshot,
    applySnapshot(peerId, snapshot) {
      if (!links.has(peerId)) throw new SyncServiceError("UNKNOWN_PEER", `unknown peer ${peerId}`);
      applySnapshot(peerId, snapshot);
    },

    expireInteractions() {
      const expired = interactions.expire({ at: clock() });
      for (const record of expired) {
        commitOp("interaction.upsert", { interaction: record });
        emit({ type: "interaction", interaction: clone(record) });
      }
      return expired;
    },

    /** 失败回合结算（REL-002）：调用 core 的 failure 处理后再同步结果。 */
    settleInteractions(reason = "agent_crashed") {
      const settled = interactions.settlePending({ reason, at: ts() });
      for (const record of settled) {
        commitOp("interaction.upsert", { interaction: record });
        emit({ type: "interaction", interaction: clone(record) });
      }
      return settled;
    },

    flushCommands,

    getHistoryCoverage(sessionId) {
      const range = storage.eventRange(sessionId);
      const coverage = storage.kvGet(`history.${sessionId}`, null);
      return {
        sessionId,
        localFirstSeq: range?.firstSeq ?? null,
        localLastSeq: range?.lastSeq ?? null,
        originFirstSeq: coverage?.firstSeq ?? null,
        truncated: coverage?.truncated ?? false,
        requiresOriginOnline: !!(coverage?.truncated && range?.firstSeq !== coverage?.firstSeq),
      };
    },

    dump() {
      const sessions = storage.listSessions();
      return {
        deviceId: identity.deviceId,
        opSeq: storage.opSeq,
        sessions,
        events: Object.fromEntries(sessions.map((session) => [session.id, storage.listEvents(session.id)])),
        paMessages: sortPaMessages(storage.listPaMessages()),
        interactions: storage.listInteractions().map((record) => ({
          id: record.id,
          sessionId: record.sessionId,
          status: record.status,
          winner: record.winner,
          answers: record.answers,
        })),
        artifacts: storage.listArtifacts(),
        commands: storage.listCommands({}).map((record) => ({
          commandId: record.commandId,
          state: record.state,
          delivery: record.delivery,
          receipt: record.receipt,
        })),
        cursors: storage.listCursors(),
      };
    },

    diagnostics() {
      return {
        deviceId: identity.deviceId,
        protocol: SYNC_PROTOCOL,
        snapshotVersion: SNAPSHOT_VERSION,
        peers: listPeers(),
        storage: storage.stats?.() ?? { schemaVersion: storage.schemaVersion, opSeq: storage.opSeq },
        commands: commands.stats(),
        interactions: interactions.stats(),
        artifacts: artifacts.stats(),
        paMessages: storage.listPaMessages().length,
      };
    },

    close() {
      for (const peerId of [...links.keys()]) dropPeer(peerId, { reason: "service_close" });
      for (const pending of historyRequests.values()) {
        clearTimeout(pending.timer);
        pending.resolve({ available: false, requiresOriginOnline: true, reason: "SERVICE_CLOSED", events: [] });
      }
      historyRequests.clear();
      for (const pending of artifactRequests.values()) {
        clearTimeout(pending.timer);
        pending.reject(new SyncServiceError("SERVICE_CLOSED", "sync service closed"));
      }
      artifactRequests.clear();
      listeners.clear();
      if (closeStorage) storage.close();
    },
  };

  return service;
}
