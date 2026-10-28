import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { test } from 'node:test';
import { LocalStore } from '../src/lib/server/local-store';
import { AppError } from '../src/lib/server/errors';
import { createRevision } from '../src/lib/replica';

const draft = { title: '原日程', start_local: '2031-01-01T09:00:00', end_local: '2031-01-01T10:00:00', timezone: 'Asia/Shanghai' as const, time_source: 'explicit' as const, assumptions: [] };
function device(name: string) { const store = new LocalStore(':memory:'); const owner = store.devices.bootstrap(name, 'test'); return { store, space: owner.grant.space_id }; }
function send(a: ReturnType<typeof device>, b: ReturnType<typeof device>) { b.store.exchange(b.space, a.store.replicaPage(a.space).records); }
const code = (value: string) => (error: unknown) => error instanceof AppError && error.code === value;

test('three independent calendars converge through a triangle, preserving deletes and resolving concurrent edits explicitly', () => {
  const a = device('电脑A'), b = device('手机B'), c = device('手机C');
  try {
    const event = a.store.commit(a.space, randomUUID(), [draft]).events[0];
    send(a,b); send(b,c);
    b.store.mutate(b.space,event.id,b.store.event(b.space,event.id).version,{title:'B修改'});
    c.store.mutate(c.space,event.id,c.store.event(c.space,event.id).version,{},true);
    send(b,a); send(c,a); send(a,b); send(b,c); send(c,a);
    const versions = [a,b,c].map(d => d.store.event(d.space,event.id));
    for (const item of versions) { assert.equal(item.replica_conflicts?.length,2); assert.equal(item.deleted_at,null); }
    const before = a.store.replicaPage(a.space).records;
    for(let i=0;i<3;i++){send(a,b);send(b,c);send(c,a);}
    assert.deepEqual(a.store.replicaPage(a.space).records,before);
    assert.equal(a.store.event(a.space,event.id).version,versions[0].version);
    assert.throws(()=>a.store.mutate(a.space,event.id,versions[0].version,{title:'不可覆盖'}),code('REPLICA_CONFLICT'));
    const selected=versions[0].replica_conflicts!.find(h=>h.payload.deleted)!;
    a.store.resolveReplica(a.space,event.id,versions[0].version,selected.revision_id);
    send(a,b);send(b,c);send(c,a);
    for(const d of[a,b,c]) { assert.ok(d.store.event(d.space,event.id).deleted_at); assert.equal(d.store.event(d.space,event.id).replica_conflicts,undefined); }
    assert.deepEqual(a.store.replicaPage(a.space).records,c.store.replicaPage(c.space).records);
  } finally { for(const d of[a,b,c])d.store.close(); }
});

test('stale conflict choices cannot absorb a new unseen revision; restored backups form concurrent branches',()=>{
  const a=device('A'), b=device('B');
  try {
    const e=a.store.commit(a.space,randomUUID(),[draft]).events[0];
    const backup=a.store.replicaPage(a.space).records[0];send(a,b);
    a.store.mutate(a.space,e.id,e.version,{title:'新内容'});send(a,b);
    const seen=b.store.event(b.space,e.id);
    const restored=createRevision({baseHeads:backup.heads,revisionId:randomUUID(),payload:{...backup.heads[0].payload,title:'备份后修改'},author:'旧备份',changedAt:1});
    b.store.exchange(b.space,[{id:e.id,heads:[restored]}]);
    assert.throws(()=>b.store.resolveReplica(b.space,e.id,seen.version,backup.heads[0].revision_id),code('VERSION_CONFLICT'));
    assert.equal(b.store.event(b.space,e.id).replica_conflicts?.length,2);
    assert.throws(()=>b.store.mutate(b.space,e.id,seen.version,{title:'过期编辑'}),code('REPLICA_CONFLICT'));
  }finally{a.store.close();b.store.close();}
});

test('historical revision receipts reject tampering after pruning and roll back a whole exchange',()=>{
  const a=device('A');try{
    const e=a.store.commit(a.space,randomUUID(),[draft]).events[0];
    const old=a.store.replicaPage(a.space).records[0];
    a.store.mutate(a.space,e.id,e.version,{title:'后续修改'});
    const injected={id:randomUUID(),heads:[createRevision({baseHeads:[],revisionId:randomUUID(),payload:old.heads[0].payload,author:'B',changedAt:1})]};
    const before=a.store.replicaPage(a.space).records;
    const tampered=structuredClone(old);tampered.heads[0].author='篡改';
    assert.throws(()=>a.store.exchange(a.space,[injected,tampered]),code('REVISION_COLLISION'));
    assert.deepEqual(a.store.replicaPage(a.space).records,before);
    assert.throws(()=>a.store.event(a.space,injected.id),code('NOT_FOUND'));
  }finally{a.store.close();}
});

test('identical independent migration roots keep causality without a false visible conflict',()=>{
  const a=device('A');try{
    const e=a.store.commit(a.space,randomUUID(),[draft]).events[0];
    const record=a.store.replicaPage(a.space).records[0];
    a.store.exchange(a.space,[{id:e.id,heads:[createRevision({baseHeads:[],revisionId:randomUUID(),payload:record.heads[0].payload,author:'旧手机',changedAt:0})]}]);
    assert.equal(a.store.replicaPage(a.space).records[0].heads.length,2);
    assert.equal(a.store.event(a.space,e.id).replica_conflicts,undefined);
    a.store.mutate(a.space,e.id,a.store.event(a.space,e.id).version,{title:'共同后续'});
    assert.equal(a.store.replicaPage(a.space).records[0].heads[0].ancestors.length,2);
  }finally{a.store.close();}
});

test('paged export does not imply deletion and preserves overlapping offline events',()=>{
  const a=device('A'),b=device('B');try{
    const template=a.store.commit(a.space,randomUUID(),[draft]).events[0];
    const payload=a.store.replicaPage(a.space).records[0].heads[0].payload;
    for(let i=0;i<120;i++) a.store.exchange(a.space,[{id:randomUUID(),heads:[createRevision({baseHeads:[],revisionId:randomUUID(),payload:{...payload,title:`离线${i}`},author:'B',changedAt:i})]}],'ffffffff-ffff-ffff-ffff-ffffffffffff');
    let after: string|undefined; let count=0;
    do { const page=a.store.replicaPage(a.space,after);b.store.exchange(b.space,page.records);count+=page.records.length;after=page.has_more?page.next_cursor!:undefined; } while(after);
    assert.equal(count,121);assert.equal(b.store.event(b.space,template.id).title,draft.title);
    assert.equal(b.store.list(b.space,'2031-01-01T00:00:00Z','2031-01-02T00:00:00Z').length,121);
  }finally{a.store.close();b.store.close();}
});

test('late ancestor contents cannot cross events or contradict an already received descendant',()=>{
  const a=device('A');try{
    const e=a.store.commit(a.space,randomUUID(),[draft]).events[0];
    const original=a.store.replicaPage(a.space).records[0].heads[0];
    const missing=randomUUID(), hidden=randomUUID();
    const child=createRevision({baseHeads:[original],revisionId:randomUUID(),payload:original.payload,author:'B',changedAt:1});
    child.ancestors.push(missing);child.ancestors.sort();
    a.store.exchange(a.space,[{id:e.id,heads:[child]}]);
    const late={...original,revision_id:missing,ancestors:[]};
    assert.throws(()=>a.store.exchange(a.space,[{id:randomUUID(),heads:[late]}]),code('INVALID_ANCESTRY'));
    assert.throws(()=>a.store.exchange(a.space,[{id:e.id,heads:[{...late,ancestors:[hidden]}]}]),(error:unknown)=>error instanceof Error && 'code' in error && error.code === 'INVALID_ANCESTRY');
    a.store.exchange(a.space,[{id:e.id,heads:[late]}]);
    assert.deepEqual(a.store.replicaPage(a.space).records[0].heads,[child]);
  }finally{a.store.close();}
});
