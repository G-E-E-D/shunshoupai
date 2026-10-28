"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { Link2, LoaderCircle, Monitor, RefreshCw, Smartphone, Unplug } from "lucide-react";
import { api, errorMessage, localInput } from "@/lib/client";

type PairedDevice = {
  id: string; client_id?: string; name: string; platform: string;
  created_at: string; last_seen_at: string | null; current: boolean; local: boolean;
};
type Peer = { id: string; name: string; origin: string; connected: boolean; last_sync_at: string | null; error: string | null };
type Connection = { state: "idle" | "connecting" | "failed" | "connected"; error?: string };
type DeviceList = { devices: PairedDevice[]; peers?: Peer[]; connection?: Connection };

export default function DeviceConnections({ role, onDisconnected }: {
  role?: "owner" | "peer";
  onDisconnected: () => void;
}) {
  const [devices, setDevices] = useState<PairedDevice[]>([]);
  const [peers, setPeers] = useState<Peer[]>([]);
  const [connection, setConnection] = useState<Connection>({ state: "idle" });
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [url, setUrl] = useState("");
  const [origins, setOrigins] = useState<string[]>([]);
  const [localOrigin, setLocalOrigin] = useState("");
  const active = useRef(true);
  const action = useRef(false);
  const sequence = useRef(0);
  const pendingLoad = useRef(false);

  const load = useCallback(async (quiet = false) => {
    if (quiet && pendingLoad.current) return;
    const current = ++sequence.current;
    pendingLoad.current = true;
    try {
      const result = await api<DeviceList>("/api/devices");
      if (!active.current || current !== sequence.current) return;
      setDevices(result.devices); setPeers(result.peers ?? []);
      setConnection(result.connection ?? { state: "idle" });
      if (!quiet) setError("");
    } catch (failure) {
      if (active.current && current === sequence.current && !quiet) setError(errorMessage(failure));
    } finally {
      if (current === sequence.current) {
        pendingLoad.current = false;
        if (active.current) setLoading(false);
      }
    }
  }, []);
  useEffect(() => {
    active.current = true;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- Read device connections after mounting; polling preserves the pairing form.
    void load();
    if (role === "owner") void api<{ candidates: string[] }>("/api/connection-info").then(result => {
      if (active.current) { setOrigins(result.candidates); setLocalOrigin(result.candidates[0] ?? ""); }
    }).catch(() => { /* The service can still select its available local address. */ });
    const timer = setInterval(() => { if (document.visibilityState === "visible" && !action.current) void load(true); }, 5000);
    return () => { active.current = false; clearInterval(timer); };
  }, [load, role]);

  async function connect(event: FormEvent) {
    event.preventDefault();
    if (action.current || role !== "owner" || !url.trim()) return;
    action.current = true; setBusy("connect"); setError(""); setMessage("");
    try {
      await api("/api/peers/connect", { method: "POST", body: JSON.stringify({ url: url.trim(), ...(localOrigin ? { local_origin: localOrigin } : {}) }) });
      setUrl("");
      setMessage("正在连接设备并合并日程，你可以继续使用。手机作为接收方时请保持应用在前台。");
      await load();
    } catch (failure) { setError(errorMessage(failure)); }
    finally { action.current = false; setBusy(null); }
  }

  async function sync() {
    if (action.current) return;
    action.current = true; setBusy("sync"); setError("");
    try {
      await api("/api/peers/sync", { method: "POST", body: "{}" });
      setMessage("已安排同步，设备可达时会合并修改。");
      await load();
    } catch (failure) { setError(errorMessage(failure)); }
    finally { action.current = false; setBusy(null); }
  }

  async function disconnect(id: string, name: string, peer: boolean, current = false) {
    if (action.current) return;
    action.current = true; setBusy(id); setError("");
    try {
      await api(`/api/${peer ? "peers" : "devices"}/${encodeURIComponent(id)}`, { method: "DELETE", body: "{}" });
      if (current) { onDisconnected(); return; }
      setConfirm(null);
      setMessage(`已解除与「${name}」的连接，已传播的日程副本仍保留。`);
      await load();
    } catch (failure) { setError(errorMessage(failure)); }
    finally { action.current = false; setBusy(null); }
  }

  function disconnectAction(id: string, name: string, peer: boolean, current = false) {
    const key = `${peer ? "peer" : "device"}:${id}`;
    return confirm === key ? <div className="device-disconnect-confirm">
      <p>解除后停止与这台设备连接。两边及其他设备已经获得的日程副本不会被撤回。</p>
      <div className="modal-actions">
        <button className="secondary-button" disabled={!!busy} onClick={() => setConfirm(null)}>保持连接</button>
        <button className="secondary-button danger-text" disabled={!!busy} onClick={() => void disconnect(id, name, peer, current)}>{busy === id ? "正在解除…" : "确认解除配对"}</button>
      </div>
    </div> : <button className="text-button danger-text" disabled={!!busy} aria-label={`解除配对：${name}`} onClick={() => { setConfirm(key); setMessage(""); }}><Unplug size={14} /> 解除配对</button>;
  }

  const linkedIds = new Set(peers.map(peer => peer.id));
  return (
    <section aria-label="已配对设备" className="device-connections">
      <div className="device-connections-heading">
        <h3>设备连接</h3>
        <button className="icon-button" aria-label="刷新设备列表" disabled={loading || !!busy} onClick={() => void load()}><RefreshCw size={16} /></button>
      </div>
      {role === "owner" && <form className="form-stack peer-connect-form" onSubmit={connect}>
        <label>另一台设备的配对链接<input type="url" autoComplete="off" spellCheck={false} maxLength={2048} value={url} onChange={event => setUrl(event.target.value)} placeholder="http://设备地址:端口/#pair=…" required disabled={!!busy} /></label>
        {origins.length > 1 && <label>本机供对方回连的地址<select value={localOrigin} onChange={event => setLocalOrigin(event.target.value)} disabled={!!busy}>{origins.map(origin => <option value={origin} key={origin}>{origin}</option>)}</select></label>}
        <p className="inline-info pairing-consent">配对会共享本机全部日程及未来修改，也可能经对方传到它已配对的其他设备。解除配对无法撤回已有副本。模型密钥不会随日程传播。</p>
        <button className="primary-button" disabled={!!busy || !url.trim() || connection.state === "connecting"}>{busy === "connect" ? <LoaderCircle size={16} className="spin" /> : <Link2 size={16} />}同意并连接设备</button>
      </form>}
      {connection.state === "connecting" && <p className="inline-info" role="status">正在连接设备…手机作为接收方时请保持应用在前台。</p>}
      {connection.state === "failed" && <p className="inline-error" role="alert">{connection.error || "暂时无法连接，请检查局域网并重新获取配对码。"}</p>}
      {loading ? <p><LoaderCircle size={16} className="spin" /> 正在读取设备…</p> : <>
        {peers.map(peer => <div key={`peer:${peer.id}`} data-peer-id={peer.id} className="device-connection-row">
          <div className="device-connection-name"><Link2 size={18} /><div><strong>{peer.name}</strong><small>{peer.connected ? "已配对，可互相发起同步" : "当前未连接"}</small><small>{peer.origin}</small>{peer.last_sync_at && <small>上次同步 {localInput(peer.last_sync_at).replace("T", " ")}</small>}</div></div>
          {peer.error && <p className="device-peer-error">{peer.error}</p>}
          {disconnectAction(peer.id, peer.name, true)}
        </div>)}
        {devices.filter(device => !device.client_id || !linkedIds.has(device.client_id)).map(device => <div key={device.id} data-device-id={device.id} className="device-connection-row">
          <div className="device-connection-name">{device.local ? <Monitor size={18} /> : <Smartphone size={18} />}<div><strong>{device.name}</strong><small>{device.local ? "本机日程" : device.current ? "当前访问设备" : "已授权访问"}</small></div></div>
          {!device.local && (role === "owner" || device.current) && disconnectAction(device.id, device.name, false, device.current)}
        </div>)}
      </>}
      {!loading && !peers.length && devices.every(device => device.local) && <p className="settings-note">还没有连接其他设备。电脑和手机都能显示配对码，也都能连接另一台设备。</p>}
      {role === "owner" && peers.length > 0 && <button className="secondary-button wide" disabled={!!busy} onClick={() => void sync()}><RefreshCw size={16} />立即同步已配对设备</button>}
      <p className="settings-note">手机只有应用在前台时提供接收同步服务；回到应用后会自动继续。局域网身份校验不提供 HTTP 通道加密。</p>
      {error && <div className="inline-error" role="alert">{error}</div>}
      {message && <div className="inline-info" role="status">{message}</div>}
    </section>
  );
}
