/**
 * sync 模块入口（CONTRACT.md §8）：`createSyncService({storage, identity})`。
 * 其余导出是同一层的组成部件，便于 core/测试按需组合。
 */
export {
  createSyncService,
  SyncServiceError,
  SNAPSHOT_VERSION,
  SYNC_DEFAULTS,
} from "./sync-service.mjs";

export {
  createDeviceIdentity,
  IdentityError,
  IDENTITY_SCHEMA_VERSION,
  DEVICES_SCHEMA_VERSION,
  PAIRING_PAYLOAD_VERSION,
  deviceIdFromPublicKey,
  fingerprintOfPublicKey,
  publicKeyPemFromBase64,
  publicKeyBase64FromPem,
  verifySignature,
  createPairingPayload,
  encodePairingPayload,
  decodePairingPayload,
} from "./identity.mjs";

export {
  PAIRING_ALPHABET,
  PAIRING_CODE_LENGTH,
  PAIRING_CODE_TTL_MS,
  PAIRING_MAX_ATTEMPTS,
  PairingError,
  normalizePairingCode,
  isValidPairingCodeFormat,
  formatPairingCode,
  createPairingCode,
  isPairingCodeExpired,
  remainingPairingAttempts,
  pairingCodesMatch,
  verifyPairingCode,
  consumePairingCode,
  activePairingCodes,
} from "./pairing.mjs";

export {
  SYNC_PROTOCOL,
  SUPPORTED_PROTOCOLS,
  MAX_FRAME_BYTES,
  HANDSHAKE_TIMEOUT_MS,
  SyncProtocolError,
  negotiateProtocol,
  createEphemeralKeyPair,
  createHello,
  buildHandshakeTranscript,
  deriveSessionKeys,
  noncePrefixFor,
  frameNonce,
  encryptFrame,
  decryptFrame,
  createSecureSession,
} from "./crypto.mjs";

export {
  DISCOVERY_MAGIC,
  DISCOVERY_VERSION,
  DISCOVERY_BEACON_PORT,
  DEFAULT_BEACON_INTERVAL_MS,
  DEFAULT_BEACON_MAX_AGE_MS,
  ROUTE_PRIORITY,
  DiscoveryError,
  createBeacon,
  encodeBeacon,
  decodeBeacon,
  classifyAddress,
  chooseRoute,
  createRetryPolicy,
  createReconnectTracker,
  candidateKey,
  createDiscovery,
  createUdpBeaconTransport,
  beaconHash,
} from "./discovery.mjs";

export { createMemoryTransportPair, createTcpServer, connectTcp, TransportError } from "./transport.mjs";

export {
  CURSOR_SCHEMA_VERSION,
  CursorError,
  createSyncCursor,
  advanceCursor,
  acknowledgeCursor,
  mergeCursors,
  serializeCursor,
  parseCursor,
  canResumeFrom,
  createLamportClock,
  stableStringify,
  contentHash,
  paOrderKey,
  sortPaMessages,
  mergePaLog,
  orderOps,
  nextExpectedSeq,
} from "./cursors.mjs";

export { STORAGE_SCHEMA_VERSION, SyncStorageError, createMemoryStorage, createFileStorage } from "./storage.mjs";

export {
  DELIVERY_STATES,
  CommandQueueError,
  createCommandQueue,
} from "./commands-queue.mjs";

export {
  INTERACTION_KINDS,
  INTERACTION_STATUSES,
  DEFAULT_INTERACTION_TTL_MS,
  InteractionError,
  compareAnswers,
  resolveCompetition,
  createInteractionsSync,
} from "./interactions-sync.mjs";

export {
  DEFAULT_CHUNK_SIZE,
  DEFAULT_MAX_ARTIFACT_BYTES,
  ArtifactError,
  createArtifactTransfer,
  newTransferId,
} from "./artifacts.mjs";

export {
  EXIT_KINDS,
  RECOVERY_STATES,
  InjectedFault,
  classifyProcessExit,
  createRecoveryJournal,
  recoverTurns,
  recoverSessions,
  recoverTerminals,
  settlePendingInteractions,
  createSeededRandom,
  withTimeout,
  createFaultHarness,
} from "./reliability.mjs";

export { percentile, summarize, runBenchmarks, PERF_SCHEMA_VERSION } from "./perf.mjs";
