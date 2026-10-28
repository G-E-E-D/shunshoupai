import { expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { createRevision, type ReplicaRecord } from '../../src/lib/replica';
import { bootstrapDevice, resetTestDevice } from './device-fixtures';

test.beforeEach(async ({ request }) => { await resetTestDevice(request); });

test('replica API binds physical identity, preserves concurrent delete/edit, and requires a current explicit resolution', async ({ request, playwright, baseURL }) => {
  const owner = await bootstrapDevice(request);
  const native = await playwright.request.newContext({ baseURL });
  const clientId = randomUUID(); const id = randomUUID();
  try {
    const ticket = await (await request.post('/api/pairing/issue', { data: {} })).json();
    const paired = await native.post('/api/pairing/redeem', { data: { token: ticket.token, device_id: clientId, device_name: '离线手机', platform: 'android' } });
    expect(paired.status()).toBe(200);
    const credential = await paired.json();
    const root = createRevision({ baseHeads: [], revisionId: randomUUID(), author: '手机', changedAt: 1,
      payload: { title: '手机独立创建', start_local: '2033-01-01T09:00:00', end_local: '2033-01-01T10:00:00', timezone: 'Asia/Shanghai', status: 'planned', deleted: false } });
    const record: ReplicaRecord = { id, heads: [root] };
    const exchange = (records: ReplicaRecord[], device_id = clientId) => native.post('/api/replica/exchange', { data: { device_id, records } });
    expect((await exchange([record], randomUUID())).status()).toBe(403);
    expect((await request.get(`/api/events/${id}`)).status()).toBe(404);
    expect((await exchange([record])).status()).toBe(200);
    let event = (await (await request.get(`/api/events/${id}`)).json()).event;
    expect(event.title).toBe(root.payload.title);
    expect((await request.patch(`/api/events/${id}`, { data: { base_version: event.version, title: '电脑离线编辑' } })).status()).toBe(200);
    const deletion = createRevision({ baseHeads: [root], revisionId: randomUUID(), author: '手机', changedAt: 0, payload: { ...root.payload, deleted: true } });
    const response = await exchange([{ id, heads: [deletion] }]); expect(response.status()).toBe(200);
    const result = await response.json(); expect(result.peer_id).toBe(owner.peer_id); expect(result.space_id).toBe(owner.space_id);
    event = (await (await request.get(`/api/events/${id}`)).json()).event;
    expect(event.deleted_at).toBeNull(); expect(event.replica_conflicts).toHaveLength(2);
    expect((await request.delete(`/api/events/${id}`, { data: { base_version: event.version } })).status()).toBe(409);
    expect((await request.post(`/api/events/${id}/resolve`, { data: { base_version: event.version - 1, revision_id: deletion.revision_id } })).status()).toBe(409);
    expect((await request.post(`/api/events/${id}/resolve`, { data: { base_version: event.version, revision_id: deletion.revision_id } })).status()).toBe(200);
    const resolved = (await (await request.get(`/api/events/${id}`)).json()).event;
    expect(resolved.deleted_at).toBeTruthy(); expect(resolved.replica_conflicts).toBeUndefined();
    expect((await exchange([record])).status()).toBe(200);
    expect((await (await request.get(`/api/events/${id}`)).json()).event.version).toBe(resolved.version);
    expect((await native.post('/api/peers/connect', { data: { url: `${baseURL}/#pair=${ticket.token}` } })).status()).toBe(403);
    expect((await request.delete(`/api/devices/${credential.pairing_id}`)).status()).toBe(200);
    expect((await exchange([record])).status()).toBe(401);
  } finally { await native.dispose(); }
});

test('pair management validates LAN roots before queueing and device lists never expose credentials', async ({ request }) => {
  await bootstrapDevice(request);
  const response = await request.post('/api/peers/connect', { data: { url: `https://example.com/#pair=${'a'.repeat(43)}` } });
  expect(response.status()).toBe(400); expect((await response.json()).error.code).toBe('INVALID_PEER_ADDRESS');
  const devices = await (await request.get('/api/devices')).json();
  expect(devices.connection.state).toBe('idle'); expect(devices.peers).toEqual([]);
  for (const device of devices.devices) { expect(device.client_id).toBeTruthy(); expect(device.token).toBeUndefined(); expect(device.token_hash).toBeUndefined(); }
  expect((await request.post('/api/peers/sync', { data: {} })).status()).toBe(202);
});
