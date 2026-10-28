import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { test } from 'node:test';
import { LocalStore } from '../src/lib/server/local-store';
import { acceptReturnConnection, connectPeer, exchangeSchema, parsePeerLink, peerOrigin, syncPeer } from '../src/lib/server/peers';
import { AppError } from '../src/lib/server/errors';

process.env.MODEL_SETTINGS_ENCRYPTION_KEY = Buffer.alloc(32, 19).toString('base64');
const draft = { title: '本机安排', start_local: '2032-01-01T09:00:00', end_local: '2032-01-01T10:00:00', timezone: 'Asia/Shanghai' as const, time_source: 'explicit' as const, assumptions: [] };
async function host(name: string) {
  const store = new LocalStore(':memory:'); const owner = store.devices.bootstrap(name,'test');
  let receivedCredentials = 0;
  let beforeRedeemResponse = async () => {};
  const server = createServer(async(req,res)=>{
    try {
      const url=new URL(req.url!, 'http://localhost');
      let body='';for await(const chunk of req)body+=chunk;
      if(req.headers.authorization)receivedCredentials++;
      let result;
      if(url.pathname==='/api/devices/identity')result=store.devices.proof(url.searchParams.get('challenge')!,url.searchParams.get('device_id')!);
      else if(url.pathname==='/api/pairing/redeem'){
        const input=JSON.parse(body); const granted=store.devices.redeem(input.token,input.device_id,input.device_name,input.platform);
        if(input.return_connection)await acceptReturnConnection(store,input.return_connection,input.device_id,input.device_name);
        result={...store.devices.context(),device_token:granted.token,pairing_id:granted.grant.id};
        await beforeRedeemResponse();
      }else if(url.pathname==='/api/replica/exchange'){
        const grant=store.devices.authenticate(req.headers.authorization?.slice(7));
        if(!grant)throw new AppError('UNAUTHORIZED','已撤销',401);
        const input=exchangeSchema.parse(JSON.parse(body));
        if(input.device_id!==grant.client_id)throw new AppError('DEVICE_MISMATCH','设备不符',403);
        result={...store.devices.context(),...store.exchange(grant.space_id,input.records,input.after)};
      }else throw new AppError('NOT_FOUND','不存在',404);
      res.setHeader('Content-Type','application/json');res.end(JSON.stringify(result));
    }catch(e){res.statusCode=e instanceof AppError?e.status:500;res.end(JSON.stringify({error:{code:e instanceof AppError?e.code:'INTERNAL_ERROR'}}));}
  });
  await new Promise<void>(resolve=>server.listen(0,'127.0.0.1',resolve));
  const origin=`http://127.0.0.1:${(server.address() as {port:number}).port}`;
  return {store,owner,origin,space:owner.grant.space_id,credentials:()=>receivedCredentials,delayRedeem:(callback:()=>Promise<void>)=>{beforeRedeemResponse=callback;},close:async()=>{await new Promise<void>(resolve=>server.close(()=>resolve()));store.close();}};
}

test('LAN peer URLs reject credentials, public addresses, query paths, fragments and malformed pairing tickets',()=>{
  assert.equal(peerOrigin('http://192.168.1.5:43127'),'http://192.168.1.5:43127');
  for(const value of ['https://example.com','http://8.8.8.8','http://169.254.169.254','http://10.0.0.1/api','http://user:pass@10.0.0.1','http://10.0.0.1?token=a','file:///C:/test']) assert.throws(()=>peerOrigin(value));
  assert.throws(()=>parsePeerLink('http://10.0.0.1/#pair=short'));
  assert.throws(()=>parsePeerLink(`http://10.0.0.1/#pair=${'a'.repeat(43)}&unexpected=1`));
});

test('real HTTP reciprocal pairing merges two primary calendars and both can initiate sync after disconnect',async()=>{
  const a=await host('电脑'),b=await host('手机');
  try{
    const ea=a.store.commit(a.space,randomUUID(),[draft]).events[0];
    const eb=b.store.commit(b.space,randomUUID(),[{...draft,title:'离线手机安排'}]).events[0];
    const qr=a.store.devices.issue(a.owner.grant);
    await connectPeer(b.store,`${a.origin}/#pair=${qr.token}`,b.origin);
    assert.equal(a.store.peers.list().length,1);assert.equal(b.store.peers.list().length,1);
    await syncPeer(b.store,b.store.peers.all()[0]);
    assert.equal(a.store.event(a.space,eb.id).title,'离线手机安排');assert.equal(b.store.event(b.space,ea.id).title,draft.title);
    a.store.mutate(a.space,ea.id,a.store.event(a.space,ea.id).version,{title:'电脑新修改'});
    await syncPeer(a.store,a.store.peers.all()[0]);
    assert.equal(b.store.event(b.space,ea.id).title,'电脑新修改');
    const before=b.store.event(b.space,ea.id).version;
    await syncPeer(b.store,b.store.peers.all()[0]);assert.equal(b.store.event(b.space,ea.id).version,before);
    a.store.devices.revokeClient(b.store.devices.context().peer_id);
    await assert.rejects(syncPeer(b.store,b.store.peers.all()[0]),(e:unknown)=>e instanceof AppError&&e.code==='PAIRING_REVOKED');
    assert.equal(b.store.event(b.space,ea.id).title,'电脑新修改');
  }finally{await a.close();await b.close();}
});

test('identity/address substitution never receives an existing bearer or schedule payload',async()=>{
  const a=await host('A'),b=await host('B'),imposter=await host('无关设备');
  try{
    await connectPeer(b.store,`${a.origin}/#pair=${a.store.devices.issue(a.owner.grant).token}`,b.origin);
    const old=b.store.peers.all()[0];
    await assert.rejects(syncPeer(b.store,{...old,origin:imposter.origin}),(e:unknown)=>e instanceof AppError&&e.code==='PEER_IDENTITY_CHANGED');
    assert.equal(imposter.credentials(),0);
    assert.equal(b.store.peers.all()[0].origin,a.origin);
  }finally{await a.close();await b.close();await imposter.close();}
});

test('unpairing wins over a delayed pairing response and never re-enables the local connection',async()=>{
  const a=await host('A'),b=await host('B');
  let release!:()=>void; const held=new Promise<void>(resolve=>{release=resolve;});
  let arrived!:()=>void; const waiting=new Promise<void>(resolve=>{arrived=resolve;});
  a.delayRedeem(async()=>{arrived();await held;});
  try{
    const pairing=connectPeer(b.store,`${a.origin}/#pair=${a.store.devices.issue(a.owner.grant).token}`,b.origin);
    await waiting;
    const remote=a.store.devices.context().peer_id;
    b.store.devices.revokeClient(remote);b.store.peers.disable(remote);
    release();
    await assert.rejects(pairing,(error:unknown)=>error instanceof AppError&&error.code==='PAIRING_CANCELLED');
    assert.equal(b.store.peers.list().length,0);
    assert.ok(!b.store.devices.list(b.owner.grant).devices.some(device=>device.client_id===remote));
  }finally{release();await a.close();await b.close();}
});
