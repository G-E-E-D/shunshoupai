import { createHash, randomBytes, randomUUID, scryptSync, timingSafeEqual } from "node:crypto";
import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { DatabaseSync } from "node:sqlite";
import type { EventDraft, ScheduleEvent } from "../types";
import { localToIso, isoToLocal } from "../time";
import { AppError } from "./errors";

type User = { id: string; email: string };
type EventRow = ScheduleEvent & { user_id: string };
type EventPatch = { title?: string; start_local?: string; end_local?: string; status?: "planned" | "completed" };
type CommitResult = { events: ScheduleEvent[]; replayed: boolean };

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
      CREATE TABLE IF NOT EXISTS ai_usage (
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        usage_day TEXT NOT NULL, count INTEGER NOT NULL,
        PRIMARY KEY(user_id, usage_day)
      );
    `);
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

  createSession(userId: string): string {
    const token = randomBytes(32).toString("base64url");
    this.transaction(() => {
      this.db.prepare("DELETE FROM sessions WHERE expires_at <= ?").run(Date.now());
      this.db.prepare("INSERT INTO sessions(token_hash,user_id,expires_at) VALUES (?,?,?)")
        .run(hash(token), userId, Date.now() + 7 * 24 * 60 * 60 * 1000);
    });
    return token;
  }

  sessionUser(token: string | undefined): User | null {
    if (!token || token.length > 128) return null;
    const row = this.db.prepare(`SELECT u.id,u.email FROM users u JOIN sessions s ON s.user_id=u.id
      WHERE s.token_hash=? AND s.expires_at>?`).get(hash(token), Date.now()) as User | undefined;
    return row ? { id: row.id, email: row.email } : null;
  }

  deleteSession(token: string | undefined) {
    if (token) this.db.prepare("DELETE FROM sessions WHERE token_hash=?").run(hash(token));
  }

  list(userId: string, from: string, to: string): ScheduleEvent[] {
    const rows = this.db.prepare(`SELECT * FROM events WHERE user_id=? AND deleted_at IS NULL
      AND start_at<? AND end_at>? ORDER BY start_at,id LIMIT 501`).all(userId, to, from) as unknown as EventRow[];
    if (rows.length > 500) throw new AppError("TOO_MANY_EVENTS", "这个时间范围内日程过多，请缩小查询范围。", 400);
    return rows.map(publicEvent);
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
      if (!deleting && status === "planned") this.assertAvailable(userId, start, end, id);
      const now = new Date().toISOString();
      this.db.prepare(`UPDATE events SET title=?,start_at=?,end_at=?,status=?,version=version+1,updated_at=?,deleted_at=?
        WHERE user_id=? AND id=? AND version=?`).run(patch.title ?? row.title, start, end, status, now, deleting ? now : null, userId, id, baseVersion);
      const updated = this.db.prepare("SELECT * FROM events WHERE user_id=? AND id=?").get(userId, id) as unknown as EventRow;
      return { event: publicEvent(updated) };
    });
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
}

const globalStore = globalThis as unknown as { shunshoupaiStore?: LocalStore };
export function localStore(): LocalStore {
  if (!globalStore.shunshoupaiStore) {
    globalStore.shunshoupaiStore = new LocalStore(process.env.APP_DB_PATH || resolve(process.cwd(), "data", "shunshoupai.sqlite"));
  }
  return globalStore.shunshoupaiStore;
}
