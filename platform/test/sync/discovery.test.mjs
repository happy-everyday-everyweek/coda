/** SYNC-102 / SYNC-111：局域网发现、手动地址、路由与退避；不依赖中心服务。 */
import test from "node:test";
import assert from "node:assert/strict";
import { join } from "node:path";

import {
  DISCOVERY_BEACON_PORT,
  beaconHash,
  chooseRoute,
  classifyAddress,
  createBeacon,
  createDiscovery,
  createReconnectTracker,
  createRetryPolicy,
  createUdpBeaconTransport,
  decodeBeacon,
  encodeBeacon,
} from "../../sync/discovery.mjs";
import { createDeviceIdentity } from "../../sync/identity.mjs";
import { makeTmpDir, waitFor } from "./helpers.mjs";

function createFakeTransport() {
  let handler = null;
  const sent = [];
  return {
    kind: "fake",
    sent,
    onMessage(fn) {
      handler = fn;
      return () => {
        handler = null;
      };
    },
    send(buffer) {
      sent.push(buffer);
    },
    emit(buffer, info) {
      handler?.(buffer, info);
    },
    close() {},
  };
}

test("广播包：编码/解码往返，拒绝过期、错误 magic 与非法端口", (t) => {
  const dir = makeTmpDir(t);
  const identity = createDeviceIdentity({ dir: join(dir, "id"), name: "laptop" });
  const now = 1_700_000_000_000;
  const beacon = createBeacon({ identity, port: 47823, addresses: ["192.168.1.7"], now: () => now });
  const buffer = encodeBeacon(beacon);
  assert.ok(buffer.length <= 2048);

  const decoded = decodeBeacon(buffer, { at: now });
  assert.equal(decoded.ok, true);
  assert.equal(decoded.beacon.deviceId, identity.deviceId);
  assert.equal(decoded.beacon.port, 47823);
  assert.equal(beaconHash(buffer).length, 16);

  assert.equal(decodeBeacon(buffer, { at: now + 10 * 60_000 }).reason, "STALE_BEACON");
  assert.equal(decodeBeacon(Buffer.from("not json"), { at: now }).reason, "MALFORMED_BEACON");
  assert.equal(
    decodeBeacon(Buffer.from(JSON.stringify({ ...beacon, m: "other" })), { at: now }).reason,
    "WRONG_MAGIC",
  );
  assert.equal(
    decodeBeacon(Buffer.from(JSON.stringify({ ...beacon, port: 0 })), { at: now }).reason,
    "INVALID_PORT",
  );
  assert.equal(
    decodeBeacon(Buffer.from(JSON.stringify({ ...beacon, v: 2 })), { at: now }).reason,
    "UNSUPPORTED_VERSION",
  );
});

test("路由选择：loopback 优先、私网 LAN、公网标记 wan", () => {
  assert.equal(classifyAddress("127.0.0.1"), "loopback");
  assert.equal(classifyAddress("localhost"), "loopback");
  assert.equal(classifyAddress("::1"), "loopback");
  assert.equal(classifyAddress("192.168.1.5"), "lan");
  assert.equal(classifyAddress("10.0.2.2"), "lan");
  assert.equal(classifyAddress("172.20.1.1"), "lan");
  assert.equal(classifyAddress("fe80::1"), "lan");
  assert.equal(classifyAddress("8.8.8.8"), "wan");
  assert.equal(classifyAddress(""), "invalid");

  const loopback = chooseRoute({ host: "127.0.0.1", port: 1 });
  assert.equal(loopback.kind, "loopback");
  assert.equal(loopback.priority, 0);
  const lan = chooseRoute({ host: "192.168.1.5", port: 2 });
  assert.equal(lan.kind, "lan");
  const wan = chooseRoute({ host: "203.0.113.9", port: 3 });
  assert.equal(wan.kind, "wan");
  const localMatch = chooseRoute({ host: "192.168.1.5", port: 4 }, { localAddresses: ["192.168.1.5"] });
  assert.equal(localMatch.kind, "loopback");
  assert.throws(() => chooseRoute({ host: "127.0.0.1", port: 0 }), /invalid candidate port/);
});

test("重连退避：指数增长、上限、抖动可复现", () => {
  const policy = createRetryPolicy({ baseMs: 100, maxMs: 800, factor: 2, jitter: 0, random: () => 0.5 });
  assert.equal(policy.delay(1), 100);
  assert.equal(policy.delay(2), 200);
  assert.equal(policy.delay(3), 400);
  assert.equal(policy.delay(4), 800);
  assert.equal(policy.delay(9), 800);

  const jittered = createRetryPolicy({ baseMs: 100, maxMs: 1000, factor: 2, jitter: 0.5, random: () => 0.5 });
  assert.equal(jittered.delay(1), 100);

  let now = 0;
  const tracker = createReconnectTracker({ policy, clock: () => now });
  assert.equal(tracker.canRetry("peer"), true);
  const first = tracker.failure("peer");
  assert.equal(first.attempts, 1);
  assert.equal(tracker.canRetry("peer"), false);
  now = first.nextAt;
  assert.equal(tracker.canRetry("peer"), true);
  tracker.success("peer");
  assert.equal(tracker.state("peer").attempts, 0);
});

test("发现器：手动地址 + 广播候选去重、忽略自己、过滤未授权设备", (t) => {
  const dir = makeTmpDir(t);
  const identity = createDeviceIdentity({ dir: join(dir, "id") });
  const peerIdentity = createDeviceIdentity({ dir: join(dir, "peer") });
  const transport = createFakeTransport();
  const authorized = new Set();
  const discovery = createDiscovery({
    identity,
    transport,
    listenPort: 47823,
    isAuthorized: (deviceId) => authorized.has(deviceId),
  });
  discovery.start({ announceIntervalMs: 0 });

  assert.equal(transport.sent.length, 1);
  const announced = decodeBeacon(transport.sent[0]);
  assert.equal(announced.ok, true);
  assert.equal(announced.beacon.deviceId, identity.deviceId);

  const manual = discovery.addManualAddress({ host: "192.168.1.30", port: 4711 });
  assert.equal(manual.route.kind, "lan");
  discovery.addManualAddress({ host: "192.168.1.30", port: 4711 });
  assert.equal(discovery.listManualAddresses().length, 1);
  assert.equal(discovery.addManualAddress({ host: "127.0.0.1", port: 1, deviceId: identity.deviceId }), null);

  const now = Date.now();
  const selfBeacon = createBeacon({ identity, port: 47823, now: () => now });
  assert.equal(discovery.handleBeacon(encodeBeacon(selfBeacon), { fromHost: "127.0.0.1" }), null);

  const peerBeacon = createBeacon({ identity: peerIdentity, port: 47823, now: () => now });
  const candidate = discovery.handleBeacon(encodeBeacon(peerBeacon), { fromHost: "192.168.1.30" });
  assert.ok(candidate);
  assert.equal(candidate.deviceId, peerIdentity.deviceId);
  assert.equal(candidate.route.kind, "lan");
  assert.equal(candidate.authorized, false);
  // 手动地址与发现到的设备是两个候选（地址不同、设备 id 不同）。
  assert.equal(discovery.listCandidates().length, 2);
  assert.equal(discovery.listCandidates({ includeUnauthorized: false }).length, 1);

  authorized.add(peerIdentity.deviceId);
  assert.equal(discovery.listCandidates({ includeUnauthorized: false }).length, 2);
  assert.equal(discovery.diagnostics().discovered, 1);

  discovery.stop();
  assert.equal(discovery.started, false);
});

test("真实 UDP 传输：绑定临时端口并单播收发 beacon（不绑固定端口、不联网）", async (t) => {
  const dir = makeTmpDir(t);
  const identity = createDeviceIdentity({ dir: join(dir, "id") });
  const receiver = createUdpBeaconTransport();
  const sender = createUdpBeaconTransport();
  try {
    await receiver.bind();
    await sender.bind();
  } catch (err) {
    receiver.close();
    sender.close();
    t.skip(`UDP unavailable in this environment: ${err.message}`);
    return;
  }
  t.after(() => {
    receiver.close();
    sender.close();
  });
  assert.ok(receiver.port > 0);
  assert.ok(sender.port > 0);
  assert.notEqual(receiver.port, DISCOVERY_BEACON_PORT);

  const got = [];
  receiver.onMessage((message, info) => got.push({ message, info }));
  const beacon = createBeacon({ identity, port: 47823 });
  sender.sendTo(encodeBeacon(beacon), { host: "127.0.0.1", port: receiver.port });
  await waitFor(() => got.length > 0, { timeoutMs: 3000, label: "UDP beacon" });
  const decoded = decodeBeacon(got[0].message);
  assert.equal(decoded.ok, true);
  assert.equal(decoded.beacon.deviceId, identity.deviceId);
});
