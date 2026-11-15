/** SYNC-103 / SYNC-014：加密帧、握手、版本协商、重放与篡改防护。 */
import test from "node:test";
import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { join } from "node:path";

import {
  MAX_FRAME_BYTES,
  createSecureSession,
  decryptFrame,
  deriveSessionKeys,
  encryptFrame,
  negotiateProtocol,
} from "../../sync/crypto.mjs";
import { createDeviceIdentity } from "../../sync/identity.mjs";
import { createMemoryTransportPair } from "../../sync/transport.mjs";
import { createNode, makeTmpDir, pairNodes, waitFor } from "./helpers.mjs";

test("ChaCha20-Poly1305 分帧：往返、重放、跳号、篡改、超大帧", () => {
  const key = randomBytes(32);
  const noncePrefix = randomBytes(4);
  const frame = encryptFrame({ key, seq: 1, plaintext: "hello", noncePrefix });
  const decrypted = decryptFrame({ key, frame, noncePrefix, lastSeq: 0 });
  assert.equal(decrypted.seq, 1);
  assert.equal(decrypted.plaintext.toString("utf8"), "hello");

  assert.throws(
    () => decryptFrame({ key, frame, noncePrefix, lastSeq: 1 }),
    (err) => err.code === "REPLAY_DETECTED",
  );

  const third = encryptFrame({ key, seq: 3, plaintext: "three", noncePrefix });
  assert.throws(
    () => decryptFrame({ key, frame: third, noncePrefix, lastSeq: 1 }),
    (err) => err.code === "SEQUENCE_GAP",
  );

  const tampered = {
    ...frame,
    c: Buffer.from("not-the-ciphertext").toString("base64"),
  };
  assert.throws(
    () => decryptFrame({ key, frame: tampered, noncePrefix, lastSeq: 0 }),
    (err) => err.code === "FRAME_AUTH_FAILED" || err.code === "BAD_FRAME",
  );

  const badNonce = { ...frame, n: randomBytes(12).toString("base64") };
  assert.throws(
    () => decryptFrame({ key, frame: badNonce, noncePrefix, lastSeq: 0 }),
    (err) => err.code === "BAD_NONCE",
  );

  assert.throws(
    () => encryptFrame({ key, seq: 1, plaintext: Buffer.alloc(MAX_FRAME_BYTES + 1), noncePrefix }),
    (err) => err.code === "FRAME_TOO_LARGE",
  );
});

test("协议版本协商：交集取最大；无交集给出可操作错误", () => {
  assert.equal(negotiateProtocol([1]), 1);
  assert.equal(negotiateProtocol([1, 2, 3]), 1);
  assert.throws(
    () => negotiateProtocol([99]),
    (err) => err.code === "VERSION_MISMATCH" && /Upgrade/.test(err.message) && err.details.action === "upgrade_or_repair",
  );
  assert.throws(
    () => negotiateProtocol(["1"]),
    (err) => err.code === "VERSION_MISMATCH",
  );
});

test("HKDF 派生：双方 transcript 相同则得到相同方向密钥", () => {
  const shared = randomBytes(32);
  const transcript = Buffer.from("transcript");
  const a = deriveSessionKeys({ sharedSecret: shared, transcript, version: 1 });
  const b = deriveSessionKeys({ sharedSecret: shared, transcript, version: 1 });
  assert.deepEqual(a.lo2hi, b.lo2hi);
  assert.deepEqual(a.hi2lo, b.hi2lo);
  assert.notDeepEqual(a.lo2hi, a.hi2lo);
  assert.equal(a.lo2hi.length, 32);
});

test("加密会话：已配对两端可互发密文；明文不出现在线路上", async (t) => {
  const dir = makeTmpDir(t);
  const a = createDeviceIdentity({ dir: join(dir, "a") });
  const b = createDeviceIdentity({ dir: join(dir, "b") });
  pairNodes(a, b);

  const { left, right } = createMemoryTransportPair();
  const wire = [];
  const recording = {
    kind: "recording",
    send(data) {
      wire.push(String(data));
      return left.send(data);
    },
    onData: (cb) => left.onData(cb),
    onClose: (cb) => left.onClose(cb),
    close: () => left.close(),
  };

  const sessionA = createSecureSession({
    identity: a,
    transport: recording,
    verifier: (peer) => a.authorizePeer(peer),
  });
  const sessionB = createSecureSession({
    identity: b,
    transport: right,
    verifier: (peer) => b.authorizePeer(peer),
  });

  const [readyA, readyB] = await Promise.all([sessionA.ready, sessionB.ready]);
  assert.equal(readyA.peer.deviceId, b.deviceId);
  assert.equal(readyB.peer.deviceId, a.deviceId);
  assert.equal(readyA.peer.fingerprint, b.fingerprint);

  const receivedByB = [];
  const receivedByA = [];
  sessionB.onEnvelope((envelope) => receivedByB.push(envelope));
  sessionA.onEnvelope((envelope) => receivedByA.push(envelope));

  sessionA.sendEnvelope({ t: "business", secretText: "sensitive-payload-123" });
  await waitFor(() => receivedByB.length === 1, { label: "B receives encrypted envelope" });
  assert.deepEqual(receivedByB[0], { t: "business", secretText: "sensitive-payload-123" });
  assert.ok(
    wire.every((line) => !line.includes("sensitive-payload-123")),
    "业务明文不得出现在密文线路上",
  );

  sessionB.sendEnvelope({ t: "ack" });
  await waitFor(() => receivedByA.length === 1, { label: "A receives reply" });
  assert.deepEqual(receivedByA[0], { t: "ack" });

  sessionA.close();
  sessionB.close();
});

test("重放已发送的密文帧会被拒绝并终止会话", async (t) => {
  const dir = makeTmpDir(t);
  const a = createDeviceIdentity({ dir: join(dir, "a") });
  const b = createDeviceIdentity({ dir: join(dir, "b") });
  pairNodes(a, b);
  const { left, right } = createMemoryTransportPair();
  const frames = [];
  const recording = {
    kind: "recording",
    send(data) {
      frames.push(String(data));
      return left.send(data);
    },
    onData: (cb) => left.onData(cb),
    onClose: (cb) => left.onClose(cb),
    close: () => left.close(),
  };
  const sessionA = createSecureSession({
    identity: a,
    transport: recording,
    verifier: (peer) => a.authorizePeer(peer),
  });
  const sessionB = createSecureSession({
    identity: b,
    transport: right,
    verifier: (peer) => b.authorizePeer(peer),
  });
  await Promise.all([sessionA.ready, sessionB.ready]);
  const errors = [];
  sessionB.onError((err) => errors.push(err));

  sessionA.sendEnvelope({ t: "once" });
  await waitFor(() => frames.some((line) => line.includes("coda-sync/frame")));

  const encryptedLine = frames.find((line) => line.includes("coda-sync/frame"));
  left.send(encryptedLine);
  await waitFor(() => errors.length > 0, { label: "replay detected" });
  assert.equal(errors[0].code, "REPLAY_DETECTED");
  sessionA.close();
  sessionB.close();
});

test("未配对设备：加密握手完成前即被拒绝", async (t) => {
  const dir = makeTmpDir(t);
  const a = createDeviceIdentity({ dir: join(dir, "a") });
  const stranger = createDeviceIdentity({ dir: join(dir, "stranger") });
  const { left, right } = createMemoryTransportPair();
  const sessionA = createSecureSession({
    identity: a,
    transport: left,
    verifier: (peer) => a.authorizePeer(peer),
  });
  const sessionB = createSecureSession({
    identity: stranger,
    transport: right,
    verifier: (peer) => stranger.authorizePeer(peer),
  });
  const results = await Promise.allSettled([sessionA.ready, sessionB.ready]);
  const rejected = results.filter((r) => r.status === "rejected");
  assert.ok(rejected.length >= 1);
  assert.ok(
    rejected.some((r) => r.reason.code === "PEER_NOT_AUTHORIZED" || r.reason.code === "TRANSPORT_CLOSED"),
  );
  sessionA.close();
  sessionB.close();
});

test("SYNC-010：撤销设备后新连接被拒绝，且不伪造远端任务已停止", async (t) => {
  const dir = makeTmpDir(t);
  const a = createNode(t, { dir: join(dir, "a"), name: "A" });
  const b = createNode(t, { dir: join(dir, "b"), name: "B" });
  pairNodes(a, b);
  const revoked = a.identity.revokeDevice(b.identity.deviceId, { reason: "lost_device" });
  assert.equal(revoked.ok, true);
  assert.match(revoked.note, /does not fake remote task termination/);

  const { left, right } = createMemoryTransportPair();
  const results = await Promise.allSettled([
    a.service.attachTransport(left),
    b.service.attachTransport(right),
  ]);
  const rejected = results.filter((result) => result.status === "rejected");
  assert.ok(rejected.length >= 1);
  assert.ok(rejected.some((result) => result.reason.code === "PEER_NOT_AUTHORIZED"));
  assert.equal(a.service.isPeerOnline(b.identity.deviceId), false);
});

test("SYNC-014：协议版本不兼容时不写本地库，错误可操作", async (t) => {
  const dir = makeTmpDir(t);
  const node = createNode(t, { dir: join(dir, "node"), name: "host" });
  const other = createDeviceIdentity({ dir: join(dir, "old") });
  const before = JSON.stringify(node.storage.dump());

  const { left, right } = createMemoryTransportPair();
  right.onData((raw) => {
    const message = JSON.parse(String(raw));
    if (message.t === "coda-sync/hello") {
      right.send(
        JSON.stringify({
          t: "coda-sync/hello",
          versions: [99],
          deviceId: other.deviceId,
          staticKey: other.publicKeyDerBase64,
          ephemeralKey: randomBytes(32).toString("base64"),
          nonce: randomBytes(8).toString("base64"),
        }),
      );
    }
  });

  await assert.rejects(
    node.service.attachTransport(left),
    (err) => err.code === "VERSION_MISMATCH" && /Upgrade|upgrade/.test(err.message),
  );
  assert.equal(JSON.stringify(node.storage.dump()), before, "握手失败不得修改本地存储");
  assert.equal(node.service.listPeers().length, 0);
});
