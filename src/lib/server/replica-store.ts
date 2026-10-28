import { createHash, randomUUID } from "node:crypto";
import type { DatabaseSync } from "node:sqlite";
import { createRevision, distinctPayloadHeads, mergeReplica, replicaRecordSchema, type ReplicaHead, type ReplicaPayload, type ReplicaRecord } from "../replica";
import { isoToLocal, localToIso } from "../time";
import type { ScheduleEvent } from "../types";
import { AppError } from "./errors";

type Row = ScheduleEvent & { user_id: string };
export const eventPayload = (row: ScheduleEvent): ReplicaPayload => ({
  title: row.title, start_local: isoToLocal(row.start_at), end_local: isoToLocal(row.end_at),
  timezone: "Asia/Shanghai", status: row.status, deleted: row.deleted_at !== null,
});

/** All writes run inside LocalStore's transaction, including the SQL projection. */
export class ReplicaStore {
  constructor(private db: DatabaseSync) {
    const hadOwners = !!db.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name='replica_revision_owners'").get();
    db.exec(`CREATE TABLE IF NOT EXISTS replica_records (
      id TEXT PRIMARY KEY REFERENCES events(id), space_id TEXT NOT NULL, heads TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS replica_receipts (
      revision_id TEXT PRIMARY KEY, event_id TEXT NOT NULL, head TEXT NOT NULL, digest TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS replica_revision_owners(revision_id TEXT PRIMARY KEY,event_id TEXT NOT NULL);`);
    if (!hadOwners || !db.prepare('SELECT 1 FROM replica_revision_owners LIMIT 1').get()) {
      db.exec('BEGIN IMMEDIATE');
      try {
        const receipts = db.prepare('SELECT event_id,head FROM replica_receipts').all() as { event_id: string; head: string }[];
        for (const receipt of receipts) this.reserveOwners(receipt.event_id, JSON.parse(receipt.head));
        db.exec('COMMIT');
      } catch (error) { db.exec('ROLLBACK'); throw error; }
    }
  }

  record(space: string, id: string): ReplicaRecord | undefined {
    const row = this.db.prepare("SELECT heads FROM replica_records WHERE space_id=? AND id=?").get(space, id) as { heads: string } | undefined;
    return row ? { id, heads: JSON.parse(row.heads) } : undefined;
  }

  decorate(event: ScheduleEvent, space: string): ScheduleEvent {
    const heads = this.record(space, event.id)?.heads;
    const variants = heads ? distinctPayloadHeads(heads) : [];
    return variants.length > 1 ? { ...event, replica_conflicts: variants.map(({ revision_id, author, changed_at, payload }) => ({ revision_id, author, changed_at, payload })) } : event;
  }

  assertResolved(space: string, id: string) {
    const record = this.record(space, id);
    if (record && distinctPayloadHeads(record.heads).length > 1) throw new AppError("REPLICA_CONFLICT", "这条日程有不同设备的修改，请先选择要保留的版本。", 409);
  }

  seed(row: Row, author: string) {
    if (this.record(row.user_id, row.id)) return;
    this.save(row.user_id, { id: row.id, heads: [createRevision({ baseHeads: [], revisionId: randomUUID(), payload: eventPayload(row), author, changedAt: Date.parse(row.updated_at) })] });
  }

  change(row: Row, author: string) {
    const previous = this.record(row.user_id, row.id);
    this.save(row.user_id, { id: row.id, heads: [createRevision({ baseHeads: previous?.heads ?? [], revisionId: randomUUID(), payload: eventPayload(row), author, changedAt: Date.now() })] });
  }

  private save(space: string, record: ReplicaRecord) {
    // Retain receipts after a head is superseded, so a restored peer cannot
    // reuse an old revision ID with different data undetected.
    const findReceipt = this.db.prepare("SELECT event_id,head,digest FROM replica_receipts WHERE revision_id=?");
    for (const head of record.heads) {
      const content = JSON.stringify(head);
      const digest = createHash("sha256").update(content).digest("hex");
      const known = findReceipt.get(head.revision_id) as { event_id: string; digest: string } | undefined;
      if (known && (known.event_id !== record.id || known.digest !== digest)) throw new AppError("REVISION_COLLISION", "版本编号对应不同内容，已保留本机日程并停止合并。", 409);
      if (known) continue;
      this.reserveOwners(record.id, head);
      const ancestors = new Set(head.ancestors);
      for (const ancestor of ancestors) {
        const receipt = findReceipt.get(ancestor) as { event_id: string; head: string } | undefined;
        if (receipt) {
          const old: ReplicaHead = JSON.parse(receipt.head);
          if (receipt.event_id !== record.id || old.ancestors.some(id => !ancestors.has(id)) || old.ancestors.includes(head.revision_id)) {
            throw new AppError("INVALID_ANCESTRY", "版本祖先关系与已保存历史不一致，已停止合并。", 409);
          }
        }
      }
      // A head may arrive before an ancestor's full contents. Learning that
      // ancestor later must still satisfy every previously recorded descendant.
      const descendants = this.db.prepare(`SELECT head FROM replica_receipts
        WHERE EXISTS(SELECT 1 FROM json_each(json_extract(head,'$.ancestors')) WHERE value=?)`).all(head.revision_id) as { head: string }[];
      for (const row of descendants) {
        const descendant: ReplicaHead = JSON.parse(row.head);
        const declared = new Set(descendant.ancestors);
        if (ancestors.has(descendant.revision_id) || head.ancestors.some(id => !declared.has(id))) throw new AppError('INVALID_ANCESTRY', '迟到的版本与已保存的后续历史不一致，已停止合并。', 409);
      }
      this.db.prepare("INSERT OR IGNORE INTO replica_receipts(revision_id,event_id,head,digest) VALUES(?,?,?,?)").run(head.revision_id, record.id, content, digest);
    }
    this.db.prepare("INSERT INTO replica_records(id,space_id,heads) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET heads=excluded.heads")
      .run(record.id, space, JSON.stringify(record.heads));
  }

  private reserveOwners(eventId: string, head: ReplicaHead) {
    const get = this.db.prepare('SELECT event_id FROM replica_revision_owners WHERE revision_id=?');
    const insert = this.db.prepare('INSERT OR IGNORE INTO replica_revision_owners(revision_id,event_id) VALUES(?,?)');
    for (const id of [head.revision_id, ...head.ancestors]) {
      const row = get.get(id) as { event_id: string } | undefined;
      if (row && row.event_id !== eventId) throw new AppError('INVALID_ANCESTRY', '同一版本编号不能属于不同日程，已停止合并。', 409);
      insert.run(id, eventId);
    }
  }

  merge(space: string, input: ReplicaRecord, author: string) {
    const record = replicaRecordSchema.parse(input);
    if (Buffer.byteLength(JSON.stringify(record)) > 8 * 1024 * 1024) throw new AppError("BODY_TOO_LARGE", "这条日程的历史过大，未丢弃任何版本。", 413);
    const row = this.db.prepare("SELECT * FROM events WHERE id=?").get(record.id) as Row | undefined;
    if (row && row.user_id !== space) throw new AppError("ID_CONFLICT", "日程编号已用于另一份本机日程，未覆盖原数据。", 409);
    if (row) this.seed(row, author);
    const previous = this.record(space, record.id);
    const merged = mergeReplica(previous, record);
    const now = new Date().toISOString();
    // Prefer a live variant solely for displaying the unresolved entry. No
    // content is selected or discarded until the user resolves all heads.
    const projection = merged.heads.find(head => !head.payload.deleted) ?? merged.heads[0];
    const payload = projection.payload;
    if (!row) this.db.prepare(`INSERT INTO events(id,user_id,title,start_at,end_at,timezone,status,version,created_at,updated_at,deleted_at)
      VALUES(?,?,?,?,?,'Asia/Shanghai',?,1,?,?,?)`).run(record.id, space, payload.title, localToIso(payload.start_local), localToIso(payload.end_local), payload.status, now, now, payload.deleted ? now : null);
    // Check and retain every received head, even ones already superseded.
    this.save(space, record);
    this.save(space, merged);
    if (row && JSON.stringify(previous?.heads) !== JSON.stringify(merged.heads)) this.db.prepare(`UPDATE events SET title=?,start_at=?,end_at=?,status=?,version=version+1,updated_at=?,deleted_at=? WHERE id=? AND user_id=?`)
      .run(payload.title, localToIso(payload.start_local), localToIso(payload.end_local), payload.status, now, payload.deleted ? now : null, record.id, space);
  }

  resolve(space: string, id: string, baseVersion: number, revisionId: string, author: string) {
    const row = this.db.prepare("SELECT * FROM events WHERE id=? AND user_id=?").get(id, space) as Row | undefined;
    if (!row) throw new AppError("NOT_FOUND", "日程不存在。", 404);
    if (row.version !== baseVersion) throw new AppError("VERSION_CONFLICT", "又收到了新的修改，请查看后重新选择。", 409);
    const record = this.record(space, id);
    const chosen = record?.heads.find(head => head.revision_id === revisionId);
    if (!record || !chosen) throw new AppError("VERSION_CONFLICT", "选中的版本已改变，请重新加载。", 409);
    const next = createRevision({ baseHeads: record.heads, revisionId: randomUUID(), payload: chosen.payload, author, changedAt: Date.now() });
    this.merge(space, { id, heads: [next] }, author);
  }

  page(space: string, after: string | undefined, author: string) {
    const rows = this.db.prepare("SELECT * FROM events WHERE user_id=? AND id>? ORDER BY id LIMIT 51").all(space, after ?? "") as unknown as Row[];
    const records: ReplicaRecord[] = [];
    let bytes = 0;
    for (const row of rows) {
      this.seed(row, author);
      const record = this.record(space, row.id)!;
      const size = Buffer.byteLength(JSON.stringify(record));
      if (size > 8 * 1024 * 1024) throw new AppError("BODY_TOO_LARGE", "日程历史过大，未截断任何内容。", 413);
      if (records.length >= 50 || (records.length && bytes + size > 2 * 1024 * 1024)) break;
      records.push(record); bytes += size;
    }
    const has_more = records.length < rows.length;
    return { records, has_more, next_cursor: has_more ? records.at(-1)!.id : null };
  }
}
