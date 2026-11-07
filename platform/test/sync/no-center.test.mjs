/** SYNC-111 / SYNC-112 / SYNC-015：无中心拓扑、局域网通道、成果按需传输、不镜像工作区。 */
import test from "node:test";
import assert from "node:assert/strict";
import { mkdirSync, readdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

import { connectTcp, createMemoryTransportPair, createTcpServer } from "../../sync/transport.mjs";
import { connectPair, createNode, makeTmpDir, pairNodes, waitFor } from "./helpers.mjs";

test("SYNC-111：只有局域网 TCP 通道（回环、临时端口）也能完成配对后的加密同步", async (t) => {
  const dir = makeTmpDir(t);
  const a = createNode(t, { dir: join(dir, "a"), name: "desktop" });
  const b = createNode(t, { dir: join(dir, "b"), name: "laptop" });
  pairNodes(a, b);

  let server;
  try {
    server = await createTcpServer({ host: "127.0.0.1", port: 0 });
  } catch (err) {
    t.skip(`TCP loopback unavailable: ${err.message}`);
    return;
  }
  t.after(() => server.close());
  assert.ok(server.port > 0);
  assert.notEqual(server.port, 80);

  const [clientTransport, acceptedTransport] = await Promise.all([
    connectTcp({ host: "127.0.0.1", port: server.port }),
    server.accept(),
  ]);
  t.after(() => {
    clientTransport.close();
    acceptedTransport.close();
  });
  const [linkA, linkB] = await Promise.all([
    a.service.attachTransport(clientTransport),
    b.service.attachTransport(acceptedTransport),
  ]);
  assert.equal(linkA.peerId, b.identity.deviceId);
  assert.equal(linkB.peerId, a.identity.deviceId);

  const session = a.service.appendSession({ title: "lan-only" });
  a.service.appendEvent({ sessionId: session.id, kind: "message", payload: { text: "over lan" } });
  await waitFor(() => b.service.dump().events[session.id]?.length === 1, { label: "LAN-only sync" });
  assert.equal(b.service.dump().sessions.find((s) => s.id === session.id).title, "lan-only");

  // 无中心服务：双方只知道自己和已配对对端，没有任何注册/目录服务依赖。
  assert.equal(a.service.listPeers().length, 1);
  assert.equal(b.service.listPeers().length, 1);
});

test("SYNC-112：只同步成果清单；字节按需分块拉取并校验哈希；断点续传可用", async (t) => {
  const dir = makeTmpDir(t);
  const a = createNode(t, { dir: join(dir, "a"), name: "origin" });
  const b = createNode(t, { dir: join(dir, "b"), name: "reader" });
  pairNodes(a, b);
  const workspace = join(dir, "workspace");
  mkdirSync(workspace, { recursive: true });
  writeFileSync(join(workspace, "project.txt"), "workspace exists");
  const workspaceBefore = readdirSync(workspace).sort();

  await connectPair(a, b);
  const session = a.service.appendSession({ title: "report" });
  const bytes = Buffer.alloc(200_000);
  for (let i = 0; i < bytes.length; i += 1) bytes[i] = i % 251;
  const manifest = a.service.offerArtifact({
    sessionId: session.id,
    name: "report.bin",
    mime: "application/octet-stream",
    bytes,
  });
  assert.equal(manifest.transfer, "on_demand");
  assert.equal(manifest.workspaceMirrored, false);
  assert.ok(!JSON.stringify(manifest).includes("dataBase64"));

  await waitFor(() => b.service.dump().artifacts.some((artifact) => artifact.id === manifest.id), {
    label: "manifest synced",
  });
  assert.equal(b.service.artifacts.hasBytes(manifest.id), false, "清单同步不得携带字节");

  // 断点续传：先本地放入第 0 块，再只请求缺失块。
  const provided = a.service.artifacts.chunksFor(manifest.id, { have: [0] });
  const firstChunk = a.service.artifacts.chunksFor(manifest.id).chunks[0];
  b.service.artifacts.putChunk(firstChunk);
  assert.deepEqual(b.service.artifacts.missingChunks(manifest.id).slice(0, 1), [1]);

  const fetched = await b.service.fetchArtifact(manifest.id);
  assert.equal(fetched.sha256, manifest.sha256);
  assert.deepEqual(fetched.bytes, bytes);
  assert.equal(b.service.artifacts.isAvailable(manifest.id), true);
  assert.ok(fetched.manifest.chunkCount > 1);

  // 工作区没有被镜像：目录项不变，且同步模块没有写工作区文件。
  assert.deepEqual(readdirSync(workspace).sort(), workspaceBefore);
  assert.equal(b.service.artifacts.stats().workspaceMirrored, false);
  assert.ok(!readdirSync(workspace).includes("report.bin"));
  assert.ok(provided.chunks.length >= 1);
});

test("SYNC-015：没有中继/发现服务时，本地与局域网功能不受影响", async (t) => {
  const dir = makeTmpDir(t);
  const a = createNode(t, { dir: join(dir, "a"), name: "A" });
  const b = createNode(t, { dir: join(dir, "b"), name: "B" });
  pairNodes(a, b);
  assert.equal(a.service.discovery, null);
  const { left, right } = createMemoryTransportPair();
  await Promise.all([a.service.attachTransport(left), b.service.attachTransport(right)]);
  const session = a.service.appendSession({ title: "no relay" });
  a.service.appendEvent({ sessionId: session.id, kind: "message", payload: { text: "local only" } });
  await waitFor(() => b.service.dump().events[session.id]?.length === 1);
  a.service.disconnectPeer(b.identity.deviceId);
  await waitFor(() => b.service.listPeers().length === 0, { label: "peer offline" });
  // 断中继不影响本地数据。
  assert.equal(a.service.dump().events[session.id].length, 1);
});
