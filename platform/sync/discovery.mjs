/**
 * 局域网发现、手动地址、路由选择与重连退避（SYNC-102 / SYNC-111 / SYNC-015）。
 *
 * - 无中心服务：发现只用局域网 UDP 广播/组播或手动地址。
 * - 发现 ≠ 信任：候选设备只是连接候选，必须经 identity.authorizePeer 才能同步。
 * - 广播不可用时手动地址仍可用；中继不可用不影响局域网与本地功能。
 * - UDP 广播是可选能力：`createUdpBeaconTransport` 仅用于真实局域网；单测使用假传输，
 *   另有一个 127.0.0.1 临时端口的真实 UDP 封装测试（不绑固定端口、不联网）。
 */
import { createHash } from "node:crypto";
import dgram from "node:dgram";

export const DISCOVERY_MAGIC = "coda-lan/1";
export const DISCOVERY_VERSION = 1;
export const DISCOVERY_BEACON_PORT = 47823;
export const DEFAULT_BEACON_INTERVAL_MS = 30_000;
export const DEFAULT_BEACON_MAX_AGE_MS = 90_000;
export const MAX_BEACON_BYTES = 2048;

/** 候选地址的优先级：loopback 优先，其次局域网，最后是经公网/中继的地址。 */
export const ROUTE_PRIORITY = Object.freeze({ loopback: 0, lan: 1, wan: 2 });

export class DiscoveryError extends Error {
  constructor(code, message, details = {}) {
    super(message);
    this.name = "DiscoveryError";
    this.code = code;
    this.details = details;
  }
}

export function createBeacon({
  identity,
  port,
  addresses = [],
  protocols = [1],
  now = Date.now,
} = {}) {
  if (!identity?.deviceId) throw new DiscoveryError("IDENTITY_REQUIRED", "identity required");
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new DiscoveryError("INVALID_PORT", `invalid listen port: ${port}`);
  }
  return {
    m: DISCOVERY_MAGIC,
    v: DISCOVERY_VERSION,
    deviceId: identity.deviceId,
    name: identity.name ?? null,
    platform: identity.platform ?? null,
    port,
    protocols: [...protocols],
    addresses: addresses.filter((a) => typeof a === "string").slice(0, 8),
    ts: new Date(now()).toISOString(),
  };
}

export function encodeBeacon(beacon) {
  const buffer = Buffer.from(JSON.stringify(beacon), "utf8");
  if (buffer.length > MAX_BEACON_BYTES) {
    throw new DiscoveryError("BEACON_TOO_LARGE", `beacon exceeds ${MAX_BEACON_BYTES} bytes`);
  }
  return buffer;
}

/** 解析广播包；任何异常都返回 `{ok:false, reason}`，绝不抛错（不可信网络输入）。 */
export function decodeBeacon(buffer, { at = Date.now(), maxAgeMs = DEFAULT_BEACON_MAX_AGE_MS } = {}) {
  try {
    if (buffer === null || buffer === undefined) return { ok: false, reason: "EMPTY_BEACON" };
    const text = Buffer.isBuffer(buffer) ? buffer.toString("utf8") : String(buffer);
    if (text.length > MAX_BEACON_BYTES) return { ok: false, reason: "BEACON_TOO_LARGE" };
    const beacon = JSON.parse(text);
    if (!beacon || typeof beacon !== "object") return { ok: false, reason: "INVALID_BEACON" };
    if (beacon.m !== DISCOVERY_MAGIC) return { ok: false, reason: "WRONG_MAGIC" };
    if (beacon.v !== DISCOVERY_VERSION) return { ok: false, reason: "UNSUPPORTED_VERSION" };
    if (typeof beacon.deviceId !== "string" || !beacon.deviceId.startsWith("dev_")) {
      return { ok: false, reason: "INVALID_DEVICE_ID" };
    }
    if (!Number.isInteger(beacon.port) || beacon.port < 1 || beacon.port > 65535) {
      return { ok: false, reason: "INVALID_PORT" };
    }
    const ts = Date.parse(beacon.ts);
    if (!Number.isFinite(ts)) return { ok: false, reason: "INVALID_TIMESTAMP" };
    if (Math.abs(at - ts) > maxAgeMs) return { ok: false, reason: "STALE_BEACON" };
    if (beacon.protocols !== undefined && !Array.isArray(beacon.protocols)) {
      return { ok: false, reason: "INVALID_PROTOCOLS" };
    }
    if (beacon.addresses !== undefined && !Array.isArray(beacon.addresses)) {
      return { ok: false, reason: "INVALID_ADDRESSES" };
    }
    return { ok: true, beacon };
  } catch {
    return { ok: false, reason: "MALFORMED_BEACON" };
  }
}

export function classifyAddress(host) {
  if (typeof host !== "string" || host.length === 0) return "invalid";
  const h = host.toLowerCase().replace(/^\[|\]$/g, "");
  if (h === "localhost" || h === "::1" || h === "0:0:0:0:0:0:0:1") return "loopback";
  if (/^127\./.test(h)) return "loopback";
  if (/^(10\.|192\.168\.|169\.254\.)/.test(h)) return "lan";
  if (/^172\.(1[6-9]|2[0-9]|3[01])\./.test(h)) return "lan";
  if (/^f[cd][0-9a-f]{2}:/i.test(h)) return "lan";
  if (/^fe[89ab][0-9a-f]:/i.test(h)) return "lan";
  if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return "lan";
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(h)) return "wan";
  return "wan";
}

/**
 * 选择连接路由：同机走 loopback，私网走 LAN，其余走可选中继/WAN。
 * 手工地址不改变路由性质，只改变候选来源。
 */
export function chooseRoute(candidate, { localAddresses = [] } = {}) {
  if (!candidate || typeof candidate !== "object") {
    throw new DiscoveryError("INVALID_CANDIDATE", "candidate required");
  }
  if (!Number.isInteger(candidate.port) || candidate.port < 1 || candidate.port > 65535) {
    throw new DiscoveryError("INVALID_PORT", `invalid candidate port: ${candidate.port}`);
  }
  const host = String(candidate.host ?? "").toLowerCase();
  const local = new Set(localAddresses.map((a) => String(a).toLowerCase()));
  let kind = classifyAddress(host);
  if (kind === "invalid") throw new DiscoveryError("INVALID_HOST", `invalid host: ${candidate.host}`);
  if (local.has(host)) kind = "loopback";
  return {
    kind,
    priority: ROUTE_PRIORITY[kind],
    host: candidate.host,
    port: candidate.port,
    address: `${candidate.host}:${candidate.port}`,
    source: candidate.source ?? "manual",
    deviceId: candidate.deviceId ?? null,
  };
}

/** 指数退避 + 抖动；random 可注入以保证测试可复现。 */
export function createRetryPolicy({
  baseMs = 250,
  maxMs = 30_000,
  factor = 2,
  jitter = 0.2,
  random = Math.random,
} = {}) {
  if (baseMs <= 0 || maxMs < baseMs || factor <= 1) {
    throw new DiscoveryError("INVALID_RETRY_POLICY", "invalid retry policy");
  }
  const delay = (attempt) => {
    if (!Number.isInteger(attempt) || attempt < 1) throw new DiscoveryError("INVALID_ATTEMPT", "attempt must be >= 1");
    const raw = Math.min(maxMs, baseMs * factor ** (attempt - 1));
    const variance = raw * jitter;
    return Math.max(0, Math.round(raw - variance + random() * variance * 2));
  };
  return { baseMs, maxMs, factor, jitter, delay };
}

/** 每个候选设备的连接尝试状态；成功后清零。 */
export function createReconnectTracker({ policy = createRetryPolicy(), clock = Date.now } = {}) {
  const entries = new Map();
  const get = (key) => entries.get(key) ?? { attempts: 0, nextAt: 0 };
  return {
    failure(key) {
      const entry = get(key);
      entry.attempts += 1;
      const delay = policy.delay(entry.attempts);
      entry.nextAt = clock() + delay;
      entries.set(key, entry);
      return { attempts: entry.attempts, delayMs: delay, nextAt: entry.nextAt };
    },
    success(key) {
      entries.delete(key);
    },
    reset(key) {
      entries.delete(key);
    },
    canRetry(key, at = clock()) {
      return at >= get(key).nextAt;
    },
    nextDelay(key) {
      return policy.delay(get(key).attempts + 1);
    },
    state(key) {
      return { ...get(key) };
    },
    snapshot() {
      return [...entries.entries()].map(([key, value]) => ({ key, ...value }));
    },
  };
}

export function candidateKey({ host, port, deviceId = null }) {
  return deviceId ? `dev:${deviceId}` : `addr:${host}:${port}`;
}

/**
 * 发现器：合并自动广播与手动地址；不负责连接与信任。
 */
export function createDiscovery({
  identity,
  transport = null,
  listenPort = null,
  isAuthorized = null,
  clock = Date.now,
  onPeer = null,
  maxPeerAgeMs = DEFAULT_BEACON_MAX_AGE_MS,
} = {}) {
  if (!identity?.deviceId) throw new DiscoveryError("IDENTITY_REQUIRED", "identity required");
  const manual = new Map();
  const seen = new Map();
  const tracker = createReconnectTracker({ clock });
  let timer = null;
  let started = false;

  const unauthorized = (deviceId) => (typeof isAuthorized === "function" ? isAuthorized(deviceId) !== true : false);

  const toCandidate = (entry) => {
    const route = chooseRoute(entry, { localAddresses: entry.localAddresses ?? [] });
    return {
      ...entry,
      key: candidateKey(entry),
      route,
      authorized: entry.deviceId ? !unauthorized(entry.deviceId) : null,
      lastSeenAt: entry.lastSeenAt ?? null,
    };
  };

  const discovery = {
    addManualAddress({ host, port, deviceId = null, label = null, localAddresses = [] } = {}) {
      if (typeof host !== "string" || host.length === 0) {
        throw new DiscoveryError("INVALID_HOST", "host required");
      }
      if (!Number.isInteger(port) || port < 1 || port > 65535) {
        throw new DiscoveryError("INVALID_PORT", `invalid port ${port}`);
      }
      if (deviceId && deviceId === identity.deviceId) return null;
      const entry = {
        host,
        port,
        deviceId,
        label,
        source: "manual",
        addedAt: new Date(clock()).toISOString(),
        localAddresses,
      };
      manual.set(candidateKey(entry), entry);
      return toCandidate(entry);
    },

    removeManualAddress(key) {
      return manual.delete(key);
    },

    listManualAddresses() {
      return [...manual.values()].map(toCandidate);
    },

    /** 合并候选：同一 deviceId 去重（自动发现优先），按路由优先级排序。 */
    listCandidates({ includeUnauthorized = true, at = clock() } = {}) {
      const merged = new Map();
      for (const entry of manual.values()) {
        const candidate = toCandidate(entry);
        if (candidate.deviceId) merged.set(`dev:${candidate.deviceId}`, candidate);
        else merged.set(candidate.key, candidate);
      }
      for (const entry of seen.values()) {
        if (at - entry.seenAtMs > maxPeerAgeMs) continue;
        const candidate = toCandidate(entry);
        merged.set(`dev:${candidate.deviceId}`, candidate);
      }
      return [...merged.values()]
        .filter((c) => includeUnauthorized || c.authorized !== false)
        .sort(
          (a, b) =>
            a.route.priority - b.route.priority ||
            String(a.deviceId ?? "").localeCompare(String(b.deviceId ?? "")) ||
            a.route.address.localeCompare(b.route.address),
        );
    },

    /** 处理收到的广播包；返回新候选或 null。 */
    handleBeacon(raw, { fromHost = null, at = clock() } = {}) {
      const decoded = decodeBeacon(raw, { at, maxAgeMs: maxPeerAgeMs });
      if (!decoded.ok) return null;
      const beacon = decoded.beacon;
      if (beacon.deviceId === identity.deviceId) return null;
      const key = `dev:${beacon.deviceId}`;
      const existing = seen.get(key);
      const entry = {
        host: fromHost ?? existing?.host ?? null,
        port: beacon.port,
        deviceId: beacon.deviceId,
        name: beacon.name ?? existing?.name ?? null,
        platform: beacon.platform ?? existing?.platform ?? null,
        protocols: beacon.protocols ?? existing?.protocols ?? [],
        addresses: beacon.addresses ?? existing?.addresses ?? [],
        source: "discovery",
        firstSeenAt: existing?.firstSeenAt ?? new Date(at).toISOString(),
        lastSeenAt: new Date(at).toISOString(),
        seenAtMs: at,
      };
      if (!entry.host && entry.addresses.length > 0) entry.host = entry.addresses[0];
      if (!entry.host) return null;
      seen.set(key, entry);
      const candidate = toCandidate(entry);
      if (typeof onPeer === "function") {
        try {
          onPeer(candidate);
        } catch {
          /* 上层回调错误不影响发现循环 */
        }
      }
      return candidate;
    },

    announce() {
      if (!transport || !listenPort) return false;
      const beacon = createBeacon({
        identity,
        port: listenPort,
        addresses: [],
        now: clock,
      });
      try {
        transport.send?.(encodeBeacon(beacon));
        return true;
      } catch {
        return false;
      }
    },

    start({ announceIntervalMs = DEFAULT_BEACON_INTERVAL_MS } = {}) {
      if (started) return discovery;
      started = true;
      if (transport?.onMessage) {
        transport.onMessage((raw, info) => discovery.handleBeacon(raw, { fromHost: info?.host ?? null }));
      }
      discovery.announce();
      if (announceIntervalMs > 0 && transport) {
        timer = setInterval(() => discovery.announce(), announceIntervalMs);
        timer.unref?.();
      }
      return discovery;
    },

    stop() {
      if (timer) clearInterval(timer);
      timer = null;
      started = false;
      transport?.close?.();
    },

    recordFailure(key) {
      return tracker.failure(key);
    },
    recordSuccess(key) {
      tracker.success(key);
    },
    canRetry(key, at = clock()) {
      return tracker.canRetry(key, at);
    },
    retryDelay(key) {
      return tracker.nextDelay(key);
    },
    prune({ at = clock() } = {}) {
      for (const [key, entry] of seen) {
        if (at - entry.seenAtMs > maxPeerAgeMs) seen.delete(key);
      }
    },
    get started() {
      return started;
    },
    diagnostics() {
      return {
        started,
        manual: manual.size,
        discovered: seen.size,
        routes: discovery.listCandidates().map((c) => ({ key: c.key, route: c.route.kind, address: c.route.address })),
        retries: tracker.snapshot(),
      };
    },
  };

  return discovery;
}

/**
 * 真实局域网 UDP 广播传输（可选）。
 * - `bind()` 使用 port 0 时由系统分配；返回实际端口。
 * - `send()` 走广播地址；`sendTo()` 用于本机/单播测试或已知地址。
 * - 仅应发送编码后的 beacon；不承载业务数据。
 */
export function createUdpBeaconTransport({
  port = 0,
  broadcastAddress = "255.255.255.255",
  broadcastPort = DISCOVERY_BEACON_PORT,
  host = "0.0.0.0",
} = {}) {
  let socket = null;
  let boundPort = null;
  const handlers = new Set();

  return {
    kind: "udp",
    async bind() {
      if (socket) return { port: boundPort };
      socket = dgram.createSocket({ type: "udp4", reuseAddr: true });
      socket.on("message", (msg, rinfo) => {
        for (const handler of [...handlers]) handler(msg, { host: rinfo.address, port: rinfo.port });
      });
      socket.on("error", () => {
        /* 发现是可选能力：错误不应让核心崩溃 */
      });
      await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new DiscoveryError("UDP_BIND_TIMEOUT", "UDP bind timed out")), 3000);
        timer.unref?.();
        socket.once("error", (err) => {
          clearTimeout(timer);
          reject(err);
        });
        socket.bind(port, host, () => {
          clearTimeout(timer);
          try {
            socket.setBroadcast(true);
          } catch {
            /* 某些网络不允许广播；手动地址仍可用 */
          }
          boundPort = socket.address().port;
          resolve();
        });
      });
      return { port: boundPort };
    },
    get port() {
      return boundPort;
    },
    onMessage(handler) {
      handlers.add(handler);
      return () => handlers.delete(handler);
    },
    send(buffer) {
      if (!socket || boundPort === null) throw new DiscoveryError("UDP_NOT_BOUND", "UDP transport is not bound");
      socket.send(buffer, 0, buffer.length, broadcastPort, broadcastAddress);
    },
    sendTo(buffer, { host: targetHost = "127.0.0.1", port: targetPort } = {}) {
      if (!socket || boundPort === null) throw new DiscoveryError("UDP_NOT_BOUND", "UDP transport is not bound");
      if (!Number.isInteger(targetPort)) throw new DiscoveryError("INVALID_PORT", "target port required");
      socket.send(buffer, 0, buffer.length, targetPort, targetHost);
    },
    close() {
      handlers.clear();
      if (socket) {
        try {
          socket.close();
        } catch {
          /* ignore */
        }
        socket = null;
        boundPort = null;
      }
    },
  };
}

export function beaconHash(buffer) {
  return createHash("sha256").update(buffer).digest("hex").slice(0, 16);
}
