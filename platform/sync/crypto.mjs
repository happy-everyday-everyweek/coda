/**
 * 端到端加密帧与版本协商（SYNC-103 / SEC-007 / SYNC-014）。
 *
 * 握手（对称两段式，无中心服务）：
 *   1. 双方各自发送 HELLO：协议版本、静态 ed25519 公钥、临时 X25519 公钥、nonce。
 *   2. 双方用 X25519 ECDH 得到共享秘密，HKDF-SHA256 派生两个方向的密钥
 *      （按 deviceId 字典序决定 lo→hi / hi→lo）。
 *   3. 双方用静态 ed25519 私钥签名「握手 transcript」（包含双方 deviceId、
 *      静态公钥、临时公钥、协议版本），对端用 HELLO 中的静态公钥验签。
 *   4. 之后所有业务消息进入 ChaCha20-Poly1305 分帧：每帧带严格递增序号，
 *      序号写入 nonce 与 AAD，重复/跳号/篡改一律拒绝。
 *
 * 版本不兼容在握手阶段失败，任何存储写入都尚未发生（SYNC-014）。
 * 中继（若未来启用）只能看到密文（SEC-007）。
 */
import {
  createCipheriv,
  createDecipheriv,
  createHash,
  createPublicKey,
  diffieHellman,
  generateKeyPairSync,
  hkdfSync,
  randomBytes,
  timingSafeEqual,
} from "node:crypto";

import {
  deviceIdFromPublicKey,
  fingerprintOfPublicKey,
  publicKeyPemFromBase64,
  verifySignature,
} from "./identity.mjs";

export const SYNC_PROTOCOL = 1;
export const SUPPORTED_PROTOCOLS = Object.freeze([1]);
export const MAX_FRAME_BYTES = 1024 * 1024;
export const MAX_FRAME_AHEAD = 4096;
export const HANDSHAKE_TIMEOUT_MS = 8000;
export const MAX_HANDSHAKE_BYTES = 16 * 1024;
const AUTH_TAG_BYTES = 16;
const NONCE_BYTES = 12;

export class SyncProtocolError extends Error {
  constructor(code, message, details = {}) {
    super(message);
    this.name = "SyncProtocolError";
    this.code = code;
    this.details = details;
  }
}

/** 双方协议版本取最大交集；无交集返回可操作的升级/重新配对提示。 */
export function negotiateProtocol(remoteVersions, { supported = SUPPORTED_PROTOCOLS } = {}) {
  const remote = Array.isArray(remoteVersions) ? remoteVersions : [remoteVersions];
  if (!remote.every((v) => Number.isInteger(v))) {
    throw new SyncProtocolError("VERSION_MISMATCH", "peer sent invalid protocol versions", {
      local: [...supported],
      remote,
    });
  }
  const common = supported.filter((v) => remote.includes(v));
  if (common.length === 0) {
    throw new SyncProtocolError(
      "VERSION_MISMATCH",
      `incompatible sync protocol: peer supports [${remote.join(", ")}], this device supports [${supported.join(
        ", ",
      )}]. Upgrade Coda on both devices or re-pair after upgrading.`,
      { local: [...supported], remote, action: "upgrade_or_repair" },
    );
  }
  return Math.max(...common);
}

export function createEphemeralKeyPair() {
  const pair = generateKeyPairSync("x25519");
  const publicKeyB64 = pair.publicKey.export({ type: "spki", format: "der" }).toString("base64");
  return { privateKey: pair.privateKey, publicKey: pair.publicKey, publicKeyB64 };
}

/** HELLO / AUTH / FRAME 的线上表示。字段名保持简短以减少帧开销。 */
export function createHello(identity, ephemeralKeyB64, { protocols = SUPPORTED_PROTOCOLS, nonce = randomBytes(16) } = {}) {
  return {
    t: "coda-sync/hello",
    versions: [...protocols],
    deviceId: identity.deviceId,
    staticKey: identity.publicKeyDerBase64,
    ephemeralKey: ephemeralKeyB64,
    nonce: nonce.toString("base64"),
  };
}

export function buildHandshakeTranscript({ version, self, peer }) {
  const entries = [
    { deviceId: self.deviceId, staticKey: self.staticKey, ephemeralKey: self.ephemeralKey },
    { deviceId: peer.deviceId, staticKey: peer.staticKey, ephemeralKey: peer.ephemeralKey },
  ].sort((a, b) => (a.deviceId < b.deviceId ? -1 : a.deviceId > b.deviceId ? 1 : 0));
  return Buffer.from(JSON.stringify(["coda-sync-handshake", version, entries]), "utf8");
}

export function deriveSessionKeys({ sharedSecret, transcript, version = SYNC_PROTOCOL }) {
  if (!Buffer.isBuffer(sharedSecret) || sharedSecret.length === 0) {
    throw new SyncProtocolError("BAD_HANDSHAKE", "empty shared secret");
  }
  const salt = createHash("sha256").update(transcript).digest();
  const derive = (direction) =>
    Buffer.from(hkdfSync("sha256", sharedSecret, salt, Buffer.from(`coda-sync/v${version}/${direction}`), 32));
  return { lo2hi: derive("lo2hi"), hi2lo: derive("hi2lo") };
}

export function noncePrefixFor(key) {
  return createHash("sha256").update(key).update("coda-sync-nonce").digest().subarray(0, 4);
}

export function frameNonce(prefix, seq) {
  if (!Number.isSafeInteger(seq) || seq < 1) throw new SyncProtocolError("BAD_SEQUENCE", "frame seq must be >= 1");
  const nonce = Buffer.alloc(NONCE_BYTES);
  prefix.copy(nonce, 0, 0, 4);
  nonce.writeBigUInt64BE(BigInt(seq), 4);
  return nonce;
}

function frameAad(version, seq) {
  return Buffer.from(JSON.stringify({ v: version, s: seq }), "utf8");
}

/** 加密一帧。返回可直接 JSON 序列化的对象。 */
export function encryptFrame({ key, seq, plaintext, noncePrefix, version = SYNC_PROTOCOL }) {
  if (!Buffer.isBuffer(key) || key.length !== 32) {
    throw new SyncProtocolError("BAD_KEY", "session key must be 32 bytes");
  }
  const payload = Buffer.isBuffer(plaintext) ? plaintext : Buffer.from(String(plaintext), "utf8");
  if (payload.length > MAX_FRAME_BYTES) {
    throw new SyncProtocolError("FRAME_TOO_LARGE", `frame exceeds ${MAX_FRAME_BYTES} bytes`, {
      size: payload.length,
    });
  }
  const nonce = frameNonce(noncePrefix, seq);
  const cipher = createCipheriv("chacha20-poly1305", key, nonce, { authTagLength: AUTH_TAG_BYTES });
  cipher.setAAD(frameAad(version, seq));
  const ciphertext = Buffer.concat([cipher.update(payload), cipher.final()]);
  return {
    v: version,
    s: seq,
    n: nonce.toString("base64"),
    c: ciphertext.toString("base64"),
    t: cipher.getAuthTag().toString("base64"),
  };
}

/**
 * 解密并强制序号连续：重复/乱序/跳号/篡改全部抛错。
 * 可靠有序通道（TCP / 内存通道）上序号必须严格 +1；重连时新会话从 1 开始。
 */
export function decryptFrame({
  key,
  frame,
  noncePrefix,
  lastSeq = 0,
  version = SYNC_PROTOCOL,
  maxFrameBytes = MAX_FRAME_BYTES,
}) {
  if (!frame || typeof frame !== "object") throw new SyncProtocolError("BAD_FRAME", "frame must be an object");
  if (frame.v !== version) {
    throw new SyncProtocolError(
      "FRAME_VERSION_MISMATCH",
      `frame protocol ${frame.v} != negotiated ${version}`,
    );
  }
  if (!Number.isSafeInteger(frame.s) || frame.s < 1) {
    throw new SyncProtocolError("BAD_SEQUENCE", "frame seq must be a positive integer");
  }
  if (frame.s <= lastSeq) {
    throw new SyncProtocolError("REPLAY_DETECTED", `replayed or out-of-order frame seq ${frame.s}`, {
      seq: frame.s,
      lastSeq,
    });
  }
  if (lastSeq > 0 && frame.s !== lastSeq + 1) {
    throw new SyncProtocolError("SEQUENCE_GAP", `frame gap: expected ${lastSeq + 1}, got ${frame.s}`, {
      expected: lastSeq + 1,
      seq: frame.s,
    });
  }
  if (frame.s - lastSeq > MAX_FRAME_AHEAD) {
    throw new SyncProtocolError("SEQUENCE_GAP", "frame sequence jumped too far", {
      seq: frame.s,
      lastSeq,
    });
  }
  const ciphertext = Buffer.from(frame.c ?? "", "base64");
  const tag = Buffer.from(frame.t ?? "", "base64");
  if (ciphertext.length > maxFrameBytes) {
    throw new SyncProtocolError("FRAME_TOO_LARGE", "frame exceeds size limit", { size: ciphertext.length });
  }
  if (tag.length !== AUTH_TAG_BYTES) {
    throw new SyncProtocolError("BAD_FRAME", "invalid auth tag length");
  }
  const nonce = frameNonce(noncePrefix, frame.s);
  const expectedNonce = Buffer.from(frame.n ?? "", "base64");
  if (expectedNonce.length !== NONCE_BYTES || !timingSafeEqual(expectedNonce, nonce)) {
    throw new SyncProtocolError("BAD_NONCE", "frame nonce does not match its sequence");
  }
  let decipher;
  try {
    decipher = createDecipheriv("chacha20-poly1305", key, nonce, { authTagLength: AUTH_TAG_BYTES });
    decipher.setAAD(frameAad(version, frame.s));
    decipher.setAuthTag(tag);
    const plaintext = Buffer.concat([decipher.update(ciphertext), decipher.final()]);
    return { seq: frame.s, plaintext };
  } catch {
    throw new SyncProtocolError("FRAME_AUTH_FAILED", "frame authentication failed", { seq: frame.s });
  }
}

function parseJson(raw, code = "BAD_MESSAGE") {
  try {
    return JSON.parse(typeof raw === "string" ? raw : String(raw));
  } catch {
    throw new SyncProtocolError(code, "message is not valid JSON");
  }
}

/**
 * 在一条可靠有序、字节流/消息流的通道上建立加密会话。
 *
 * transport 契约：`send(string)`、`onData(cb)`、`onClose(cb)`、`close()`。
 * 方法返回的 promise `ready` 在双方 hello + auth 验证、且本地 verifier 通过后 resolve。
 */
export function createSecureSession({
  identity,
  transport,
  verifier = null,
  handshakeTimeoutMs = HANDSHAKE_TIMEOUT_MS,
  clock = Date.now,
  onError = null,
} = {}) {
  if (!identity?.sign || !identity?.deviceId) {
    throw new SyncProtocolError("IDENTITY_REQUIRED", "createSecureSession requires a device identity");
  }
  if (!transport?.send || !transport?.onData) {
    throw new SyncProtocolError("TRANSPORT_REQUIRED", "transport with send/onData required");
  }

  const ephemeral = createEphemeralKeyPair();
  const hello = createHello(identity, ephemeral.publicKeyB64);
  let version = null;
  let keys = null;
  let sendKey = null;
  let sendPrefix = null;
  let recvKey = null;
  let recvPrefix = null;
  let sendSeq = 0;
  let lastRecvSeq = 0;
  let transcript = null;
  let peerHello = null;
  let peer = null;
  let peerAuthVerified = false;
  let localAuthSent = false;
  let state = "handshake";
  let closed = false;
  const envelopeHandlers = new Set();
  const bufferedEnvelopes = [];
  const earlyErrors = [];

  let resolveReady;
  let rejectReady;
  const ready = new Promise((resolve, reject) => {
    resolveReady = resolve;
    rejectReady = reject;
  });
  // 避免 unhandledRejection：调用方可能选择只等待 handshake 失败信号。
  ready.catch(() => {});

  let timeoutHandle = null;
  if (handshakeTimeoutMs > 0) {
    timeoutHandle = setTimeout(() => {
      fail(new SyncProtocolError("HANDSHAKE_TIMEOUT", "peer did not finish handshake in time"));
    }, handshakeTimeoutMs);
    timeoutHandle.unref?.();
  }

  function cleanupListeners() {
    if (timeoutHandle) clearTimeout(timeoutHandle);
    timeoutHandle = null;
  }

  function closeTransport() {
    try {
      transport.close?.();
    } catch {
      /* ignore */
    }
  }

  function fail(err) {
    if (state === "failed" || state === "closed") return;
    state = "failed";
    cleanupListeners();
    closed = true;
    closeTransport();
    rejectReady(err);
    for (const handler of earlyErrors) handler(err);
    if (onError) onError(err);
  }

  function sendWire(message) {
    if (closed) throw new SyncProtocolError("TRANSPORT_CLOSED", "session is closed");
    try {
      transport.send(JSON.stringify(message));
    } catch (err) {
      fail(new SyncProtocolError("TRANSPORT_CLOSED", `transport send failed: ${err.message}`));
    }
  }

  function deriveAndSign() {
    let peerPublic;
    try {
      peerPublic = createPublicKey({ key: Buffer.from(peerHello.ephemeralKey, "base64"), format: "der", type: "spki" });
    } catch {
      throw new SyncProtocolError("BAD_HELLO", "peer ephemeral key is invalid");
    }
    const shared = diffieHellman({ privateKey: ephemeral.privateKey, publicKey: peerPublic });
    transcript = buildHandshakeTranscript({
      version,
      self: { deviceId: identity.deviceId, staticKey: identity.publicKeyDerBase64, ephemeralKey: ephemeral.publicKeyB64 },
      peer: { deviceId: peerHello.deviceId, staticKey: peerHello.staticKey, ephemeralKey: peerHello.ephemeralKey },
    });
    keys = deriveSessionKeys({ sharedSecret: shared, transcript, version });
    const selfIsLo = identity.deviceId < peerHello.deviceId;
    const send = selfIsLo ? keys.lo2hi : keys.hi2lo;
    const receive = selfIsLo ? keys.hi2lo : keys.lo2hi;
    sendKey = send;
    sendPrefix = noncePrefixFor(send);
    recvKey = receive;
    recvPrefix = noncePrefixFor(receive);
    const signature = identity.sign(transcript);
    sendWire({ t: "coda-sync/auth", sig: signature });
    localAuthSent = true;
  }

  function maybeReady() {
    if (state !== "handshake" || !localAuthSent || !peerAuthVerified) return;
    state = "ready";
    cleanupListeners();
    for (const envelope of bufferedEnvelopes.splice(0)) dispatchEnvelope(envelope);
    resolveReady({ peer, version, protocol: version });
  }

  function verifyPeerAuth(sig) {
    if (!transcript) throw new SyncProtocolError("BAD_AUTH", "auth received before hello");
    const peerPem = publicKeyPemFromBase64(peerHello.staticKey);
    if (!verifySignature(transcript, sig, peerPem)) {
      throw new SyncProtocolError("BAD_AUTH", "peer handshake signature verification failed");
    }
    peerAuthVerified = true;
    if (typeof verifier === "function") {
      let verdict;
      try {
        verdict = verifier(peer);
      } catch (err) {
        throw new SyncProtocolError("PEER_NOT_AUTHORIZED", `peer rejected: ${err.message}`, {
          reasonCode: err.code ?? null,
        });
      }
      if (verdict && verdict.ok === false) {
        throw new SyncProtocolError("PEER_NOT_AUTHORIZED", "peer is not paired/authorized", {
          reasonCode: verdict.code ?? null,
        });
      }
    }
  }

  function onHello(msg) {
    if (peerHello) throw new SyncProtocolError("BAD_HELLO", "duplicate hello");
    if (msg.deviceId === identity.deviceId) {
      throw new SyncProtocolError("SELF_CONNECTION", "connected to self");
    }
    version = negotiateProtocol(msg.versions);
    let derivedId;
    let fingerprint;
    try {
      const pem = publicKeyPemFromBase64(msg.staticKey);
      derivedId = deviceIdFromPublicKey(pem);
      fingerprint = fingerprintOfPublicKey(pem);
    } catch {
      throw new SyncProtocolError("BAD_HELLO", "peer static key is invalid");
    }
    if (msg.deviceId !== derivedId) {
      throw new SyncProtocolError("DEVICE_ID_MISMATCH", "peer deviceId does not match its static key");
    }
    if (!msg.ephemeralKey || typeof msg.ephemeralKey !== "string") {
      throw new SyncProtocolError("BAD_HELLO", "peer ephemeral key missing");
    }
    peerHello = msg;
    peer = {
      deviceId: msg.deviceId,
      staticKey: msg.staticKey,
      publicKeyPem: publicKeyPemFromBase64(msg.staticKey),
      fingerprint,
    };
    deriveAndSign();
    maybeReady();
  }

  function dispatchEnvelope(envelope) {
    for (const handler of envelopeHandlers) handler(envelope);
  }

  function onFrame(frame) {
    if (!keys) throw new SyncProtocolError("BAD_FRAME", "encrypted frame before handshake");
    const { seq, plaintext } = decryptFrame({
      key: recvKey,
      frame,
      noncePrefix: recvPrefix,
      lastSeq: lastRecvSeq,
      version,
    });
    lastRecvSeq = seq;
    const envelope = parseJson(plaintext.toString("utf8"), "BAD_ENVELOPE");
    if (!envelope || typeof envelope !== "object") {
      throw new SyncProtocolError("BAD_ENVELOPE", "envelope must be an object");
    }
    if (state === "ready") dispatchEnvelope(envelope);
    else bufferedEnvelopes.push(envelope);
  }

  function handleMessage(msg) {
    if (closed) return;
    if (!msg || typeof msg !== "object") throw new SyncProtocolError("BAD_MESSAGE", "message must be an object");
    switch (msg.t) {
      case "coda-sync/hello":
        onHello(msg);
        break;
      case "coda-sync/auth":
        if (typeof msg.sig !== "string") throw new SyncProtocolError("BAD_AUTH", "auth signature missing");
        verifyPeerAuth(msg.sig);
        maybeReady();
        break;
      case "coda-sync/frame":
        onFrame(msg.f);
        break;
      default:
        throw new SyncProtocolError("UNKNOWN_MESSAGE", `unknown sync message type ${msg.t}`);
    }
  }

  transport.onData((raw) => {
    if (closed) return;
    try {
      const text = typeof raw === "string" ? raw : raw?.toString?.("utf8") ?? String(raw);
      if (text.length > MAX_HANDSHAKE_BYTES && !text.includes("coda-sync/frame")) {
        throw new SyncProtocolError("MESSAGE_TOO_LARGE", "handshake message too large");
      }
      handleMessage(parseJson(text));
    } catch (err) {
      fail(
        err instanceof SyncProtocolError
          ? err
          : new SyncProtocolError("BAD_MESSAGE", `protocol error: ${err.message}`),
      );
    }
  });

  transport.onClose?.((info) => {
    if (state === "failed" || state === "closed") return;
    state = "closed";
    closed = true;
    cleanupListeners();
    rejectReady(new SyncProtocolError("TRANSPORT_CLOSED", "transport closed", info ?? {}));
  });

  sendWire(hello);

  return {
    ready,
    get state() {
      return state;
    },
    get peer() {
      return peer;
    },
    get version() {
      return version;
    },
    get lastReceivedSeq() {
      return lastRecvSeq;
    },
    onEnvelope(handler) {
      if (typeof handler !== "function") throw new SyncProtocolError("BAD_HANDLER", "handler must be a function");
      envelopeHandlers.add(handler);
      return () => envelopeHandlers.delete(handler);
    },
    onError(handler) {
      earlyErrors.push(handler);
      return () => {
        const index = earlyErrors.indexOf(handler);
        if (index >= 0) earlyErrors.splice(index, 1);
      };
    },
    sendEnvelope(envelope) {
      if (state !== "ready" || closed) {
        throw new SyncProtocolError("NOT_READY", "encrypted session is not ready", { state });
      }
      sendSeq += 1;
      const frame = encryptFrame({
        key: sendKey,
        seq: sendSeq,
        plaintext: JSON.stringify(envelope),
        noncePrefix: sendPrefix,
        version,
      });
      sendWire({ t: "coda-sync/frame", f: frame });
      return sendSeq;
    },
    close() {
      if (closed) return;
      state = "closed";
      closed = true;
      cleanupListeners();
      closeTransport();
    },
  };
}
