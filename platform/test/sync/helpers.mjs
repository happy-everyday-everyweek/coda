/** 测试辅助：临时目录、两端节点、配对与轮询等待（不是测试文件）。 */
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { createDeviceIdentity } from "../../sync/identity.mjs";
import { createFileStorage, createMemoryStorage } from "../../sync/storage.mjs";
import { createSyncService } from "../../sync/sync-service.mjs";
import { createMemoryTransportPair } from "../../sync/transport.mjs";

export async function waitFor(predicate, { timeoutMs = 4000, intervalMs = 5, label = "condition" } = {}) {
  const started = Date.now();
  for (;;) {
    let value;
    try {
      value = await predicate();
    } catch (err) {
      if (Date.now() - started > timeoutMs) throw err;
      value = false;
    }
    if (value) return value;
    if (Date.now() - started > timeoutMs) {
      throw new Error(`timed out after ${timeoutMs}ms waiting for ${label}`);
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
}

export function makeTmpDir(t, prefix = "coda-sync-test-") {
  const dir = mkdtempSync(join(tmpdir(), prefix));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  return dir;
}

/**
 * 创建一个节点：真实身份文件 + 内存/文件存储 + 同步服务。
 */
export function createNode(t, { dir, name, platform = "test", storage = null, commandHandler = null, eventWindow, paId = null } = {}) {
  const identity = createDeviceIdentity({ dir: join(dir, "identity"), name, platform });
  const store = storage ?? createMemoryStorage();
  const service = createSyncService({ storage: store, identity, commandHandler, eventWindow, paId });
  t.after(() => {
    try {
      service.close();
    } catch {
      /* ignore */
    }
    if (!storage) store.close();
  });
  return { identity, storage: store, service, dir };
}

export function createFileNode(t, { dir, name, platform = "test", ...rest } = {}) {
  const store = createFileStorage({ path: join(dir, "sync-store.json") });
  return createNode(t, { dir, name, platform, storage: store, ...rest });
}

/** 双向配对：A 生成 offer，B 扫码信任 A，A 用短码确认 B。接受 node 或 identity。 */
export function pairNodes(a, b) {
  const identityA = a.identity ?? a;
  const identityB = b.identity ?? b;
  const offer = identityA.createPairingOffer({
    addresses: [{ host: "127.0.0.1", port: 0 }],
  });
  const remote = {
    deviceId: identityB.deviceId,
    publicKeyPem: identityB.publicKeyPem,
    name: identityB.name,
    platform: identityB.platform,
  };
  const accepted = identityA.acceptPairing({
    offerId: offer.offerId,
    code: offer.code,
    remote,
    fingerprintConfirmed: true,
  });
  const trusted = identityB.trustPeerFromOffer(offer.qr);
  return { offer, accepted, trusted };
}

/** 用内存通道连接两端；返回可用于断连的句柄。 */
export async function connectPair(a, b) {
  const { left, right } = createMemoryTransportPair();
  const [linkA, linkB] = await Promise.all([
    a.service.attachTransport(left),
    b.service.attachTransport(right),
  ]);
  return { left, right, linkA, linkB };
}

export async function disconnectPair(a, b, reason = "test_partition") {
  a.service.disconnectPeer(b.identity.deviceId, { reason });
  b.service.disconnectPeer(a.identity.deviceId, { reason });
}
