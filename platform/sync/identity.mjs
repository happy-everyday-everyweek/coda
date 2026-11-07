/**
 * 设备身份、配对载荷与设备撤销（SYNC-101）。
 *
 * - 长期身份密钥：ed25519（签名/设备指纹）；传输层临时密钥：X25519（见 crypto.mjs）。
 * - deviceId 由公钥确定性派生；指纹用于设备上人工比对。
 * - 私钥只写入 0600 文件（security/secrets.mjs），不出现在诊断/同步/日志中。
 * - 配对需要「二维码载荷」或「短码 + 指纹确认」，且短码一次性、限次、限时。
 * - 撤销设备后 authorizePeer 立即拒绝；但撤销不会伪造远端任务已停止（见 reliability.mjs）。
 */
import {
  createHash,
  createPrivateKey,
  createPublicKey,
  generateKeyPairSync,
  randomUUID,
  sign as edSign,
  verify as edVerify,
} from "node:crypto";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { hostname, platform as osPlatform } from "node:os";

import { ensureSecureDir, readSecretFile, writeSecretFile, modeOf } from "../security/secrets.mjs";
import {
  PAIRING_CODE_TTL_MS,
  PairingError,
  consumePairingCode,
  createPairingCode,
  isPairingCodeExpired,
  pairingCodesMatch,
  remainingPairingAttempts,
} from "./pairing.mjs";

export const IDENTITY_SCHEMA_VERSION = 1;
export const DEVICES_SCHEMA_VERSION = 1;
export const PAIRING_PAYLOAD_VERSION = 1;
const PUBLIC_KEY_KEYS = new Set(["publicKey", "publicKeyPem", "staticKey"]);

export class IdentityError extends Error {
  constructor(code, message, details = {}) {
    super(message);
    this.name = "IdentityError";
    this.code = code;
    this.details = details;
  }
}

function keyObjectFromPublic(input) {
  if (!input) throw new IdentityError("PUBLIC_KEY_REQUIRED", "public key required");
  if (typeof input === "object" && typeof input.export === "function") {
    try {
      return createPublicKey(input);
    } catch {
      /* fallthrough */
    }
  }
  if (typeof input !== "string") throw new IdentityError("INVALID_PUBLIC_KEY", "public key must be a string");
  if (input.includes("BEGIN PUBLIC KEY")) return createPublicKey({ key: input, format: "pem" });
  return createPublicKey({ key: Buffer.from(input, "base64"), format: "der", type: "spki" });
}

/** 公钥（PEM / base64 SPKI / KeyObject）→ deviceId。 */
export function deviceIdFromPublicKey(publicKey) {
  const key = keyObjectFromPublic(publicKey);
  const der = key.export({ type: "spki", format: "der" });
  const hash = createHash("sha256").update(der).digest("hex");
  return `dev_${hash.slice(0, 32)}`;
}

/** 人类可比对的公钥指纹：SHA256:XXXX XXXX ...（取前 128 bit）。 */
export function fingerprintOfPublicKey(publicKey) {
  const key = keyObjectFromPublic(publicKey);
  const der = key.export({ type: "spki", format: "der" });
  const hex = createHash("sha256").update(der).digest("hex").toUpperCase().slice(0, 32);
  return `SHA256:${hex.match(/.{4}/g).join(" ")}`;
}

export function publicKeyPemFromBase64(b64) {
  return keyObjectFromPublic(b64).export({ type: "spki", format: "pem" });
}

export function publicKeyBase64FromPem(pem) {
  return keyObjectFromPublic(pem).export({ type: "spki", format: "der" }).toString("base64");
}

export function verifySignature(bytes, signatureB64, publicKey) {
  try {
    const key = keyObjectFromPublic(publicKey);
    const payload = Buffer.isBuffer(bytes) ? bytes : Buffer.from(bytes);
    return edVerify(null, payload, key, Buffer.from(signatureB64, "base64"));
  } catch {
    return false;
  }
}

/** 二维码/链接配对载荷（不含短码；短码单独显示）。 */
export function createPairingPayload({ identity, addresses = [], ttlMs = 10 * 60_000, now = Date.now } = {}) {
  if (!identity?.deviceId) throw new IdentityError("IDENTITY_REQUIRED", "identity required");
  const issuedAt = now();
  return {
    v: PAIRING_PAYLOAD_VERSION,
    type: "coda-pairing-offer",
    deviceId: identity.deviceId,
    name: identity.name,
    platform: identity.platform,
    publicKey: identity.publicKeyDerBase64,
    fingerprint: identity.fingerprint,
    addresses: [...addresses],
    issuedAt: new Date(issuedAt).toISOString(),
    expiresAt: new Date(issuedAt + ttlMs).toISOString(),
  };
}

/** 编码为可放进二维码/链接的字符串。 */
export function encodePairingPayload(payload) {
  const body = Buffer.from(JSON.stringify(payload), "utf8").toString("base64url");
  return `coda://pair?v=1&p=${body}`;
}

/** 解析并校验二维码/链接载荷；失败抛 PairingError('INVALID_PAYLOAD')。 */
export function decodePairingPayload(input, { at = Date.now() } = {}) {
  if (typeof input !== "string" || input.length === 0) {
    throw new PairingError("INVALID_PAYLOAD", "pairing payload must be a non-empty string");
  }
  let raw = input.trim();
  if (raw.startsWith("coda://")) {
    let url;
    try {
      url = new URL(raw.replace(/^coda:\/\//, "https://"));
    } catch {
      throw new PairingError("INVALID_PAYLOAD", "malformed pairing link");
    }
    raw = url.searchParams.get("p") ?? "";
  }
  let payload;
  try {
    payload = JSON.parse(Buffer.from(raw, "base64url").toString("utf8"));
  } catch {
    throw new PairingError("INVALID_PAYLOAD", "pairing payload is not valid base64url JSON");
  }
  if (!payload || typeof payload !== "object" || payload.type !== "coda-pairing-offer") {
    throw new PairingError("INVALID_PAYLOAD", "unrecognized pairing payload type");
  }
  if (payload.v !== PAIRING_PAYLOAD_VERSION) {
    throw new PairingError("UNSUPPORTED_PAYLOAD_VERSION", `unsupported pairing payload version ${payload.v}`);
  }
  if (typeof payload.publicKey !== "string" || payload.publicKey.length === 0) {
    throw new PairingError("INVALID_PAYLOAD", "pairing payload missing public key");
  }
  const derivedId = deviceIdFromPublicKey(payload.publicKey);
  const derivedFingerprint = fingerprintOfPublicKey(payload.publicKey);
  if (payload.deviceId !== derivedId) {
    throw new PairingError("DEVICE_ID_MISMATCH", "pairing payload deviceId does not match its public key");
  }
  if (payload.fingerprint !== derivedFingerprint) {
    throw new PairingError("FINGERPRINT_MISMATCH", "pairing payload fingerprint does not match its public key");
  }
  if (!Array.isArray(payload.addresses)) payload.addresses = [];
  if (payload.expiresAt && Date.parse(payload.expiresAt) <= at) {
    throw new PairingError("PAIRING_EXPIRED", "pairing payload has expired; generate a new offer");
  }
  return payload;
}

function normalizeRemote(remote = {}) {
  if (!remote || typeof remote !== "object") {
    throw new PairingError("INVALID_REMOTE", "remote device description required");
  }
  const keyInput = remote.publicKey ?? remote.publicKeyPem ?? remote.staticKey;
  if (!keyInput) throw new PairingError("INVALID_REMOTE", "remote public key required");
  const publicKeyPem = keyInput.includes?.("BEGIN PUBLIC KEY") ? keyInput : publicKeyPemFromBase64(keyInput);
  const deviceId = remote.deviceId ?? deviceIdFromPublicKey(publicKeyPem);
  const derived = deviceIdFromPublicKey(publicKeyPem);
  if (deviceId !== derived) {
    throw new PairingError("DEVICE_ID_MISMATCH", "remote deviceId does not match its public key");
  }
  const fingerprint = remote.fingerprint ?? fingerprintOfPublicKey(publicKeyPem);
  if (fingerprint !== fingerprintOfPublicKey(publicKeyPem)) {
    throw new PairingError("FINGERPRINT_MISMATCH", "remote fingerprint does not match its public key");
  }
  return {
    deviceId,
    publicKeyPem,
    fingerprint,
    name: typeof remote.name === "string" && remote.name.length > 0 ? remote.name.slice(0, 120) : "unknown device",
    platform: typeof remote.platform === "string" ? remote.platform.slice(0, 60) : "unknown",
  };
}

/**
 * 设备身份对象。同时承载本机 identity 与已配对设备注册表。
 *
 * @param {object} opts
 * @param {string} opts.dir 身份与设备记录目录（自动 0700，文件 0600）
 * @param {string} [opts.name]
 * @param {string} [opts.platform]
 * @param {() => number} [opts.clock]
 * @param {boolean} [opts.persist] 测试可为 false（仍使用 dir 但可重置）
 */
export function createDeviceIdentity({
  dir,
  name = hostname(),
  platform = osPlatform(),
  clock = Date.now,
  persist = true,
} = {}) {
  if (!dir) throw new IdentityError("DIR_REQUIRED", "createDeviceIdentity requires {dir}");
  const directory = ensureSecureDir(dir);
  const identityFile = join(directory, "identity.json");
  const devicesFile = join(directory, "devices.json");
  const now = () => clock();
  const iso = () => new Date(clock()).toISOString();

  let stored = null;
  if (existsSync(identityFile)) {
    const raw = readSecretFile(identityFile);
    try {
      stored = JSON.parse(raw);
    } catch (err) {
      throw new IdentityError("IDENTITY_CORRUPT", `identity.json is invalid JSON: ${err.message}`);
    }
    if (stored.schemaVersion !== IDENTITY_SCHEMA_VERSION) {
      throw new IdentityError("IDENTITY_SCHEMA_MISMATCH", `unsupported identity schema ${stored.schemaVersion}`);
    }
  }

  let privateKey;
  let publicKeyObject;
  if (stored) {
    privateKey = createPrivateKey({ key: stored.privateKeyPem, format: "pem" });
    publicKeyObject = createPublicKey(privateKey);
  } else {
    const pair = generateKeyPairSync("ed25519");
    privateKey = pair.privateKey;
    publicKeyObject = pair.publicKey;
  }
  const publicKeyPem = publicKeyObject.export({ type: "spki", format: "pem" });
  const publicKeyDerBase64 = publicKeyObject.export({ type: "spki", format: "der" }).toString("base64");
  const deviceId = deviceIdFromPublicKey(publicKeyPem);
  const fingerprint = fingerprintOfPublicKey(publicKeyPem);

  if (stored) {
    if (stored.deviceId !== deviceId) {
      throw new IdentityError("IDENTITY_KEY_MISMATCH", "identity file deviceId does not match its private key");
    }
    if (stored.privateKeyPem && !persist) {
      // 测试模式也保持文件一致，不做特殊处理。
    }
  } else {
    const createdAt = iso();
    stored = {
      schemaVersion: IDENTITY_SCHEMA_VERSION,
      deviceId,
      name,
      platform,
      publicKeyPem,
      privateKeyPem: privateKey.export({ type: "pkcs8", format: "pem" }),
      createdAt,
    };
    if (persist) writeSecretFile(identityFile, JSON.stringify(stored, null, 2));
  }

  // ── 设备注册表 ────────────────────────────────────────────────
  let deviceState = { schemaVersion: DEVICES_SCHEMA_VERSION, devices: {} };
  if (existsSync(devicesFile)) {
    const parsed = JSON.parse(readSecretFile(devicesFile));
    if (parsed?.schemaVersion !== DEVICES_SCHEMA_VERSION) {
      throw new IdentityError("DEVICES_SCHEMA_MISMATCH", `unsupported devices schema ${parsed?.schemaVersion}`);
    }
    deviceState = { schemaVersion: DEVICES_SCHEMA_VERSION, devices: parsed.devices ?? {} };
  }
  const saveDevices = () => {
    if (persist) writeSecretFile(devicesFile, JSON.stringify(deviceState, null, 2));
  };

  const pendingOffers = new Map();

  const self = {
    deviceId,
    name: stored.name ?? name,
    platform: stored.platform ?? platform,
    publicKeyPem,
    publicKeyDerBase64,
    fingerprint,
    createdAt: stored.createdAt ?? iso(),
    dir: directory,
    identityFile,
    devicesFile,

    sign(bytes) {
      const payload = Buffer.isBuffer(bytes) ? bytes : Buffer.from(bytes);
      return edSign(null, payload, privateKey).toString("base64");
    },

    publicKeyObject() {
      return publicKeyObject;
    },

    /** 诊断只暴露公钥/指纹与设备数量。 */
    diagnostics() {
      return {
        deviceId,
        name: self.name,
        platform: self.platform,
        fingerprint,
        publicKeyPem,
        createdAt: self.createdAt,
        storage: {
          dirMode: modeOf(directory),
          identityFileMode: modeOf(identityFile),
          devicesFileMode: modeOf(devicesFile),
        },
        pairedDevices: Object.keys(deviceState.devices).length,
        revokedDevices: Object.values(deviceState.devices).filter((d) => d.revokedAt).length,
      };
    },

    listDevices() {
      return Object.values(deviceState.devices)
        .map((d) => ({ ...d }))
        .sort((a, b) => a.deviceId.localeCompare(b.deviceId));
    },

    getDevice(id) {
      const d = deviceState.devices[id];
      return d ? { ...d } : null;
    },

    /**
     * 创建配对 offer：返回二维码载荷与短码（短码只在本次返回值和内存中出现）。
     */
    createPairingOffer({ addresses = [], ttlMs = PAIRING_CODE_TTL_MS, label = null } = {}) {
      const codeRecord = createPairingCode({ ttlMs, now: clock });
      const offerId = randomUUID();
      const payload = createPairingPayload({ identity: self, addresses, now: clock, ttlMs });
      const offer = {
        offerId,
        codeRecord,
        payload,
        label,
        createdAt: iso(),
        expiresAt: payload.expiresAt,
      };
      pendingOffers.set(offerId, offer);
      return {
        offerId,
        code: codeRecord.display,
        qr: encodePairingPayload(payload),
        payload,
        fingerprint,
        expiresAt: codeRecord.expiresAt,
      };
    },

    listPairingOffers() {
      return [...pendingOffers.values()].map((o) => ({
        offerId: o.offerId,
        createdAt: o.createdAt,
        expiresAt: o.expiresAt,
        used: !!o.codeRecord.usedAt,
        attempts: o.codeRecord.attempts,
        remainingAttempts: remainingPairingAttempts(o.codeRecord),
      }));
    },

    /**
     * 接受对方配对：必须确认指纹；短码消耗一次尝试并在成功时失效。
     */
    acceptPairing({ offerId = null, code, remote, fingerprintConfirmed = false, at = clock() } = {}) {
      if (fingerprintConfirmed !== true) {
        throw new PairingError(
          "FINGERPRINT_NOT_CONFIRMED",
          "pairing requires explicit fingerprint confirmation on this device",
        );
      }
      const normalizedRemote = normalizeRemote(remote);
      if (normalizedRemote.deviceId === deviceId) {
        throw new PairingError("SELF_PAIRING", "cannot pair a device with itself");
      }
      let offer = offerId ? pendingOffers.get(offerId) ?? null : null;
      if (!offer) {
        for (const candidate of pendingOffers.values()) {
          if (!candidate.codeRecord.usedAt && pairingCodesMatch(candidate.codeRecord, code)) {
            offer = candidate;
            break;
          }
        }
      }
      if (!offer) throw new PairingError("UNKNOWN_OFFER", "no matching pairing offer; generate a new code");
      if (isPairingCodeExpired(offer.codeRecord, at)) {
        throw new PairingError("PAIRING_EXPIRED", "pairing code expired; generate a new one");
      }
      const result = consumePairingCode(offer.codeRecord, code, { at });
      if (!result.ok) {
        throw new PairingError(result.reason, `pairing failed: ${result.reason}`, {
          remainingAttempts: result.remainingAttempts,
          locked: result.locked,
        });
      }
      const device = addOrUpdateDevice(normalizedRemote, "code");
      pendingOffers.delete(offer.offerId);
      return {
        ok: true,
        device,
        localPayload: createPairingPayload({ identity: self, addresses: offer.payload.addresses }),
      };
    },

    /** 扫描二维码后在本地信任对方（二维码本身即携带完整公钥）。 */
    trustPeerFromOffer(input, { at = clock() } = {}) {
      const payload = typeof input === "string" ? decodePairingPayload(input, { at }) : input;
      if (!payload || payload.type !== "coda-pairing-offer") {
        throw new PairingError("INVALID_PAYLOAD", "not a pairing offer");
      }
      if (payload.deviceId === deviceId) throw new PairingError("SELF_PAIRING", "cannot pair with itself");
      const remote = normalizeRemote({
        deviceId: payload.deviceId,
        publicKeyPem: publicKeyPemFromBase64(payload.publicKey),
        name: payload.name,
        platform: payload.platform,
        fingerprint: payload.fingerprint,
      });
      const device = addOrUpdateDevice(remote, "qr");
      return { ok: true, device, payload };
    },

    revokeDevice(deviceIdToRevoke, { reason = "user_revoked", at = clock() } = {}) {
      const device = deviceState.devices[deviceIdToRevoke];
      if (!device) return { ok: false, code: "UNKNOWN_DEVICE", deviceId: deviceIdToRevoke };
      if (!device.revokedAt) {
        device.revokedAt = new Date(at).toISOString();
        device.revokedReason = reason;
        saveDevices();
      }
      return {
        ok: true,
        device: { ...device },
        note: "revocation blocks new connections and commands; it does not fake remote task termination",
      };
    },

    /**
     * 连接前授权检查（每次新连接都调用）。
     * @returns {{ok:boolean, code?:string, device?:object}}
     */
    authorizePeer({ deviceId: peerDeviceId, publicKeyPem: peerPem, publicKey, fingerprint: peerFingerprint } = {}) {
      if (!peerDeviceId) return { ok: false, code: "UNPAIRED", reason: "missing deviceId" };
      const device = deviceState.devices[peerDeviceId];
      if (!device) return { ok: false, code: "UNPAIRED", reason: "device is not paired" };
      if (device.revokedAt) {
        return {
          ok: false,
          code: "DEVICE_REVOKED",
          reason: "device was revoked",
          revokedAt: device.revokedAt,
        };
      }
      const keyInput = peerPem ?? publicKey;
      if (keyInput) {
        let derived;
        try {
          derived = deviceIdFromPublicKey(keyInput);
        } catch {
          return { ok: false, code: "INVALID_PUBLIC_KEY", reason: "peer public key is invalid" };
        }
        if (derived !== peerDeviceId) {
          return { ok: false, code: "KEY_MISMATCH", reason: "peer public key does not match its deviceId" };
        }
        const normalizedPem = publicKeyPemFromBase64(publicKeyBase64FromPem(keyInput));
        if (normalizedPem !== device.publicKeyPem) {
          return { ok: false, code: "KEY_MISMATCH", reason: "peer public key differs from the paired key" };
        }
        if (peerFingerprint && peerFingerprint !== device.fingerprint) {
          return { ok: false, code: "FINGERPRINT_MISMATCH", reason: "peer fingerprint mismatch" };
        }
      }
      return { ok: true, device: { ...device } };
    },

    isAuthorizedPeer(peer) {
      return self.authorizePeer(peer).ok === true;
    },

    touchDevice(peerDeviceId, { at = clock() } = {}) {
      const device = deviceState.devices[peerDeviceId];
      if (!device) return null;
      device.lastSeenAt = new Date(at).toISOString();
      saveDevices();
      return { ...device };
    },

    close() {
      pendingOffers.clear();
    },
  };

  function addOrUpdateDevice(remote, verifiedBy) {
    const existing = deviceState.devices[remote.deviceId];
    if (existing && existing.publicKeyPem !== remote.publicKeyPem) {
      throw new PairingError("KEY_MISMATCH", "paired device presented a different public key");
    }
    if (existing?.revokedAt) {
      throw new PairingError("DEVICE_REVOKED", "device was revoked; revoke is sticky until explicitly re-paired");
    }
    const record = existing ?? {
      deviceId: remote.deviceId,
      name: remote.name,
      platform: remote.platform,
      publicKeyPem: remote.publicKeyPem,
      fingerprint: remote.fingerprint,
      pairedAt: iso(),
      verifiedBy,
      revokedAt: null,
      revokedReason: null,
      lastSeenAt: null,
    };
    record.name = remote.name;
    record.platform = remote.platform;
    record.fingerprint = remote.fingerprint;
    deviceState.devices[remote.deviceId] = record;
    saveDevices();
    return { ...record };
  }

  return self;
}

export { PUBLIC_KEY_KEYS };
