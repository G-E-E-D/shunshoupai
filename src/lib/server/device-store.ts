import { createHash, generateKeyPairSync, randomBytes, randomUUID, sign } from "node:crypto";
import type { DatabaseSync } from "node:sqlite";
import { AppError } from "./errors";

export type DeviceGrant = { id: string; space_id: string; client_id: string; name: string; platform: string; role: "owner" | "peer"; created_at: string; last_seen_at: string };
type Identity = { id: string; space_id: string; name: string; public_key: string; private_key: string };
export const deviceTokenHash = (token: string) => createHash("sha256").update(token).digest("hex");

/** One local calendar, explicitly trusted devices, and no account service. */
export class DeviceStore {
  constructor(private db: DatabaseSync) {
    db.exec(`
      CREATE TABLE IF NOT EXISTS local_device (
        singleton INTEGER PRIMARY KEY CHECK(singleton=1), id TEXT NOT NULL UNIQUE,
        space_id TEXT NOT NULL REFERENCES users(id), name TEXT NOT NULL,
        public_key TEXT NOT NULL, private_key TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS device_grants (
        id TEXT PRIMARY KEY, space_id TEXT NOT NULL REFERENCES users(id), client_id TEXT NOT NULL,
        token_hash TEXT NOT NULL UNIQUE, name TEXT NOT NULL, platform TEXT NOT NULL,
        role TEXT NOT NULL CHECK(role IN ('owner','peer')), created_at TEXT NOT NULL,
        last_seen_at TEXT NOT NULL, revoked_at TEXT
      );
      CREATE TABLE IF NOT EXISTS device_pairings (
        token_hash TEXT PRIMARY KEY, owner_grant_id TEXT NOT NULL REFERENCES device_grants(id),
        space_id TEXT NOT NULL REFERENCES users(id), expires_at INTEGER NOT NULL, consumed_at INTEGER
      );
    `);
  }

  private transaction<T>(operation: () => T): T {
    this.db.exec("BEGIN IMMEDIATE");
    try { const result = operation(); this.db.exec("COMMIT"); return result; }
    catch (error) { this.db.exec("ROLLBACK"); throw error; }
  }

  ready() { return !!this.db.prepare("SELECT 1 FROM local_device WHERE singleton=1").get(); }

  private identity(): Identity {
    const row = this.db.prepare("SELECT * FROM local_device WHERE singleton=1").get() as Identity | undefined;
    if (!row) throw new AppError("DEVICE_NOT_READY", "请先打开这台电脑上的顺手排，再生成配对码。", 503);
    return row;
  }

  bootstrap(name: string, platform: string, currentToken?: string, legacyToken?: string, legacySpace?: string) {
    return this.transaction(() => {
      let identity = this.db.prepare("SELECT * FROM local_device WHERE singleton=1").get() as Identity | undefined;
      if (!identity) {
        let space: string | undefined;
        if (legacyToken && legacyToken.length <= 128) {
          const row = this.db.prepare("SELECT user_id FROM sessions WHERE token_hash=? AND expires_at>?")
            .get(deviceTokenHash(legacyToken), Date.now()) as { user_id: string } | undefined;
          space = row?.user_id;
        }
        // Even an empty legacy database profile may own client-side drafts.
        const spaces = this.db.prepare("SELECT id,email AS label FROM users ORDER BY created_at").all() as { id: string; label: string }[];
        if (legacySpace) {
          if (!spaces.some((item) => item.id === legacySpace)) throw new AppError("INVALID_SPACE", "找不到这份旧版日程，请重新选择。", 400);
          space = legacySpace;
        }
        if (!space && spaces.length > 1) throw new AppError("MIGRATION_REQUIRED", "发现多份旧版日程，请选择在这台设备继续使用的一份；其他数据会保留。", 409, { spaces });
        space ??= spaces[0]?.id;
        if (!space) {
          space = randomUUID();
          // Retain the legacy FK layout without creating a usable account.
          this.db.prepare("INSERT INTO users(id,email,password_hash,created_at) VALUES(?,?,?,?)")
            .run(space, `device-${space}@local.invalid`, "device-only", new Date().toISOString());
        }
        const keys = generateKeyPairSync("ec", {
          namedCurve: "prime256v1", publicKeyEncoding: { type: "spki", format: "der" }, privateKeyEncoding: { type: "pkcs8", format: "der" },
        });
        identity = { id: randomUUID(), space_id: space, name, public_key: keys.publicKey.toString("base64"), private_key: keys.privateKey.toString("base64") };
        this.db.prepare("INSERT INTO local_device(singleton,id,space_id,name,public_key,private_key) VALUES(1,?,?,?,?,?)")
          .run(identity.id, identity.space_id, name, identity.public_key, identity.private_key);
      }
      const current = this.lookup(currentToken);
      if (current?.role === "owner" && current.space_id === identity.space_id) return { grant: current, token: currentToken! };
      return this.createGrant(identity.space_id, identity.id, name, platform, "owner");
    });
  }

  private createGrant(space: string, clientId: string, name: string, platform: string, role: "owner" | "peer", rotate = true) {
    const token = randomBytes(32).toString("base64url");
    const now = new Date().toISOString();
    // Explicit re-pairing rotates only that physical client's prior grants.
    if (rotate) this.db.prepare("UPDATE device_grants SET revoked_at=? WHERE space_id=? AND client_id=? AND role=? AND revoked_at IS NULL")
      .run(now, space, clientId, role);
    const grant: DeviceGrant = { id: randomUUID(), space_id: space, client_id: clientId, name, platform, role, created_at: now, last_seen_at: now };
    this.db.prepare("INSERT INTO device_grants(id,space_id,client_id,token_hash,name,platform,role,created_at,last_seen_at) VALUES(?,?,?,?,?,?,?,?,?)")
      .run(grant.id, space, clientId, deviceTokenHash(token), name, platform, role, now, now);
    return { grant, token };
  }

  private lookup(token?: string): DeviceGrant | null {
    if (!token || !/^[A-Za-z0-9_-]{43}$/.test(token)) return null;
    const row = this.db.prepare("SELECT id,space_id,client_id,name,platform,role,created_at,last_seen_at FROM device_grants WHERE token_hash=? AND revoked_at IS NULL")
      .get(deviceTokenHash(token)) as DeviceGrant | undefined;
    return row ? { ...row } : null;
  }

  authenticate(token?: string): DeviceGrant | null {
    const grant = this.lookup(token);
    if (grant && Date.now() - Date.parse(grant.last_seen_at) >= 60_000) {
      this.db.prepare("UPDATE device_grants SET last_seen_at=? WHERE id=?").run(new Date().toISOString(), grant.id);
    }
    return grant;
  }

  assertActive(grant: DeviceGrant) {
    if (!this.db.prepare("SELECT 1 FROM device_grants WHERE id=? AND client_id=? AND space_id=? AND revoked_at IS NULL").get(grant.id, grant.client_id, grant.space_id))
      throw new AppError("UNAUTHORIZED", "设备授权已解除，请重新扫码配对。", 401);
  }

  grantActive(id: string) { return !!this.db.prepare("SELECT 1 FROM device_grants WHERE id=? AND revoked_at IS NULL").get(id); }

  context() {
    const identity = this.identity();
    return { capabilities: { replica: 1, web: false, ai: false }, peer_id: identity.id, space_id: identity.space_id, device: { id: identity.id, name: identity.name, platform: "windows" }, legacy_space_ids: [identity.space_id] };
  }

  proof(challenge: string, clientId: string) {
    const identity = this.identity();
    const content = `${challenge}\n${clientId}\n${identity.id}\n${identity.space_id}`;
    const signature = sign("sha256", Buffer.from(content), { key: Buffer.from(identity.private_key, "base64"), format: "der", type: "pkcs8" }).toString("base64");
    return { ...this.context(), identity: { public_key: identity.public_key, signature } };
  }

  issue(grant: DeviceGrant, now = Date.now()) {
    if (grant.role !== "owner") throw new AppError("OWNER_REQUIRED", "请在本机生成设备配对码。", 403);
    return this.transaction(() => {
      const current = this.db.prepare("SELECT id FROM device_grants WHERE id=? AND role='owner' AND revoked_at IS NULL").get(grant.id);
      if (!current) throw new AppError("UNAUTHORIZED", "本机授权已失效，请重新打开应用。", 401);
      this.db.prepare("DELETE FROM device_pairings WHERE expires_at<=? OR owner_grant_id=?").run(now, grant.id);
      const token = randomBytes(32).toString("base64url");
      this.db.prepare("INSERT INTO device_pairings(token_hash,owner_grant_id,space_id,expires_at) VALUES(?,?,?,?)")
        .run(deviceTokenHash(token), grant.id, grant.space_id, now + 90_000);
      return { token, expires_at: new Date(now + 90_000).toISOString(), ...this.context(), pairing_limited: false, session_expires_at: null };
    });
  }

  redeem(token: string, clientId: string, name: string, platform: string, now = Date.now()) {
    return this.transaction(() => {
      const ticket = this.db.prepare(`SELECT p.space_id FROM device_pairings p JOIN device_grants g ON p.owner_grant_id=g.id
        WHERE p.token_hash=? AND p.expires_at>? AND p.consumed_at IS NULL AND g.revoked_at IS NULL AND g.role='owner'`)
        .get(deviceTokenHash(token), now) as { space_id: string } | undefined;
      if (!ticket) throw new AppError("PAIRING_INVALID", "配对码已过期或已使用，请在显示配对码的设备上重新生成。", 400);
      this.db.prepare("UPDATE device_pairings SET consumed_at=? WHERE token_hash=?").run(now, deviceTokenHash(token));
      return this.createGrant(ticket.space_id, clientId, name, platform, "peer");
    });
  }

  publicKey() { return this.identity().public_key; }

  callbackGrant(clientId: string, name: string, platform: string) {
    return this.transaction(() => this.createGrant(this.identity().space_id, clientId, name, platform, "peer", false));
  }

  finishCallback(grant: DeviceGrant, success: boolean) {
    this.db.prepare(success
      ? "UPDATE device_grants SET revoked_at=? WHERE client_id=? AND role='peer' AND id<>? AND revoked_at IS NULL"
      : "UPDATE device_grants SET revoked_at=? WHERE client_id=? AND role='peer' AND id=? AND revoked_at IS NULL")
      .run(new Date().toISOString(), grant.client_id, grant.id);
  }

  revokeClient(clientId: string) {
    this.db.prepare("UPDATE device_grants SET revoked_at=? WHERE client_id=? AND role='peer' AND revoked_at IS NULL").run(new Date().toISOString(), clientId);
  }
  revokeGrant(id: string) { this.db.prepare("UPDATE device_grants SET revoked_at=? WHERE id=? AND role='peer' AND revoked_at IS NULL").run(new Date().toISOString(), id); }

  list(grant: DeviceGrant) {
    const rows = this.db.prepare(`SELECT id,client_id,name,platform,role,created_at,last_seen_at FROM device_grants
      WHERE space_id=? AND revoked_at IS NULL AND (?='owner' OR id=?) ORDER BY created_at`)
      .all(grant.space_id, grant.role, grant.id) as (DeviceGrant)[];
    return { ...this.context(), devices: rows.map(({ role, ...row }) => ({ ...row, current: row.id === grant.id, local: role === "owner" })) };
  }

  revoke(actor: DeviceGrant, id: string, onRevoke?: (clientId: string) => void) {
    return this.transaction(() => {
      this.assertActive(actor);
      const target = this.db.prepare("SELECT id,role,client_id FROM device_grants WHERE id=? AND space_id=? AND revoked_at IS NULL").get(id, actor.space_id) as { id: string; role: string; client_id: string } | undefined;
      if (!target || (actor.role !== "owner" && id !== actor.id)) throw new AppError("NOT_FOUND", "找不到这台已配对设备。", 404);
      if (target.role === "owner") throw new AppError("LOCAL_DEVICE", "本机日程无需解除配对。", 400);
      this.db.prepare("UPDATE device_grants SET revoked_at=? WHERE id=?").run(new Date().toISOString(), id);
      onRevoke?.(target.client_id);
      return { revoked: true, id, client_id: target.client_id };
    });
  }
}
