import type { DatabaseSync } from "node:sqlite";
import { decryptModelKey, encryptModelKey, modelEncryptionKey } from "./model-crypto";
import { AppError } from "./errors";

export type PeerConnection = { id: string; space_id: string; name: string; origin: string; public_key: string; token: string; grant_id: string | null };
type PeerRow = Omit<PeerConnection, "token"> & { credential: string; enabled: number; last_sync_at: string | null; error: string | null };
export class PeerStore {
  constructor(private db: DatabaseSync) {
    db.exec(`CREATE TABLE IF NOT EXISTS peer_connections (
      id TEXT PRIMARY KEY, space_id TEXT NOT NULL, name TEXT NOT NULL, origin TEXT NOT NULL,
      public_key TEXT NOT NULL, credential TEXT NOT NULL, grant_id TEXT,
      enabled INTEGER NOT NULL DEFAULT 1, last_sync_at TEXT, error TEXT
    );
    CREATE TABLE IF NOT EXISTS peer_control(singleton INTEGER PRIMARY KEY CHECK(singleton=1), generation INTEGER NOT NULL);
    INSERT OR IGNORE INTO peer_control(singleton,generation) VALUES(1,0);`);
  }
  pin(id: string) { return (this.db.prepare("SELECT public_key FROM peer_connections WHERE id=?").get(id) as { public_key: string } | undefined)?.public_key; }
  save(peer: PeerConnection) {
    if (this.pin(peer.id) && this.pin(peer.id) !== peer.public_key) throw new AppError("PEER_IDENTITY_CHANGED", "设备身份已改变，未发送或覆盖原配对凭据。请核对目标设备。", 409);
    const credential = encryptModelKey(peer.token, `peer:${peer.id}:${peer.space_id}`, modelEncryptionKey("local", true));
    this.db.prepare(`INSERT INTO peer_connections(id,space_id,name,origin,public_key,credential,grant_id) VALUES(?,?,?,?,?,?,?)
      ON CONFLICT(id) DO UPDATE SET space_id=excluded.space_id,name=excluded.name,origin=excluded.origin,public_key=excluded.public_key,
      credential=excluded.credential,grant_id=excluded.grant_id,enabled=1,error=NULL`).run(peer.id, peer.space_id, peer.name, peer.origin, peer.public_key, credential, peer.grant_id);
  }
  list() {
    const rows = this.db.prepare("SELECT id,name,origin,last_sync_at,error FROM peer_connections WHERE enabled=1 ORDER BY name,id").all() as { id: string; name: string; origin: string; last_sync_at: string | null; error: string | null }[];
    return rows.map(row => ({ ...row, connected: !row.error && !!row.last_sync_at && Date.now() - Date.parse(row.last_sync_at) < 60_000 }));
  }
  all(): PeerConnection[] {
    const rows = this.db.prepare("SELECT * FROM peer_connections WHERE enabled=1 ORDER BY id").all() as PeerRow[];
    return rows.flatMap(row => {
      try { return [{ id: row.id, space_id: row.space_id, name: row.name, origin: row.origin, public_key: row.public_key, grant_id: row.grant_id,
        token: decryptModelKey(row.credential, `peer:${row.id}:${row.space_id}`, modelEncryptionKey("local")) }]; }
      catch {
        this.failed(row.id, "本机配对凭据无法读取，日程仍在本机；请重新扫码配对。");
        return [];
      }
    });
  }
  active(id: string) { return !!this.db.prepare("SELECT 1 FROM peer_connections WHERE id=? AND enabled=1").get(id); }
  generation() { return (this.db.prepare("SELECT generation FROM peer_control WHERE singleton=1").get() as { generation: number }).generation; }
  synced(id: string) { this.db.prepare("UPDATE peer_connections SET last_sync_at=?,error=NULL WHERE id=? AND enabled=1").run(new Date().toISOString(), id); }
  failed(id: string, error: string) { this.db.prepare("UPDATE peer_connections SET error=? WHERE id=? AND enabled=1").run(error, id); }
  disable(id: string) {
    this.db.prepare("UPDATE peer_control SET generation=generation+1 WHERE singleton=1").run();
    this.db.prepare("UPDATE peer_connections SET enabled=0,credential='',error=NULL WHERE id=?").run(id);
  }
}
