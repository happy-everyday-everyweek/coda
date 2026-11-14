/**
 * 成果/附件的按需传输（SYNC-112 / PERF-005）。
 *
 * - 同步只复制「清单」（哈希、大小、类型、所属节点），绝不传输工作区文件；
 * - 只有用户明确请求某个成果时才按块拉取，可断点续传（缺失块集合）；
 * - 完成后校验总大小与 SHA-256，再原子转成完整 blob；
 * - 不 import fs：本模块无法访问工作区，也不应访问。
 */
import { createHash, randomBytes } from "node:crypto";

import { makeId } from "../contracts/ids.mjs";

export const DEFAULT_CHUNK_SIZE = 64 * 1024;
export const DEFAULT_MAX_ARTIFACT_BYTES = 512 * 1024 * 1024;

export class ArtifactError extends Error {
  constructor(code, message, details = {}) {
    super(message);
    this.name = "ArtifactError";
    this.code = code;
    this.details = details;
  }
}

function sha256(buffer) {
  return createHash("sha256").update(buffer).digest("hex");
}

function clone(value) {
  return value === undefined ? undefined : structuredClone(value);
}

function chunkKey(artifactId, index) {
  return `${artifactId}:chunk:${index}`;
}

export function createArtifactTransfer({
  storage,
  deviceId,
  clock = Date.now,
  chunkSize = DEFAULT_CHUNK_SIZE,
  maxBytes = DEFAULT_MAX_ARTIFACT_BYTES,
} = {}) {
  if (!storage) throw new ArtifactError("STORAGE_REQUIRED", "createArtifactTransfer requires {storage}");
  if (!deviceId) throw new ArtifactError("DEVICE_REQUIRED", "createArtifactTransfer requires {deviceId}");
  if (!Number.isInteger(chunkSize) || chunkSize < 1024) {
    throw new ArtifactError("INVALID_CHUNK_SIZE", "chunkSize must be an integer >= 1024");
  }
  const iso = () => new Date(clock()).toISOString();

  const manifest = (id) => {
    const record = storage.getArtifact(id);
    if (!record) throw new ArtifactError("UNKNOWN_ARTIFACT", `unknown artifact ${id}`);
    return record;
  };

  const receivedKey = (id) => `artifact.received.${id}`;

  const receivedChunks = (id) => {
    const record = manifest(id);
    const indices = [];
    for (let index = 0; index < record.chunkCount; index += 1) {
      if (storage.hasBlob(chunkKey(id, index))) indices.push(index);
    }
    return indices;
  };

  return {
    /**
     * 发布方登记成果：只持久化清单与本地字节；清单会随同步操作复制到对端。
     */
    offer({ sessionId, name, mime = "application/octet-stream", bytes, artifactId = null } = {}) {
      if (!sessionId) throw new ArtifactError("SESSION_REQUIRED", "artifact requires sessionId");
      if (!name) throw new ArtifactError("NAME_REQUIRED", "artifact requires name");
      if (!Buffer.isBuffer(bytes)) throw new ArtifactError("INVALID_BYTES", "artifact bytes must be a Buffer");
      if (bytes.length > maxBytes) {
        throw new ArtifactError("ARTIFACT_TOO_LARGE", `artifact exceeds ${maxBytes} bytes`);
      }
      const id = artifactId ?? makeId("art");
      const record = {
        id,
        sessionId,
        name: String(name).slice(0, 255),
        mime: String(mime),
        size: bytes.length,
        sha256: sha256(bytes),
        chunkSize,
        chunkCount: Math.max(1, Math.ceil(bytes.length / chunkSize)),
        originDeviceId: deviceId,
        createdAt: iso(),
        transfer: "on_demand",
        workspaceMirrored: false,
      };
      storage.putBlob(id, bytes);
      storage.putArtifact(record);
      return clone(record);
    },

    /** 收到远端清单：只保存元数据，不含字节。 */
    applyManifest(remote) {
      if (!remote?.id) throw new ArtifactError("INVALID_MANIFEST", "artifact manifest requires id");
      if (!Number.isInteger(remote.size) || remote.size < 0 || remote.size > maxBytes) {
        throw new ArtifactError("INVALID_MANIFEST", "artifact size out of range");
      }
      if (!Number.isInteger(remote.chunkCount) || remote.chunkCount < 1) {
        throw new ArtifactError("INVALID_MANIFEST", "artifact chunkCount invalid");
      }
      const existing = storage.getArtifact(remote.id);
      if (existing && existing.sha256 !== remote.sha256) {
        throw new ArtifactError("MANIFEST_CONFLICT", "artifact manifest hash conflict");
      }
      const record = {
        ...clone(remote),
        transfer: "on_demand",
        workspaceMirrored: false,
      };
      storage.putArtifact(record);
      return clone(record);
    },

    manifest(id) {
      return clone(manifest(id));
    },

    list({ sessionId = null } = {}) {
      return storage
        .listArtifacts({ sessionId })
        .filter((record) => record.workspaceMirrored !== true)
        .map(clone);
    },

    hasBytes(id) {
      return storage.hasBlob(id);
    },

    read(id) {
      const buffer = storage.getBlob(id);
      return buffer ? Buffer.from(buffer) : null;
    },

    /** 缺失块（断点续传的核心状态）。 */
    missingChunks(id) {
      const record = manifest(id);
      const have = new Set(receivedChunks(id));
      const missing = [];
      for (let index = 0; index < record.chunkCount; index += 1) {
        if (!have.has(index)) missing.push(index);
      }
      return missing;
    },

    missingRanges(id) {
      const missing = this.missingChunks(id);
      const ranges = [];
      for (const index of missing) {
        const last = ranges[ranges.length - 1];
        if (last && last.to + 1 === index) last.to = index;
        else ranges.push({ from: index, to: index });
      }
      return ranges;
    },

    /** 提供方按请求生成块（可只发缺失块）。 */
    chunksFor(id, { have = [] } = {}) {
      const record = manifest(id);
      const bytes = storage.getBlob(id);
      if (!bytes) throw new ArtifactError("NO_BYTES", "artifact bytes are not available on this node");
      const haveSet = new Set(have);
      const chunks = [];
      for (let index = 0; index < record.chunkCount; index += 1) {
        if (haveSet.has(index)) continue;
        const offset = index * record.chunkSize;
        const slice = bytes.subarray(offset, Math.min(offset + record.chunkSize, bytes.length));
        chunks.push({
          artifactId: id,
          index,
          offset,
          length: slice.length,
          dataBase64: slice.toString("base64"),
          sha256: sha256(slice),
        });
      }
      return { manifest: clone(record), chunks };
    },

    /** 接收一块；签名/长度/索引不合法直接拒绝。 */
    putChunk({ artifactId, index, dataBase64, sha256: expectedSha = null } = {}) {
      const record = manifest(artifactId);
      if (!Number.isInteger(index) || index < 0 || index >= record.chunkCount) {
        throw new ArtifactError("INVALID_CHUNK_INDEX", `chunk index out of range: ${index}`);
      }
      const buffer = Buffer.from(dataBase64 ?? "", "base64");
      const expectedLength = Math.min(record.chunkSize, Math.max(0, record.size - index * record.chunkSize));
      if (buffer.length !== expectedLength) {
        throw new ArtifactError("CHUNK_SIZE_MISMATCH", `chunk ${index} length ${buffer.length} != ${expectedLength}`);
      }
      const actualSha = sha256(buffer);
      if (expectedSha && expectedSha !== actualSha) {
        throw new ArtifactError("CHUNK_HASH_MISMATCH", `chunk ${index} hash mismatch`);
      }
      storage.putBlob(chunkKey(artifactId, index), buffer);
      const missing = this.missingChunks(artifactId);
      return { ok: true, index, received: record.chunkCount - missing.length, missing, complete: missing.length === 0 };
    },

    /** 所有块到齐后校验并原子转成完整 blob。 */
    finalize(artifactId) {
      const record = manifest(artifactId);
      const missing = this.missingChunks(artifactId);
      if (missing.length > 0) {
        return { ok: false, code: "INCOMPLETE", missing };
      }
      const parts = [];
      for (let index = 0; index < record.chunkCount; index += 1) {
        parts.push(storage.getBlob(chunkKey(artifactId, index)));
      }
      const buffer = Buffer.concat(parts);
      if (buffer.length !== record.size) {
        throw new ArtifactError("SIZE_MISMATCH", `artifact size ${buffer.length} != ${record.size}`);
      }
      const actual = sha256(buffer);
      if (actual !== record.sha256) {
        throw new ArtifactError("HASH_MISMATCH", "artifact hash mismatch after transfer", {
          expected: record.sha256,
          actual,
        });
      }
      storage.putBlob(artifactId, buffer);
      for (let index = 0; index < record.chunkCount; index += 1) {
        storage.deleteBlob(chunkKey(artifactId, index));
      }
      storage.kvSet(receivedKey(artifactId), { sha256: actual, size: buffer.length, at: iso() });
      return { ok: true, artifactId, sha256: actual, size: buffer.length };
    },

    /** 是否已经在本节点完整可用（不是「清单存在」）。 */
    isAvailable(id) {
      if (!storage.hasBlob(id)) return false;
      const record = storage.getArtifact(id);
      return !!record;
    },

    /** 清理接收缓存（保留清单）。 */
    evict(id) {
      const record = manifest(id);
      let removed = storage.deleteBlob(id) ? 1 : 0;
      for (let index = 0; index < record.chunkCount; index += 1) {
        if (storage.deleteBlob(chunkKey(id, index))) removed += 1;
      }
      storage.kvDelete(receivedKey(id));
      return { ok: true, removed };
    },

    stats() {
      const manifests = storage.listArtifacts({});
      const available = manifests.filter((record) => storage.hasBlob(record.id)).length;
      return {
        manifests: manifests.length,
        available,
        pendingBytes: manifests.reduce((sum, record) => sum + (storage.hasBlob(record.id) ? 0 : record.size), 0),
        workspaceMirrored: false,
      };
    },

    resumeToken(id) {
      return { artifactId: id, missing: this.missingRanges(id), chunkSize: manifest(id).chunkSize };
    },
  };
}

export function newTransferId() {
  return `tx_${randomBytes(8).toString("hex")}`;
}
