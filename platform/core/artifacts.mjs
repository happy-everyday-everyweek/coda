/**
 * CORE-112：成果（Artifact）服务。
 *
 * - 内容寻址：sha256 决定 blob 路径，重复内容只存一份。
 * - 分块下载与断点续传：chunkSize 固定，客户端按块拉取并能从任意 chunk 续传。
 * - 只在被请求时读取/复制单个成果，不镜像整个工作区（ART-001）。
 * - 源 blob 缺失时返回 `available:false`，绝不生成空文件冒充成果（ART-003）。
 */
import { createHash } from "node:crypto";
import {
  closeSync,
  existsSync,
  mkdirSync,
  openSync,
  readFileSync,
  readSync,
  renameSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { createReadStream, createWriteStream } from "node:fs";
import path from "node:path";
import { pipeline } from "node:stream/promises";
import { Transform } from "node:stream";

import { makeId } from "../contracts/ids.mjs";
import { createArtifact } from "./domain.mjs";
import { NotFoundError, ValidationError } from "./errors.mjs";

export const DEFAULT_CHUNK_SIZE = 64 * 1024;

function sha256Hex(buffer) {
  return createHash("sha256").update(buffer).digest("hex");
}

export class ArtifactStore {
  #index = null;

  constructor({ dataDir, chunkSize = DEFAULT_CHUNK_SIZE, clock = () => new Date(), journal = null } = {}) {
    if (!dataDir) throw new ValidationError("ArtifactStore requires dataDir");
    if (!Number.isInteger(chunkSize) || chunkSize < 1) throw new ValidationError("chunkSize must be a positive integer");
    this.dataDir = dataDir;
    this.blobDir = path.join(dataDir, "blobs");
    this.indexPath = path.join(dataDir, "index.json");
    this.tmpDir = path.join(dataDir, "tmp");
    this.chunkSize = chunkSize;
    this.clock = clock;
    this.journal = journal;
    mkdirSync(this.blobDir, { recursive: true });
    mkdirSync(this.tmpDir, { recursive: true });
  }

  nowIso() {
    const d = this.clock();
    return (d instanceof Date ? d : new Date(d)).toISOString();
  }

  #load() {
    if (this.#index) return this.#index;
    if (!existsSync(this.indexPath)) {
      this.#index = { version: 1, artifacts: {} };
      return this.#index;
    }
    try {
      const parsed = JSON.parse(readFileSync(this.indexPath, "utf8"));
      this.#index = {
        version: 1,
        artifacts: parsed && typeof parsed.artifacts === "object" && parsed.artifacts ? parsed.artifacts : {},
      };
    } catch {
      // 索引损坏：以空索引继续，但受影响的成果会因 blob 路径仍可寻址而不至于伪造内容。
      this.#index = { version: 1, artifacts: {} };
    }
    return this.#index;
  }

  #save() {
    const index = this.#load();
    const tmp = path.join(this.tmpDir, `index-${makeId("evt")}.json`);
    writeFileSync(tmp, JSON.stringify(index, null, 2), { mode: 0o600 });
    renameSync(tmp, this.indexPath);
  }

  blobPath(sha256) {
    return path.join(this.blobDir, sha256.slice(0, 2), sha256);
  }

  #writeBlob(buffer, sha256) {
    const target = this.blobPath(sha256);
    if (existsSync(target)) return false;
    mkdirSync(path.dirname(target), { recursive: true });
    const tmp = path.join(this.tmpDir, `blob-${makeId("evt")}`);
    writeFileSync(tmp, buffer, { mode: 0o600 });
    renameSync(tmp, target);
    return true;
  }

  #record({ id, sha256, size, mime, name, sessionId, nodeId, metadata }) {
    const record = {
      ...createArtifact({
        id,
        sessionId: sessionId ?? null,
        nodeId: nodeId ?? null,
        sha256,
        size,
        mime,
        name,
        metadata: metadata ?? {},
        createdAt: this.nowIso(),
      }),
      chunkSize: this.chunkSize,
      chunkCount: Math.max(1, Math.ceil(size / this.chunkSize)),
      blobPath: this.blobPath(sha256),
      available: existsSync(this.blobPath(sha256)),
    };
    const index = this.#load();
    index.artifacts[id] = record;
    this.#save();
    if (this.journal) {
      this.journal.upsertArtifact(record);
    }
    return record;
  }

  /** 从内存写入成果。 */
  putBuffer(buffer, { sessionId = null, nodeId = null, name = "artifact", mime = "application/octet-stream", metadata = {}, id } = {}) {
    if (!Buffer.isBuffer(buffer)) throw new ValidationError("putBuffer requires a Buffer");
    const sha256 = sha256Hex(buffer);
    this.#writeBlob(buffer, sha256);
    return this.#record({
      id: id ?? makeId("art"),
      sha256,
      size: buffer.length,
      mime,
      name,
      sessionId,
      nodeId,
      metadata,
    });
  }

  /**
   * 从文件写入成果（流式哈希，不把大文件读进内存）。
   */
  async putFile(filePath, { sessionId = null, nodeId = null, name = null, mime = "application/octet-stream", metadata = {}, id } = {}) {
    if (typeof filePath !== "string" || filePath.length === 0) throw new ValidationError("filePath is required");
    if (!existsSync(filePath)) throw new NotFoundError(`file not found: ${filePath}`, { filePath });
    const stat = statSync(filePath);
    if (!stat.isFile()) throw new ValidationError(`not a regular file: ${filePath}`, { filePath });

    const hash = createHash("sha256");
    let size = 0;
    const hasher = new Transform({
      transform(chunk, _enc, cb) {
        hash.update(chunk);
        size += chunk.length;
        cb(null, chunk);
      },
    });
    const tmp = path.join(this.tmpDir, `upload-${makeId("evt")}`);
    await pipeline(createReadStream(filePath), hasher, createWriteStream(tmp, { mode: 0o600 }));
    const sha256 = hash.digest("hex");

    const target = this.blobPath(sha256);
    if (existsSync(target)) {
      rmSync(tmp, { force: true });
    } else {
      mkdirSync(path.dirname(target), { recursive: true });
      renameSync(tmp, target);
    }

    return this.#record({
      id: id ?? makeId("art"),
      sha256,
      size,
      mime,
      name: name ?? path.basename(filePath),
      sessionId,
      nodeId,
      metadata,
    });
  }

  get(id) {
    const record = this.#load().artifacts[id];
    if (!record) return null;
    return { ...record, available: existsSync(record.blobPath) };
  }

  /** 元数据 + 分块信息；源 blob 缺失时 available=false。 */
  stat(id) {
    const record = this.get(id);
    if (!record) throw new NotFoundError(`unknown artifact: ${id}`, { artifactId: id });
    return {
      ...record,
      available: record.available,
      chunkSize: this.chunkSize,
      chunkCount: Math.max(1, Math.ceil(record.size / this.chunkSize)),
      downloaded: record.available,
    };
  }

  list({ sessionId = null } = {}) {
    const all = Object.values(this.#load().artifacts);
    return all
      .filter((r) => !sessionId || r.sessionId === sessionId)
      .map((r) => ({ ...r, available: existsSync(r.blobPath) }))
      .sort((a, b) => (a.createdAt < b.createdAt ? -1 : 1));
  }

  #readBlob(id, offset, length) {
    const record = this.get(id);
    if (!record) throw new NotFoundError(`unknown artifact: ${id}`, { artifactId: id });
    if (!existsSync(record.blobPath)) {
      // 不返回空内容：调用方必须把 available=false 展示为「源节点不可用」。
      throw new NotFoundError(`artifact blob unavailable: ${id}`, { artifactId: id, available: false });
    }
    const remaining = Math.max(0, record.size - offset);
    const toRead = Math.min(length, remaining);
    if (toRead <= 0) return Buffer.alloc(0);
    const fd = openSync(record.blobPath, "r");
    try {
      const buf = Buffer.alloc(toRead);
      const read = readSync(fd, buf, 0, toRead, offset);
      return read === toRead ? buf : buf.subarray(0, read);
    } finally {
      closeSync(fd);
    }
  }

  /** 读取单块。 */
  readChunk(id, index) {
    const record = this.stat(id);
    if (!Number.isInteger(index) || index < 0 || index >= record.chunkCount) {
      throw new ValidationError(`chunk index out of range: ${index}`, { chunkCount: record.chunkCount });
    }
    const offset = index * this.chunkSize;
    const length = Math.min(this.chunkSize, record.size - offset);
    return {
      artifactId: id,
      index,
      offset,
      length,
      last: index === record.chunkCount - 1,
      chunkCount: record.chunkCount,
      sha256: record.sha256,
      data: this.#readBlob(id, offset, length),
    };
  }

  /** 批量读取（下载用）；limit 防止一次拉太多。 */
  readChunks(id, { fromChunk = 0, maxChunks = 16 } = {}) {
    const record = this.stat(id);
    if (!Number.isInteger(fromChunk) || fromChunk < 0) throw new ValidationError("fromChunk must be a non-negative integer");
    if (!Number.isInteger(maxChunks) || maxChunks < 1) throw new ValidationError("maxChunks must be a positive integer");
    const end = Math.min(record.chunkCount, fromChunk + maxChunks);
    const chunks = [];
    for (let i = fromChunk; i < end; i += 1) chunks.push(this.readChunk(id, i));
    return { artifactId: id, chunks, nextChunk: end, complete: end >= record.chunkCount, chunkCount: record.chunkCount };
  }

  /** 任意区间读取（支持 Range 语义）。 */
  readRange(id, { offset = 0, length = this.chunkSize } = {}) {
    const record = this.stat(id);
    if (!Number.isInteger(offset) || offset < 0) throw new ValidationError("offset must be a non-negative integer");
    if (!Number.isInteger(length) || length < 1) throw new ValidationError("length must be a positive integer");
    if (offset >= record.size && record.size > 0) {
      throw new ValidationError("offset beyond end of artifact", { size: record.size });
    }
    return this.#readBlob(id, offset, length);
  }

  /**
   * 断点续传元数据：客户端报 receivedBytes，服务端告诉下一块与剩余量。
   */
  resumeMetadata(id, { receivedBytes = 0 } = {}) {
    const record = this.stat(id);
    if (!Number.isInteger(receivedBytes) || receivedBytes < 0) {
      throw new ValidationError("receivedBytes must be a non-negative integer");
    }
    const clamped = Math.min(receivedBytes, record.size);
    return {
      artifactId: id,
      sha256: record.sha256,
      size: record.size,
      chunkSize: this.chunkSize,
      chunkCount: record.chunkCount,
      receivedBytes: clamped,
      nextChunk: Math.min(record.chunkCount, Math.floor(clamped / this.chunkSize)),
      chunkOffset: clamped % this.chunkSize,
      remainingBytes: record.size - clamped,
      complete: clamped >= record.size,
      available: record.available,
    };
  }

  /** 重新计算哈希，校验内容未损坏。 */
  verify(id) {
    const record = this.get(id);
    if (!record) throw new NotFoundError(`unknown artifact: ${id}`, { artifactId: id });
    if (!existsSync(record.blobPath)) {
      return { artifactId: id, ok: false, available: false, reason: "blob-missing", expected: record.sha256 };
    }
    const actual = sha256Hex(readFileSync(record.blobPath));
    return {
      artifactId: id,
      ok: actual === record.sha256,
      available: true,
      expected: record.sha256,
      actual,
      size: record.size,
    };
  }

  /** 保存到客户端指定路径（按需复制，不镜像工作区）。 */
  copyOut(id, destination) {
    const record = this.get(id);
    if (!record) throw new NotFoundError(`unknown artifact: ${id}`, { artifactId: id });
    if (!record.available) throw new NotFoundError(`artifact blob unavailable: ${id}`, { artifactId: id, available: false });
    mkdirSync(path.dirname(destination), { recursive: true });
    writeFileSync(destination, readFileSync(record.blobPath), { mode: 0o600 });
    return { artifactId: id, destination, size: record.size };
  }

  remove(id) {
    const record = this.get(id);
    if (!record) return false;
    const index = this.#load();
    delete index.artifacts[id];
    this.#save();
    // 仅当没有其它成果引用同一 blob 时才删除内容。
    const stillUsed = Object.values(index.artifacts).some((r) => r.sha256 === record.sha256);
    if (!stillUsed && existsSync(record.blobPath)) rmSync(record.blobPath, { force: true });
    return true;
  }
}

export function createArtifactStore(options) {
  return new ArtifactStore(options);
}
