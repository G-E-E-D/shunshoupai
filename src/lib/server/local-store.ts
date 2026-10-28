import { createHash, randomBytes, randomUUID, scryptSync, timingSafeEqual } from "node:crypto";
import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { DatabaseSync } from "node:sqlite";
import type { EventDraft, ScheduleEvent } from "../types";
import { localToIso, isoToLocal } from "../time";
import { AppError } from "./errors";
import { syncRequestSchema, type SyncMutation, type SyncRequest, type SyncResponse, type SyncResult } from "../sync";
import { DeviceStore } from "./device-store";
import { PeerStore } from "./peer-store";
import { ReplicaStore } from "./replica-store";
import type { ReplicaRecord } from "../replica";

export const LOCAL_SESSION_MAX_AGE = 30 * 24 * 60 * 60;

type User = { id: string; email: string };
type EventRow = ScheduleEvent & { user_id: string };
type EventPatch = { title?: string; start_local?: string; end_local?: string; status?: "planned" | "completed" };
type CommitResult = { events: ScheduleEvent[]; replayed: boolean };
export type ModelSettingRecord = {
  base_url: string | null;
  model: string | null;
  api_key_ciphertext: string | null;
  key_hint: string | null;
  version: number;
  updated_at: string;
  deleted_at: string | null;
};

function hash(value: string) {
  return createHash("sha256").update(value).digest("hex");
}

function publicEvent(row: EventRow): ScheduleEvent {
  return {
    id: row.id, title: row.title, start_at: row.start_at, end_at: row.end_at,
    timezone: row.timezone, status: row.status, version: row.version,
    created_at: row.created_at, updated_at: row.updated_at, deleted_at: row.deleted_at,
  };
}

export class LocalStore {
  private db: DatabaseSync;
  readonly devices: DeviceStore;
  readonly replicas: ReplicaStore;
  readonly peers: PeerStore;

  constructor(filename: string) {
    if (filename !== ":memory:") mkdirSync(dirname(resolve(filename)), { recursive: true });
    this.db = new DatabaseSync(filename);
    this.db.exec(`
      PRAGMA foreign_keys = ON;
      PRAGMA journal_mode = WAL;
      PRAGMA busy_timeout = 5000;
      CREATE TABLE IF NOT EXISTS users (
        id TEXT PRIMARY KEY, email TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL,
        created_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS sessions (
        token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        expires_at INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS events (
        id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        title TEXT NOT NULL CHECK(length(title) BETWEEN 1 AND 120),
        start_at TEXT NOT NULL, end_at TEXT NOT NULL CHECK(end_at > start_at),
        timezone TEXT NOT NULL CHECK(timezone = 'Asia/Shanghai'),
        status TEXT NOT NULL DEFAULT 'planned' CHECK(status IN ('planned','completed')),
        version INTEGER NOT NULL DEFAULT 1 CHECK(version > 0),
        created_at TEXT NOT NULL, updated_at TEXT NOT NULL, deleted_at TEXT
      );
      CREATE INDEX IF NOT EXISTS events_owner_time ON events(user_id, start_at, end_at);
      CREATE TABLE IF NOT EXISTS mutation_requests (
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        request_id TEXT NOT NULL, payload_hash TEXT NOT NULL, result TEXT NOT NULL,
        created_at TEXT NOT NULL, PRIMARY KEY(user_id, request_id)
      );
      CREATE TABLE IF NOT EXISTS sync_mutations (
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        mutation_id TEXT NOT NULL, payload_hash TEXT NOT NULL, result TEXT NOT NULL,
        PRIMARY KEY(user_id, mutation_id)
      );
      CREATE TABLE IF NOT EXISTS ai_usage (
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        usage_day TEXT NOT NULL, count INTEGER NOT NULL,
        PRIMARY KEY(user_id, usage_day)
      );
      CREATE TABLE IF NOT EXISTS model_settings (
        user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
        base_url TEXT, model TEXT, api_key_ciphertext TEXT, key_hint TEXT,
        version INTEGER NOT NULL CHECK(version > 0),
        updated_at TEXT NOT NULL, deleted_at TEXT,
        CHECK(deleted_at IS NOT NULL OR (base_url IS NOT NULL AND model IS NOT NULL AND api_key_ciphertext IS NOT NULL AND key_hint IS NOT NULL))
      );
      CREATE TABLE IF NOT EXISTS pairing_requests (
        token_hash TEXT PRIMARY KEY,
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        source_session_hash TEXT NOT NULL REFERENCES sessions(token_hash) ON DELETE CASCADE,
        expires_at INTEGER NOT NULL,
        consumed_at INTEGER
      );
    `);
    this.devices = new DeviceStore(this.db);
    this.replicas = new ReplicaStore(this.db);
    this.peers = new PeerStore(this.db);
  }

  close() { this.db.close(); }

  private transaction<T>(operation: () => T): T {
    this.db.exec("BEGIN IMMEDIATE");
    try {
      const result = operation();
      this.db.exec("COMMIT");
      return result;
    } catch (error) {
      this.db.exec("ROLLBACK");
      throw error;
    }
  }

  register(email: string, password: string): User {
    email = email.trim().toLowerCase();
    const salt = randomBytes(16).toString("hex");
    const passwordHash = `${salt}:${scryptSync(password, salt, 64).toString("hex")}`;
    const user = { id: randomUUID(), email };
    try {
      this.db.prepare("INSERT INTO users(id,email,password_hash,created_at) VALUES (?,?,?,?)")
        .run(user.id, email, passwordHash, new Date().toISOString());
    } catch (error) {
      if ((error as Error).message.includes("UNIQUE")) throw new AppError("EMAIL_EXISTS", "该邮箱已注册，请直接登录。", 409);
      throw error;
    }
    return user;
  }

  login(email: string, password: string): User {
    const row = this.db.prepare("SELECT id,email,password_hash FROM users WHERE email = ?")
      .get(email.trim().toLowerCase()) as (User & { password_hash: string }) | undefined;
    // Keep unknown-user and wrong-password paths similarly expensive.
    const [salt, expectedHex] = (row?.password_hash ?? `${"0".repeat(32)}:${"0".repeat(128)}`).split(":");
    const supplied = scryptSync(password, salt, 64);
    const correct = timingSafeEqual(supplied, Buffer.from(expectedHex, "hex"));
    if (!row || !correct) throw new AppError("INVALID_CREDENTIALS", "邮箱或密码不正确。", 401);
    return { id: row.id, email: row.email };
  }

  createSession(userId: string, now = Date.now()): string {
    const token = randomBytes(32).toString("base64url");
    this.transaction(() => {
      this.db.prepare("DELETE FROM sessions WHERE expires_at <= ?").run(now);
      this.db.prepare("INSERT INTO sessions(token_hash,user_id,expires_at) VALUES (?,?,?)")
        .run(hash(token), userId, now + LOCAL_SESSION_MAX_AGE * 1000);
    });
    return token;
  }

  sessionUser(token: string | undefined, now = Date.now()): User | null {
    if (!token || token.length > 128) return null;
    const row = this.db.prepare(`SELECT u.id,u.email FROM users u JOIN sessions s ON s.user_id=u.id
      WHERE s.token_hash=? AND s.expires_at>?`).get(hash(token), now) as User | undefined;
    return row ? { id: row.id, email: row.email } : null;
  }

  // Renew at most once a day, avoiding database/cookie writes on every poll.
  // An expired or explicitly revoked credential can never be renewed.
  refreshSession(token: string | undefined, now = Date.now()) {
    if (!token || token.length > 128) return null;
    const row = this.db.prepare(`SELECT u.id,u.email,s.expires_at FROM users u JOIN sessions s ON s.user_id=u.id
      WHERE s.token_hash=? AND s.expires_at>?`).get(hash(token), now) as (User & { expires_at: number }) | undefined;
    if (!row) return null;
    const renewed = row.expires_at <= now + (LOCAL_SESSION_MAX_AGE - 86400) * 1000;
    const expires = renewed ? now + LOCAL_SESSION_MAX_AGE * 1000 : row.expires_at;
    if (renewed) this.db.prepare("UPDATE sessions SET expires_at=? WHERE token_hash=? AND expires_at>?").run(expires, hash(token), now);
    return { user: { id: row.id, email: row.email }, expires_at: new Date(expires).toISOString(), renewed };
  }

  deleteSession(token: string | undefined) {
    if (token) this.db.prepare("DELETE FROM sessions WHERE token_hash=?").run(hash(token));
  }

  list(userId: string, from: string, to: string): ScheduleEvent[] {
    const rows = this.db.prepare(`SELECT * FROM events WHERE user_id=? AND deleted_at IS NULL
      AND start_at<? AND end_at>? ORDER BY start_at,id LIMIT 501`).all(userId, to, from) as unknown as EventRow[];
    if (rows.length > 500) throw new AppError("TOO_MANY_EVENTS", "这个时间范围内日程过多，请缩小查询范围。", 400);
    return rows.map(row => this.replicas.decorate(publicEvent(row), userId));
  }

  event(userId: string, id: string): ScheduleEvent {
    const row = this.db.prepare("SELECT * FROM events WHERE user_id=? AND id=?").get(userId, id) as EventRow | undefined;
    if (!row) throw new AppError("NOT_FOUND", "日程不存在或已删除。", 404);
    return this.replicas.decorate(publicEvent(row), userId);
  }

  private assertAvailable(userId: string, start: string, end: string, excludeId = "") {
    const found = this.db.prepare(`SELECT id FROM events WHERE user_id=? AND deleted_at IS NULL
      AND status='planned' AND id<>? AND start_at<? AND end_at>? LIMIT 1`).get(userId, excludeId, end, start);
    if (found) throw new AppError("TIME_CONFLICT", "时间已被其他日程占用，请调整时间后重试。", 409);
  }

  commit(userId: string, requestId: string, drafts: EventDraft[]): CommitResult {
    return this.transaction(() => {
      const payloadHash = hash(JSON.stringify(drafts));
      const previous = this.db.prepare("SELECT payload_hash,result FROM mutation_requests WHERE user_id=? AND request_id=?")
        .get(userId, requestId) as { payload_hash: string; result: string } | undefined;
      if (previous) {
        if (previous.payload_hash !== payloadHash) throw new AppError("IDEMPOTENCY_CONFLICT", "这次保存的内容已改变，请重新确认。", 409);
        return { events: JSON.parse(previous.result), replayed: true };
      }
      const now = new Date().toISOString();
      const events: ScheduleEvent[] = [];
      for (const draft of drafts) {
        const start = localToIso(draft.start_local);
        const end = localToIso(draft.end_local);
        this.assertAvailable(userId, start, end);
        const event: ScheduleEvent = {
          id: randomUUID(), title: draft.title, start_at: start, end_at: end,
          timezone: draft.timezone, status: "planned", version: 1,
          created_at: now, updated_at: now, deleted_at: null,
        };
        this.db.prepare(`INSERT INTO events(id,user_id,title,start_at,end_at,timezone,status,version,created_at,updated_at)
          VALUES (?,?,?,?,?,?,?,?,?,?)`).run(event.id, userId, event.title, start, end, event.timezone, event.status, 1, now, now);
        this.replicas.seed({ ...event, user_id: userId }, this.replicaAuthor());
        events.push(event);
      }
      this.db.prepare("INSERT INTO mutation_requests(user_id,request_id,payload_hash,result,created_at) VALUES (?,?,?,?,?)")
        .run(userId, requestId, payloadHash, JSON.stringify(events), now);
      return { events, replayed: false };
    });
  }

  mutate(userId: string, id: string, baseVersion: number, patch: EventPatch, deleting = false): { event: ScheduleEvent } {
    return this.transaction(() => {
      const row = this.db.prepare("SELECT * FROM events WHERE user_id=? AND id=?").get(userId, id) as EventRow | undefined;
      if (!row) throw new AppError("NOT_FOUND", "日程不存在或已删除。", 404);
      this.replicas.assertResolved(userId, id);
      this.replicas.seed(row, this.replicaAuthor());
      if (row.deleted_at) {
        if (deleting && (row.version === baseVersion + 1 || row.version === baseVersion)) return { event: publicEvent(row) };
        throw new AppError("NOT_FOUND", "日程不存在或已删除。", 404);
      }
      if (row.version !== baseVersion) throw new AppError("VERSION_CONFLICT", "这条日程已在另一端更新，请刷新后重新确认。", 409);
      const start = patch.start_local ? localToIso(patch.start_local) : row.start_at;
      const end = patch.end_local ? localToIso(patch.end_local) : row.end_at;
      if (end <= start || Date.parse(end) - Date.parse(start) > 24 * 60 * 60 * 1000) {
        throw new AppError("VALIDATION_ERROR", "结束时间必须晚于开始时间，单次日程不超过 24 小时。", 400);
      }
      // Retain explicit local conversion checks on merged start/end as well.
      isoToLocal(start);
      const status = patch.status ?? row.status;
      if (!deleting && status === "planned" && (row.status !== "planned" || start !== row.start_at || end !== row.end_at)) this.assertAvailable(userId, start, end, id);
      const now = new Date().toISOString();
      this.db.prepare(`UPDATE events SET title=?,start_at=?,end_at=?,status=?,version=version+1,updated_at=?,deleted_at=?
        WHERE user_id=? AND id=? AND version=?`).run(patch.title ?? row.title, start, end, status, now, deleting ? now : null, userId, id, baseVersion);
      const updated = this.db.prepare("SELECT * FROM events WHERE user_id=? AND id=?").get(userId, id) as unknown as EventRow;
      this.replicas.change(updated, this.replicaAuthor());
      return { event: publicEvent(updated) };
    });
  }

  private replicaAuthor() { return this.devices.ready() ? this.devices.context().device.name : "本机"; }

  replicaPage(space: string, after?: string) {
    return this.transaction(() => this.replicas.page(space, after, this.replicaAuthor()));
  }

  exchange(space: string, records: ReplicaRecord[], after?: string) {
    return this.transaction(() => {
      for (const record of records) this.replicas.merge(space, record, this.replicaAuthor());
      return this.replicas.page(space, after, this.replicaAuthor());
    });
  }

  resolveReplica(space: string, id: string, baseVersion: number, revisionId: string) {
    return this.transaction(() => {
      this.replicas.resolve(space, id, baseVersion, revisionId, this.replicaAuthor());
      return { event: this.event(space, id) };
    });
  }

  disconnectPeer(id: string) {
    this.transaction(() => { this.peers.disable(id); this.devices.revokeClient(id); });
  }

  consumeAiQuota(userId: string, limit: number): boolean {
    const day = new Date().toISOString().slice(0, 10);
    return this.transaction(() => {
      const row = this.db.prepare("SELECT count FROM ai_usage WHERE user_id=? AND usage_day=?").get(userId, day) as { count: number } | undefined;
      if ((row?.count ?? 0) >= limit) return false;
      this.db.prepare(`INSERT INTO ai_usage(user_id,usage_day,count) VALUES (?,?,1)
        ON CONFLICT(user_id,usage_day) DO UPDATE SET count=count+1`).run(userId, day);
      return true;
    });
  }

  sync(userId: string, input: SyncRequest): SyncResponse {
    const request = syncRequestSchema.parse(input);
    if (request.account_id !== userId) throw new AppError("ACCOUNT_MISMATCH", "配对空间已改变，本机日程已保留，请重新核对设备。", 409);
    return this.transaction(() => {
      const results = request.mutations.map((mutation) => this.syncMutation(userId, mutation));
      // Include tombstones: absence from a page never means an event was deleted.
      const rows = this.db.prepare("SELECT * FROM events WHERE user_id=? AND id>? ORDER BY id LIMIT 201")
        .all(userId, request.after ?? "") as unknown as EventRow[];
      const events = rows.slice(0, 200).map(publicEvent);
      return { results, events, has_more: rows.length > 200, next_cursor: rows.length > 200 ? events.at(-1)!.id : null };
    });
  }

  private syncMutation(userId: string, mutation: SyncMutation): SyncResult {
    const payloadHash = hash(JSON.stringify(mutation));
    const previous = this.db.prepare("SELECT payload_hash,result FROM sync_mutations WHERE user_id=? AND mutation_id=?")
      .get(userId, mutation.mutation_id) as { payload_hash: string; result: string } | undefined;
    if (previous) {
      if (previous.payload_hash !== payloadHash) throw new AppError("IDEMPOTENCY_CONFLICT", "同步内容已改变，请保留原请求核实保存结果。", 409);
      return JSON.parse(previous.result);
    }
    const row = this.db.prepare("SELECT * FROM events WHERE user_id=? AND id=?").get(userId, mutation.id) as EventRow | undefined;
    let result: SyncResult;
    this.db.exec("SAVEPOINT legacy_mutation");
    try {
      if (mutation.base_version === 0) {
        // Globally unique IDs may never take over another account's row.
        if (this.db.prepare("SELECT id FROM events WHERE id=?").get(mutation.id)) throw new AppError("ID_CONFLICT", "这条日程编号已存在，手机内容已保留，请另存为新日程。", 409);
      } else {
        if (!row) throw new AppError("NOT_FOUND", "这条日程已不存在，手机内容已保留。", 409);
        if (row.version !== mutation.base_version || row.deleted_at) throw new AppError("VERSION_CONFLICT", "两台设备都修改了这条日程，手机内容已保留，请选择要使用的版本。", 409);
      }
      this.replicas.assertResolved(userId, mutation.id);
      if (row) this.replicas.seed(row, this.replicaAuthor());
      const start = mutation.event ? localToIso(mutation.event.start_local) : row!.start_at;
      const end = mutation.event ? localToIso(mutation.event.end_local) : row!.end_at;
      const status = mutation.event?.status ?? row!.status;
      // Independent offline appointments are real commitments: retain both
      // even when their times overlap. Clients show overlap without data loss.
      const now = new Date().toISOString();
      if (mutation.base_version === 0) {
        this.db.prepare(`INSERT INTO events(id,user_id,title,start_at,end_at,timezone,status,version,created_at,updated_at)
          VALUES(?,?,?,?,?,'Asia/Shanghai',?,1,?,?)`).run(mutation.id, userId, mutation.event!.title, start, end, status, now, now);
      } else {
        this.db.prepare(`UPDATE events SET title=?,start_at=?,end_at=?,status=?,version=version+1,updated_at=?,deleted_at=?
          WHERE user_id=? AND id=?`).run(mutation.event?.title ?? row!.title, start, end, status, now, mutation.deleted ? now : null, userId, mutation.id);
      }
      const updated = this.db.prepare("SELECT * FROM events WHERE user_id=? AND id=?").get(userId, mutation.id) as unknown as EventRow;
      this.replicas.change(updated, this.replicaAuthor());
      result = { mutation_id: mutation.mutation_id, status: "applied", event: publicEvent(updated) };
    } catch (error) {
      if (!(error instanceof AppError)) throw error;
      this.db.exec("ROLLBACK TO legacy_mutation");
      result = { mutation_id: mutation.mutation_id, status: "conflict", code: error.code, message: error.message, ...(row ? { event: publicEvent(row) } : {}) };
    }
    this.db.exec("RELEASE legacy_mutation");
    this.db.prepare("INSERT INTO sync_mutations(user_id,mutation_id,payload_hash,result) VALUES(?,?,?,?)")
      .run(userId, mutation.mutation_id, payloadHash, JSON.stringify(result));
    return result;
  }

  modelSetting(userId: string): ModelSettingRecord | null {
    const row = this.db.prepare("SELECT base_url,model,api_key_ciphertext,key_hint,version,updated_at,deleted_at FROM model_settings WHERE user_id=?")
      .get(userId) as ModelSettingRecord | undefined;
    return row ?? null;
  }

  saveModelSetting(userId: string, baseVersion: number, input: {
    base_url: string; model: string; api_key_ciphertext: string; key_hint: string;
  }): ModelSettingRecord {
    return this.transaction(() => {
      const previous = this.modelSetting(userId);
      if ((previous?.version ?? 0) !== baseVersion) throw new AppError("VERSION_CONFLICT", "模型设置已在另一端更新，请重新加载后再保存。", 409);
      const updated = new Date().toISOString();
      this.db.prepare(`INSERT INTO model_settings(user_id,base_url,model,api_key_ciphertext,key_hint,version,updated_at,deleted_at)
        VALUES(?,?,?,?,?,?,?,NULL) ON CONFLICT(user_id) DO UPDATE SET
        base_url=excluded.base_url,model=excluded.model,api_key_ciphertext=excluded.api_key_ciphertext,
        key_hint=excluded.key_hint,version=excluded.version,updated_at=excluded.updated_at,deleted_at=NULL`)
        .run(userId, input.base_url, input.model, input.api_key_ciphertext, input.key_hint, baseVersion + 1, updated);
      return this.modelSetting(userId)!;
    });
  }

  deleteModelSetting(userId: string, baseVersion: number): ModelSettingRecord | null {
    return this.transaction(() => {
      const previous = this.modelSetting(userId);
      if (!previous) {
        if (baseVersion !== 0) throw new AppError("VERSION_CONFLICT", "模型设置已在另一端更新，请重新加载后再操作。", 409);
        return null;
      }
      if (previous.deleted_at && (previous.version === baseVersion || previous.version === baseVersion + 1)) return previous;
      if (previous.version !== baseVersion) throw new AppError("VERSION_CONFLICT", "模型设置已在另一端更新，请重新加载后再操作。", 409);
      const now = new Date().toISOString();
      this.db.prepare(`UPDATE model_settings SET base_url=NULL,model=NULL,api_key_ciphertext=NULL,key_hint=NULL,
        version=version+1,updated_at=?,deleted_at=? WHERE user_id=? AND version=?`).run(now, now, userId, baseVersion);
      return this.modelSetting(userId);
    });
  }

  issuePairing(userId: string, sourceToken: string, now = Date.now()): { token: string; expires_at: string } {
    return this.transaction(() => {
      const sourceHash = hash(sourceToken);
      const source = this.db.prepare("SELECT expires_at FROM sessions WHERE token_hash=? AND user_id=? AND expires_at>?")
        .get(sourceHash, userId, now) as { expires_at: number } | undefined;
      if (!source) throw new AppError("UNAUTHORIZED", "当前登录已失效，请重新登录后生成二维码。", 401);
      this.db.prepare("DELETE FROM pairing_requests WHERE expires_at<=?").run(now);
      this.db.prepare("DELETE FROM pairing_requests WHERE source_session_hash=? AND consumed_at IS NULL").run(sourceHash);
      const token = randomBytes(32).toString("base64url");
      const expires = Math.min(source.expires_at, now + 90_000);
      this.db.prepare("INSERT INTO pairing_requests(token_hash,user_id,source_session_hash,expires_at) VALUES(?,?,?,?)")
        .run(hash(token), userId, sourceHash, expires);
      return { token, expires_at: new Date(expires).toISOString() };
    });
  }

  redeemPairing(token: string, now = Date.now()): { user: User; session_token: string; session_expires_at: string } {
    return this.transaction(() => {
      const pairing = this.db.prepare(`SELECT p.user_id,p.expires_at,p.consumed_at,s.expires_at AS source_expires_at,u.email
        FROM pairing_requests p JOIN sessions s ON s.token_hash=p.source_session_hash AND s.user_id=p.user_id
        JOIN users u ON u.id=p.user_id WHERE p.token_hash=?`).get(hash(token)) as {
          user_id: string; expires_at: number; consumed_at: number | null; source_expires_at: number; email: string;
        } | undefined;
      if (!pairing || pairing.consumed_at !== null || pairing.expires_at <= now || pairing.source_expires_at <= now) {
        throw new AppError("PAIRING_INVALID", "登录二维码已失效或已使用，请在已登录设备重新生成。", 400);
      }
      this.db.prepare("UPDATE pairing_requests SET consumed_at=? WHERE token_hash=? AND consumed_at IS NULL").run(now, hash(token));
      const sessionToken = randomBytes(32).toString("base64url");
      const sessionExpires = now + LOCAL_SESSION_MAX_AGE * 1000;
      this.db.prepare("INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)").run(hash(sessionToken), pairing.user_id, sessionExpires);
      return { user: { id: pairing.user_id, email: pairing.email }, session_token: sessionToken, session_expires_at: new Date(sessionExpires).toISOString() };
    });
  }
}

const globalStore = globalThis as unknown as { shunshoupaiStore?: LocalStore };
export function localStore(): LocalStore {
  if (!globalStore.shunshoupaiStore) {
    globalStore.shunshoupaiStore = new LocalStore(process.env.APP_DB_PATH || resolve(process.cwd(), "data", "shunshoupai.sqlite"));
  }
  return globalStore.shunshoupaiStore;
}
