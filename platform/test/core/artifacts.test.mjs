/** CORE-112：内容寻址、分块下载、断点续传、完整性校验。 */
import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { existsSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";

import { ArtifactStore } from "../../core/artifacts.mjs";
import { cleanupDir, makeTempDir } from "../../fixtures/core/helpers.mjs";

const sha256 = (buf) => createHash("sha256").update(buf).digest("hex");

test("内容寻址、分块读取与断点续传元数据", () => {
  const dir = makeTempDir("coda-artifacts-");
  try {
    const store = new ArtifactStore({ dataDir: dir, chunkSize: 4 });
    const content = Buffer.from("hello world");
    const record = store.putBuffer(content, { name: "hello.txt", mime: "text/plain", sessionId: "ses_art" });

    assert.equal(record.sha256, sha256(content));
    assert.equal(record.size, 11);
    assert.equal(record.chunkCount, 3);
    assert.equal(record.available, true);
    assert.ok(existsSync(record.blobPath));

    const stat = store.stat(record.id);
    assert.equal(stat.chunkSize, 4);
    assert.equal(stat.chunkCount, 3);

    assert.equal(store.readChunk(record.id, 0).data.toString(), "hell");
    assert.equal(store.readChunk(record.id, 2).data.toString(), "rld");
    assert.equal(store.readChunk(record.id, 2).last, true);

    const reassembled = Buffer.concat(
      Array.from({ length: 3 }, (_, i) => store.readChunk(record.id, i).data),
    ).toString();
    assert.equal(reassembled, "hello world");

    assert.equal(store.readRange(record.id, { offset: 6, length: 5 }).toString(), "world");

    const resume = store.resumeMetadata(record.id, { receivedBytes: 6 });
    assert.equal(resume.nextChunk, 1);
    assert.equal(resume.chunkOffset, 2);
    assert.equal(resume.remainingBytes, 5);
    assert.equal(resume.complete, false);
    assert.equal(store.resumeMetadata(record.id, { receivedBytes: 11 }).complete, true);

    const batch = store.readChunks(record.id, { fromChunk: 1, maxChunks: 2 });
    assert.deepEqual(batch.chunks.map((c) => c.data.toString()), ["o wo", "rld"]);
    assert.equal(batch.complete, true);

    assert.equal(store.verify(record.id).ok, true);
  } finally {
    cleanupDir(dir);
  }
});

test("相同内容只存一份 blob，但可保留多条元数据", () => {
  const dir = makeTempDir("coda-artifacts-");
  try {
    const store = new ArtifactStore({ dataDir: dir, chunkSize: 4 });
    const content = Buffer.from("dedupe me");
    const first = store.putBuffer(content, { name: "a.txt" });
    const second = store.putBuffer(content, { name: "b.txt" });
    assert.notEqual(first.id, second.id);
    assert.equal(first.sha256, second.sha256);
    assert.equal(first.blobPath, second.blobPath);
    assert.equal(store.list().length, 2);
  } finally {
    cleanupDir(dir);
  }
});

test("文件流式写入与按需复制（不镜像目录）", async () => {
  const dir = makeTempDir("coda-artifacts-");
  try {
    const store = new ArtifactStore({ dataDir: dir, chunkSize: 8 });
    const source = path.join(dir, "source.bin");
    const payload = Buffer.from("streamed content for artifacts");
    writeFileSync(source, payload);

    const record = await store.putFile(source, { name: "source.bin", sessionId: "ses_stream" });
    assert.equal(record.size, payload.length);
    assert.equal(record.sha256, sha256(payload));
    assert.equal(store.verify(record.id).ok, true);

    const destination = path.join(dir, "copy.bin");
    store.copyOut(record.id, destination);
    assert.deepEqual(readFileSync(destination), payload);

    // 只复制被请求的成果；工作区其它文件不会出现在 artifacts 目录里。
    assert.equal(store.list({ sessionId: "ses_stream" }).length, 1);
  } finally {
    cleanupDir(dir);
  }
});

test("blob 缺失或内容损坏时不伪造成果", () => {
  const dir = makeTempDir("coda-artifacts-");
  try {
    const store = new ArtifactStore({ dataDir: dir, chunkSize: 4 });
    const record = store.putBuffer(Buffer.from("tamper target"), { name: "t.txt" });

    writeFileSync(record.blobPath, "tampered");
    const verified = store.verify(record.id);
    assert.equal(verified.ok, false);
    assert.notEqual(verified.actual, verified.expected);

    rmSync(record.blobPath, { force: true });
    assert.equal(store.stat(record.id).available, false);
    assert.throws(() => store.readChunk(record.id, 0), /unavailable/);
    assert.throws(() => store.copyOut(record.id, path.join(dir, "x")), /unavailable/);
    assert.equal(store.verify(record.id).available, false);
  } finally {
    cleanupDir(dir);
  }
});
