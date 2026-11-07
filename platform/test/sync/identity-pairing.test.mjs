/** SYNC-101 / SYNC-002 / SYNC-010：设备身份、二维码/短码配对、短码生命周期、撤销。 */
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync, statSync } from "node:fs";
import { join } from "node:path";

import {
  createDeviceIdentity,
  decodePairingPayload,
  encodePairingPayload,
  fingerprintOfPublicKey,
  deviceIdFromPublicKey,
} from "../../sync/identity.mjs";
import {
  PAIRING_ALPHABET,
  consumePairingCode,
  createPairingCode,
  pairingCodesMatch,
  verifyPairingCode,
} from "../../sync/pairing.mjs";
import { makeTmpDir, pairNodes } from "./helpers.mjs";

test("设备身份：ed25519 持久化、deviceId/指纹确定、文件权限 0600/0700", (t) => {
  const dir = makeTmpDir(t);
  const id = createDeviceIdentity({ dir: join(dir, "identity"), name: "phone", platform: "android" });

  assert.match(id.deviceId, /^dev_[0-9a-f]{32}$/);
  assert.equal(id.deviceId, deviceIdFromPublicKey(id.publicKeyPem));
  assert.equal(id.fingerprint, fingerprintOfPublicKey(id.publicKeyPem));
  assert.match(id.fingerprint, /^SHA256:([0-9A-F]{4} ){7}[0-9A-F]{4}$/);

  assert.equal(statSync(join(dir, "identity")).mode & 0o777, 0o700);
  assert.equal(statSync(join(dir, "identity", "identity.json")).mode & 0o777, 0o600);

  const reloaded = createDeviceIdentity({ dir: join(dir, "identity") });
  assert.equal(reloaded.deviceId, id.deviceId);
  assert.equal(reloaded.fingerprint, id.fingerprint);

  const diagnostics = JSON.stringify(id.diagnostics());
  assert.ok(!diagnostics.includes("PRIVATE KEY"), "诊断不得包含私钥");
  assert.ok(!JSON.stringify(id).includes("privateKeyPem"), "身份对象不得暴露私钥字段");
  assert.ok(diagnostics.includes(id.fingerprint));
});

test("二维码配对载荷：编码往返、被信任后授权、篡改被拒", (t) => {
  const dir = makeTmpDir(t);
  const a = createDeviceIdentity({ dir: join(dir, "a"), name: "laptop" });
  const b = createDeviceIdentity({ dir: join(dir, "b"), name: "phone" });

  const offer = a.createPairingOffer({ addresses: [{ host: "192.168.1.20", port: 47823 }] });
  const payload = decodePairingPayload(offer.qr);
  assert.equal(payload.deviceId, a.deviceId);
  assert.equal(payload.fingerprint, a.fingerprint);
  assert.equal(payload.addresses[0].host, "192.168.1.20");

  const trusted = b.trustPeerFromOffer(offer.qr);
  assert.equal(trusted.ok, true);
  assert.equal(trusted.device.deviceId, a.deviceId);

  const authorized = b.authorizePeer({
    deviceId: a.deviceId,
    publicKeyPem: a.publicKeyPem,
    fingerprint: a.fingerprint,
  });
  assert.equal(authorized.ok, true);

  const unknown = b.authorizePeer({
    deviceId: `dev_${"0".repeat(32)}`,
    publicKeyPem: a.publicKeyPem,
  });
  assert.equal(unknown.ok, false);
  assert.equal(unknown.code, "UNPAIRED");

  const tampered = JSON.parse(
    Buffer.from(offer.qr.slice(offer.qr.indexOf("p=") + 2), "base64url").toString("utf8"),
  );
  tampered.deviceId = `dev_${"f".repeat(32)}`;
  const tamperedQr = `coda://pair?v=1&p=${Buffer.from(JSON.stringify(tampered), "utf8").toString("base64url")}`;
  assert.throws(() => decodePairingPayload(tamperedQr), /deviceId does not match/);
});

test("短码：8 位 Crockford、常量时间匹配、限次锁定、过期、一次性", () => {
  let tick = 1_000;
  const record = createPairingCode({ now: () => tick, ttlMs: 60_000, maxAttempts: 3, random: (n) => n % 7 });
  assert.equal(record.normalized.length, 8);
  for (const ch of record.normalized) assert.ok(PAIRING_ALPHABET.includes(ch));
  assert.match(record.display, /^[0-9A-Z]{4}-[0-9A-Z]{4}$/);
  assert.equal(record.expiresAt, new Date(tick + 60_000).toISOString());

  assert.equal(pairingCodesMatch(record, record.display), true);
  assert.equal(pairingCodesMatch(record, "!!!!"), false);

  const wrong = record.normalized === "00000000" ? "11111111" : "00000000";
  const first = verifyPairingCode(record, wrong, { at: tick });
  assert.equal(first.ok, false);
  assert.equal(first.remainingAttempts, 2);
  verifyPairingCode(record, wrong, { at: tick });
  const third = verifyPairingCode(record, wrong, { at: tick });
  assert.equal(third.locked, true);
  assert.equal(third.reason, "PAIRING_LOCKED");

  const lockedCorrect = verifyPairingCode(record, record.display, { at: tick });
  assert.equal(lockedCorrect.ok, false);
  assert.equal(lockedCorrect.reason, "PAIRING_LOCKED");

  const fresh = createPairingCode({ now: () => tick, ttlMs: 60_000 });
  assert.equal(verifyPairingCode(fresh, "not-a-code", { at: tick }).ok, false);
  assert.equal(verifyPairingCode(fresh, fresh.display, { at: tick + 60_001 }).reason, "PAIRING_EXPIRED");

  const usable = createPairingCode({ now: () => tick, ttlMs: 60_000 });
  const consumed = consumePairingCode(usable, usable.display, { at: tick });
  assert.equal(consumed.ok, true);
  assert.ok(usable.usedAt);
  const reuse = verifyPairingCode(usable, usable.display, { at: tick });
  assert.equal(reuse.ok, false);
  assert.equal(reuse.reason, "PAIRING_USED");
});

test("短码配对必须确认指纹；成功后双方互相授权；短码不出现在设备文件/诊断里", (t) => {
  const dir = makeTmpDir(t);
  const a = createDeviceIdentity({ dir: join(dir, "a") });
  const b = createDeviceIdentity({ dir: join(dir, "b") });

  const offer = a.createPairingOffer({});
  const remote = {
    deviceId: b.deviceId,
    publicKeyPem: b.publicKeyPem,
    name: b.name,
    platform: b.platform,
  };

  assert.throws(
    () => a.acceptPairing({ offerId: offer.offerId, code: offer.code, remote }),
    /fingerprint confirmation/,
  );

  const result = a.acceptPairing({
    offerId: offer.offerId,
    code: offer.code,
    remote,
    fingerprintConfirmed: true,
  });
  assert.equal(result.ok, true);
  assert.equal(result.device.deviceId, b.deviceId);
  b.trustPeerFromOffer(result.localPayload);

  assert.equal(a.authorizePeer({ deviceId: b.deviceId, publicKeyPem: b.publicKeyPem }).ok, true);
  assert.equal(b.authorizePeer({ deviceId: a.deviceId, publicKeyPem: a.publicKeyPem }).ok, true);

  const devicesRaw = readFileSync(join(a.dir, "devices.json"), "utf8");
  assert.ok(!devicesRaw.includes(offer.code.replace("-", "")));
  assert.ok(!JSON.stringify(a.diagnostics()).includes(offer.code.replace("-", "")));

  // 同一 offer 不能用第二次。
  assert.throws(
    () =>
      a.acceptPairing({
        offerId: offer.offerId,
        code: offer.code,
        remote,
        fingerprintConfirmed: true,
      }),
    /no matching pairing offer/,
  );
});

test("撤销设备：立即拒绝、可审计、不伪造远端任务停止", (t) => {
  const dir = makeTmpDir(t);
  const a = createDeviceIdentity({ dir: join(dir, "a") });
  const b = createDeviceIdentity({ dir: join(dir, "b") });
  pairNodes(a, b);

  const revoked = a.revokeDevice(b.deviceId, { reason: "lost_phone" });
  assert.equal(revoked.ok, true);
  assert.ok(revoked.device.revokedAt);
  assert.match(revoked.note, /does not fake remote task termination/);

  const denied = a.authorizePeer({ deviceId: b.deviceId, publicKeyPem: b.publicKeyPem });
  assert.equal(denied.ok, false);
  assert.equal(denied.code, "DEVICE_REVOKED");

  // 撤销是粘性的：重新配对不会静默恢复。
  const offer = a.createPairingOffer({});
  assert.throws(
    () =>
      a.acceptPairing({
        offerId: offer.offerId,
        code: offer.code,
        remote: { deviceId: b.deviceId, publicKeyPem: b.publicKeyPem },
        fingerprintConfirmed: true,
      }),
    /revoked/,
  );

  const record = a.getDevice(b.deviceId);
  assert.ok(record.revokedAt);
  assert.equal(a.listDevices().length, 1);
});
