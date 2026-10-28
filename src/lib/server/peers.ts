import { randomBytes, verify } from "node:crypto";
import ipaddr from "ipaddr.js";
import { z } from "zod";
import { replicaRecordSchema } from "../replica";
import { localStore, type LocalStore } from "./local-store";
import { AppError } from "./errors";
import type { DeviceGrant } from "./device-store";
import type { PeerConnection } from "./peer-store";

const uuid = z.string().uuid();
export const returnConnectionSchema = z.object({ origin: z.string().max(512), token: z.string().regex(/^[A-Za-z0-9_-]{43}$/), peer_id: uuid, space_id: uuid, public_key: z.string().max(512) }).strict();
export const exchangeSchema = z.object({ device_id: uuid, records: z.array(replicaRecordSchema).max(50), after: uuid.optional() }).strict();
const pageSchema = z.object({ peer_id: uuid, space_id: uuid, records: z.array(replicaRecordSchema).max(50), has_more: z.boolean(), next_cursor: uuid.nullable() });
const proofSchema = z.object({ peer_id: uuid, space_id: uuid, device: z.object({ name: z.string().max(80), platform: z.enum(['android', 'windows', 'web', 'test']).optional() }), identity: z.object({ public_key: z.string().max(512), signature: z.string().max(256) }), capabilities: z.object({ web: z.boolean().optional() }).optional() });
const MAX_CURSOR = "ffffffff-ffff-ffff-ffff-ffffffffffff";

export function peerOrigin(value: string): string {
  try {
    const url = new URL(value);
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.hash || url.search || url.pathname !== '/') throw new Error();
    const host = url.hostname.replace(/^\[|\]$/g, '');
    const range = host === 'localhost' ? 'loopback' : ipaddr.process(host).range();
    if (!['private', 'loopback', 'uniqueLocal'].includes(range)) throw new Error();
    return url.origin;
  } catch { throw new AppError("INVALID_PEER_ADDRESS", "请使用同一局域网内的设备 IP 地址和端口。", 400); }
}

export function parsePeerLink(value: string) {
  try {
    const url = new URL(value.trim());
    const fragment = new URLSearchParams(url.hash.slice(1));
    const token = fragment.get('pair');
    if (!token || !/^[A-Za-z0-9_-]{43}$/.test(token) || [...fragment.keys()].some(key => key !== 'pair')) throw new Error();
    url.hash = '';
    return { origin: peerOrigin(url.href), token };
  } catch (error) { if (error instanceof AppError) throw error; throw new AppError("INVALID_PAIRING_LINK", "请扫描或粘贴带有一次性配对码的完整链接。", 400); }
}

async function request(origin: string, path: string, body?: unknown, token?: string) {
  const response = await fetch(`${peerOrigin(origin)}${path}`, {
    method: body === undefined ? 'GET' : 'POST', redirect: 'error', signal: AbortSignal.timeout(15_000),
    headers: { ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  const reader = response.body?.getReader();
  if (!reader) throw new AppError('PEER_UNAVAILABLE', '对方没有返回有效内容。', 502);
  const chunks: Uint8Array[] = []; let size = 0;
  while (true) {
    const { done, value } = await reader.read(); if (done) break;
    size += value.byteLength;
    if (size > 16 * 1024 * 1024) { await reader.cancel(); throw new AppError('BODY_TOO_LARGE', '对方响应过大，已停止同步。', 413); }
    chunks.push(value);
  }
  let result;
  try { result = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { throw new AppError('PEER_UNAVAILABLE', '对方返回了无效内容。', 502); }
  if (!response.ok) throw new AppError(response.status === 401 ? 'PAIRING_REVOKED' : 'PEER_SYNC_FAILED', response.status === 401 ? '配对已失效，请重新扫码。' : '对方未接受同步，本机日程已保留。', response.status);
  return result;
}

export async function verifyPeer(origin: string, localId: string, expected?: Pick<PeerConnection, 'id' | 'space_id' | 'public_key'>) {
  const challenge = randomBytes(32).toString('base64url');
  const proof = proofSchema.parse(await request(origin, `/api/devices/identity?challenge=${challenge}&device_id=${localId}`));
  let valid = false;
  try { valid = verify('sha256', Buffer.from(`${challenge}\n${localId}\n${proof.peer_id}\n${proof.space_id}`), { key: Buffer.from(proof.identity.public_key, 'base64'), format: 'der', type: 'spki' }, Buffer.from(proof.identity.signature, 'base64')); } catch { /* Reject invalid keys and signatures. */ }
  if (!valid || (expected && (proof.peer_id !== expected.id || proof.space_id !== expected.space_id || proof.identity.public_key !== expected.public_key))) throw new AppError('PEER_IDENTITY_CHANGED', '设备身份与配对记录不符，未发送日程或旧凭据。请重新核对并扫码。', 409);
  return proof;
}

export async function acceptReturnConnection(store: LocalStore, input: z.infer<typeof returnConnectionSchema>, clientId: string, name: string, grantId?: string) {
  const generation = store.peers.generation();
  if (input.peer_id !== clientId || clientId === store.devices.context().peer_id) throw new AppError('INVALID_PEER', '回连设备编号不匹配。', 400);
  const origin = peerOrigin(input.origin);
  await verifyPeer(origin, store.devices.context().peer_id, { id: input.peer_id, space_id: input.space_id, public_key: input.public_key });
  if (store.peers.generation() !== generation || (grantId && !store.devices.grantActive(grantId))) throw new AppError('PAIRING_CANCELLED', '配对已取消，未恢复连接。', 409);
  store.peers.save({ id: clientId, space_id: input.space_id, public_key: input.public_key, name, origin, token: input.token, grant_id: null });
}

export async function connectPeer(store: LocalStore, link: string, localOrigin?: string, ownerGrantId?: string) {
  const generation = store.peers.generation();
  const assertCurrent = () => {
    if (store.peers.generation() !== generation || (ownerGrantId && !store.devices.grantActive(ownerGrantId))) throw new AppError('PAIRING_CANCELLED', '配对已取消，未恢复连接。', 409);
  };
  const { origin, token } = parsePeerLink(link);
  const local = store.devices.context();
  const proof = await verifyPeer(origin, local.peer_id);
  assertCurrent();
  if (proof.peer_id === local.peer_id) throw new AppError('SELF_PAIRING', '这是本机配对码，请在另一台设备扫描。', 400);
  const pinned = store.peers.pin(proof.peer_id);
  if (pinned && pinned !== proof.identity.public_key) throw new AppError('PEER_IDENTITY_CHANGED', '设备身份已改变，请核对目标设备。', 409);
  const callback = localOrigin ? store.devices.callbackGrant(proof.peer_id, proof.device.name, proof.device.platform ?? (proof.capabilities?.web === false ? 'android' : 'windows')) : undefined;
  try {
    const data = await request(origin, '/api/pairing/redeem', {
      token, device_id: local.peer_id, device_name: local.device.name, platform: 'windows',
      ...(callback ? { return_connection: { origin: peerOrigin(localOrigin!), token: callback.token, peer_id: local.peer_id, space_id: local.space_id, public_key: store.devices.publicKey() } } : {}),
    });
    const result = z.object({ peer_id: uuid, space_id: uuid, device_token: z.string().regex(/^[A-Za-z0-9_-]{43}$/), pairing_id: uuid.optional() }).parse(data);
    assertCurrent();
    if (callback && !store.devices.grantActive(callback.grant.id)) throw new AppError('PAIRING_CANCELLED', '配对已解除，未恢复连接。', 409);
    if (result.peer_id !== proof.peer_id || result.space_id !== proof.space_id) throw new AppError('PEER_IDENTITY_CHANGED', '配对过程中设备身份改变，未保存凭据。', 409);
    store.peers.save({ id: proof.peer_id, space_id: proof.space_id, name: proof.device.name, origin, public_key: proof.identity.public_key, token: result.device_token, grant_id: result.pairing_id ?? null });
    if (callback) store.devices.finishCallback(callback.grant, true);
  } catch (error) { if (callback) store.devices.finishCallback(callback.grant, false); throw error; }
}

export async function syncPeer(store: LocalStore, peer: PeerConnection) {
  const local = store.devices.context();
  await verifyPeer(peer.origin, local.peer_id, peer);
  let pushCursor: string | undefined; let pullCursor: string | undefined; let pushDone = false; let pullDone = false;
  const started = Date.now();
  for (let pages = 0; pages < 10000 && Date.now() - started < 120_000; pages++) {
    if (!store.peers.active(peer.id)) return;
    const push: ReturnType<LocalStore['replicaPage']> = pushDone ? { records: [], has_more: false, next_cursor: null } : store.replicaPage(local.space_id, pushCursor);
    const reply = pageSchema.parse(await request(peer.origin, '/api/replica/exchange', { device_id: local.peer_id, records: push.records, ...(pullCursor ? { after: pullCursor } : {}) }, peer.token));
    if (reply.peer_id !== peer.id || reply.space_id !== peer.space_id) throw new AppError('PEER_IDENTITY_CHANGED', '对方日程身份改变，已保留本机内容。', 409);
    if (!store.peers.active(peer.id)) return;
    store.exchange(local.space_id, reply.records, MAX_CURSOR);
    pushDone = !push.has_more; if (push.has_more) pushCursor = push.next_cursor!;
    if (!pullDone && reply.has_more && (!reply.next_cursor || (pullCursor && reply.next_cursor <= pullCursor))) throw new AppError('INVALID_CURSOR', '对方同步游标无效，已停止本轮同步。', 409);
    pullDone ||= !reply.has_more; pullCursor = pullDone ? MAX_CURSOR : reply.next_cursor!;
    if (pushDone && pullDone) { store.peers.synced(peer.id); return; }
  }
  throw new AppError('SYNC_INCOMPLETE', '本轮同步尚未完成，稍后会从保留的数据继续。', 408);
}

type Worker = { timer?: NodeJS.Timeout; running: boolean; connection: { state: 'idle' | 'connecting' | 'failed' | 'connected'; error?: string } };
const globals = globalThis as unknown as { schedulePeerWorker?: Worker };
function worker() { return globals.schedulePeerWorker ??= { running: false, connection: { state: 'idle' } }; }
const errorMessage = (error: unknown) => error instanceof AppError ? error.message : '暂时连不上对方，本机日程已保存；连接恢复后自动重试。';
async function syncAll() {
  const state = worker(); if (state.running) return;
  state.running = true;
  try {
    const store = localStore(); if (!store.devices.ready()) return;
    // Separate peer errors: an unavailable device must not prevent others syncing.
    const peers = store.peers.all();
    await Promise.all(Array.from({ length: Math.min(4, peers.length) }, async () => {
      while (peers.length) {
        const peer = peers.shift()!;
        try { await syncPeer(store, peer); } catch (error) { store.peers.failed(peer.id, errorMessage(error)); }
      }
    }));
  } catch { /* Per-peer/status requests expose recoverable storage failures. */ }
  finally { state.running = false; }
}
export function startPeerWorker() { const state = worker(); state.timer ??= setInterval(() => { void syncAll(); }, 15_000).unref(); }
export function queueSync() { startPeerWorker(); void syncAll(); return { queued: true }; }
export function queueConnect(link: string, localOrigin?: string, ownerGrantId?: string) {
  parsePeerLink(link); if (localOrigin) peerOrigin(localOrigin);
  const state = worker(); if (state.connection.state === 'connecting') throw new AppError('PAIRING_BUSY', '正在配对，请稍候。', 409);
  state.connection = { state: 'connecting' };
  void connectPeer(localStore(), link, localOrigin, ownerGrantId).then(() => { state.connection = { state: 'connected' }; queueSync(); }).catch(error => { state.connection = { state: 'failed', error: errorMessage(error) }; });
  return { queued: true };
}
export function connectionStatus() { return worker().connection; }
export function requireOwner(grant?: DeviceGrant) { if (grant?.role !== 'owner') throw new AppError('OWNER_REQUIRED', '请在本机管理配对设备。', 403); }
export async function removePeer(id: string) {
  const store = localStore(); const peer = store.peers.all().find(item => item.id === id);
  store.disconnectPeer(id);
  if (peer) {
    // Stop future reciprocal access where reachable. Revocation cannot recall
    // any copies already received by this peer or peers it has paired with.
    void (async () => {
      try {
        await verifyPeer(peer.origin, store.devices.context().peer_id, peer);
        const session = z.object({ peer: z.object({ id: uuid }) }).parse(await request(peer.origin, '/api/devices/info', undefined, peer.token));
        await fetch(`${peer.origin}/api/devices/${session.peer.id}`, { method: 'DELETE', headers: { Authorization: `Bearer ${peer.token}`, 'Content-Type': 'application/json' }, body: '{}', redirect: 'error', signal: AbortSignal.timeout(10_000) });
      } catch { /* Our local inbound and outbound permissions are already revoked. */ }
    })();
  }
  return { revoked: true, id };
}
