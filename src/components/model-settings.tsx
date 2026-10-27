"use client";

import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
} from "react";
import {
  Check,
  FlaskConical,
  KeyRound,
  LoaderCircle,
  RefreshCw,
  ShieldCheck,
  Trash2,
} from "lucide-react";
import { api, ApiError, errorMessage } from "@/lib/client";
import type { ModelSettingsInfo } from "@/lib/types";

export default function ModelSettingsPanel({
  onChanged,
}: {
  onChanged: (settings: ModelSettingsInfo) => void;
}) {
  const [settings, setSettings] = useState<ModelSettingsInfo | null>(null);
  const [baseUrl, setBaseUrl] = useState("");
  const [model, setModel] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<"save" | "test" | "remove" | null>(null);
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [conflict, setConflict] = useState(false);
  const [confirmRemove, setConfirmRemove] = useState(false);
  const active = useRef(true);
  const action = useRef(false);
  const currentVersion = useRef<number | null>(null);
  const sequence = useRef(0);
  const dirtyRef = useRef(false);
  const changedCallback = useRef(onChanged);
  useEffect(() => {
    changedCallback.current = onChanged;
  }, [onChanged]);

  const apply = useCallback((next: ModelSettingsInfo) => {
    setSettings(next);
    setBaseUrl(next.base_url);
    setModel(next.model);
    setApiKey("");
    setDirty(false);
    dirtyRef.current = false;
    currentVersion.current = next.version;
    setConflict(false);
    setConfirmRemove(false);
    changedCallback.current(next);
  }, []);

  const load = useCallback(
    async (force = false) => {
      if (action.current) return;
      const requestNumber = ++sequence.current;
      try {
        const { settings: next } = await api<{ settings: ModelSettingsInfo }>(
          "/api/model-settings",
        );
        if (!active.current || requestNumber !== sequence.current) return;
        if (force || !dirtyRef.current) {
          apply(next);
          setError("");
        } else if (currentVersion.current !== next.version) setConflict(true);
      } catch (error) {
        if (active.current && requestNumber === sequence.current)
          setError(errorMessage(error));
      } finally {
        if (active.current && requestNumber === sequence.current)
          setLoading(false);
      }
    },
    [apply],
  );

  useEffect(() => {
    active.current = true;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- Fetch the authenticated account's settings after mounting.
    void load();
    const refocus = () => {
      if (document.visibilityState === "visible") void load();
    };
    const timer = setInterval(refocus, 8000);
    window.addEventListener("focus", refocus);
    document.addEventListener("visibilitychange", refocus);
    return () => {
      active.current = false;
      clearInterval(timer);
      window.removeEventListener("focus", refocus);
      document.removeEventListener("visibilitychange", refocus);
    };
  }, [load]);

  function edited() {
    dirtyRef.current = true;
    setDirty(true);
    setError("");
    setMessage("");
    setConfirmRemove(false);
  }
  function payload() {
    return {
      base_url: baseUrl.trim(),
      model: model.trim(),
      ...(apiKey.trim() ? { api_key: apiKey.trim() } : {}),
    };
  }

  async function testConnection() {
    if (action.current || !settings) return;
    if (
      !baseUrl.trim() ||
      !model.trim() ||
      (!apiKey.trim() && !settings.has_api_key)
    ) {
      setError("请填写 Base URL、模型名称和 API Key，再测试连接。");
      return;
    }
    action.current = true;
    sequence.current++;
    setBusy("test");
    setError("");
    setMessage("");
    try {
      const result = await api<{ success: boolean; message: string }>(
        "/api/model-settings/test",
        { method: "POST", body: JSON.stringify(payload()) },
      );
      if (active.current)
        setMessage(
          `${result.message} 测试不会保存配置；修改后请点击「保存配置」。`,
        );
    } catch (error) {
      if (active.current) setError(errorMessage(error));
    } finally {
      action.current = false;
      if (active.current) setBusy(null);
    }
  }

  async function save(e: FormEvent) {
    e.preventDefault();
    if (action.current || !settings || conflict) return;
    if (!apiKey.trim() && !settings.has_api_key) {
      setError("首次接入请填写 API Key。");
      return;
    }
    action.current = true;
    sequence.current++;
    setBusy("save");
    setError("");
    setMessage("");
    try {
      const { settings: next } = await api<{ settings: ModelSettingsInfo }>(
        "/api/model-settings",
        {
          method: "PUT",
          body: JSON.stringify({
            ...payload(),
            base_version: settings.version,
          }),
        },
      );
      if (!active.current) return;
      apply(next);
      setMessage(
        "个人模型配置已保存，网页和安卓端会读取同一份配置。可使用「测试连接」验证实际调用。",
      );
    } catch (error) {
      if (!active.current) return;
      setError(errorMessage(error));
      if (error instanceof ApiError && error.status === 409) setConflict(true);
      else if (!(error instanceof ApiError) || error.status >= 500) {
        setConflict(true);
        setError(
          "保存结果待确认，请重新加载配置核实后再操作。输入仍保留在当前页面。",
        );
      }
    } finally {
      action.current = false;
      if (active.current) setBusy(null);
    }
  }

  async function remove() {
    if (!settings || action.current) return;
    action.current = true;
    sequence.current++;
    setBusy("remove");
    setError("");
    setMessage("");
    try {
      const { settings: next } = await api<{ settings: ModelSettingsInfo }>(
        "/api/model-settings",
        {
          method: "DELETE",
          body: JSON.stringify({ base_version: settings.version }),
        },
      );
      if (!active.current) return;
      apply(next);
      setMessage(
        next.effective_source === "server"
          ? "已移除个人配置，之后使用服务端默认模型。"
          : "已移除个人配置，之后使用规则解析。",
      );
    } catch (error) {
      if (active.current) {
        setError(errorMessage(error));
        if (error instanceof ApiError && error.status === 409)
          setConflict(true);
      }
    } finally {
      action.current = false;
      if (active.current) setBusy(null);
    }
  }

  if (loading)
    return (
      <div className="model-loading" role="status">
        <LoaderCircle size={20} className="spin" />
        正在读取个人配置…
      </div>
    );
  if (!settings)
    return (
      <div>
        <div className="inline-error" role="alert">
          {error || "暂时无法读取配置。"}
        </div>
        <button
          className="secondary-button wide"
          onClick={() => void load(true)}
        >
          <RefreshCw size={15} />
          重新加载
        </button>
      </div>
    );

  return (
    <div className="model-panel">
      <div className="model-prompt-note">
        <ShieldCheck size={18} />
        <div>
          <strong>日程提示词已内置</strong>
          <p>
            程序固定附带日程规则、参考日期和上海时区。你只需配置模型接口，无需填写提示词。
          </p>
        </div>
      </div>
      <div className="model-current-source">
        <span className="green-dot" />
        当前使用：
        {settings.effective_source === "personal"
          ? "个人模型配置"
          : settings.effective_source === "server"
            ? "服务端默认模型"
            : "规则解析"}
        {dirty && <span className="unsaved-label">有未保存修改</span>}
      </div>
      <p className="model-trust-note">
        密钥会发送到你连接的顺手排服务并加密保存，请在可信服务上填写。公网访问使用
        HTTPS。
      </p>
      {!settings.encryption_available && (
        <div className="inline-error" role="alert">
          管理员尚未配置模型密钥加密服务，暂时无法保存个人 API Key。
        </div>
      )}
      {conflict && (
        <div className="model-conflict" role="status">
          <p>
            配置可能已在另一端更新。重新加载会替换当前输入，请核实后再保存。
          </p>
          <button
            className="secondary-button"
            disabled={!!busy}
            onClick={() => void load(true)}
          >
            <RefreshCw size={14} />
            重新加载配置
          </button>
        </div>
      )}
      <form className="form-stack model-form" onSubmit={save}>
        <label>
          Base URL
          <input
            type="url"
            placeholder="https://api.example.com/v1"
            aria-label="Base URL"
            autoComplete="off"
            spellCheck={false}
            maxLength={2048}
            required
            value={baseUrl}
            disabled={!!busy}
            onChange={(e) => {
              setBaseUrl(e.target.value);
              edited();
            }}
          />
          <small>填接口根地址；程序自动追加 /chat/completions。</small>
        </label>
        <label>
          模型名称
          <input
            aria-label="模型名称"
            placeholder="填写账号可用的模型 ID"
            autoComplete="off"
            spellCheck={false}
            maxLength={200}
            required
            value={model}
            disabled={!!busy}
            onChange={(e) => {
              setModel(e.target.value);
              edited();
            }}
          />
        </label>
        <label>
          API Key
          <div className="model-key-field">
            <KeyRound size={16} />
            <input
              type="password"
              name="model_api_key"
              aria-label="API Key"
              placeholder={
                settings.has_api_key
                  ? `已保存 ${settings.key_hint || "密钥"}，留空保持`
                  : "输入你的 API Key"
              }
              autoComplete="off"
              spellCheck={false}
              maxLength={4096}
              value={apiKey}
              disabled={!!busy}
              onChange={(e) => {
                setApiKey(e.target.value);
                edited();
              }}
            />
          </div>
          <small>
            {settings.has_api_key
              ? `已保存 ${settings.key_hint || "密钥"}，密钥原文不会返回浏览器。更换接口域名时请重新填写 Key。`
              : "密钥按账号加密保存在服务端，网页和安卓共用。"}
          </small>
        </label>
        <p className="model-format-note">
          需要兼容 Chat Completions 和 JSON 输出。公网接口使用
          HTTPS；本地模型地址需管理员加入允许列表。测试连接会发起一次小型模型请求。
        </p>
        {error && (
          <div className="inline-error" role="alert">
            {error}
          </div>
        )}
        {message && (
          <div className="inline-info" role="status">
            {message}
          </div>
        )}
        <div className="model-actions">
          <button
            type="button"
            className="secondary-button"
            disabled={!!busy || conflict}
            onClick={() => void testConnection()}
          >
            {busy === "test" ? (
              <LoaderCircle size={16} className="spin" />
            ) : (
              <FlaskConical size={16} />
            )}
            测试连接
          </button>
          <button
            className="primary-button"
            disabled={!!busy || conflict || !settings.encryption_available}
          >
            {busy === "save" ? (
              <LoaderCircle size={16} className="spin" />
            ) : (
              <Check size={16} />
            )}
            保存配置
          </button>
        </div>
      </form>
      {settings.configured && (
        <div className="model-remove">
          {confirmRemove ? (
            <>
              <p>移除后将使用服务端默认模型，未配置默认模型时使用规则解析。</p>
              <div>
                <button
                  className="text-button"
                  disabled={!!busy}
                  onClick={() => setConfirmRemove(false)}
                >
                  取消
                </button>
                <button
                  className="secondary-button danger-text"
                  disabled={!!busy || conflict}
                  onClick={() => void remove()}
                >
                  {busy === "remove" ? (
                    <LoaderCircle size={15} className="spin" />
                  ) : (
                    <Trash2 size={15} />
                  )}
                  确认移除
                </button>
              </div>
            </>
          ) : (
            <button
              className="text-button danger-text"
              disabled={!!busy}
              onClick={() => setConfirmRemove(true)}
            >
              <Trash2 size={14} />
              移除个人配置
            </button>
          )}
        </div>
      )}
    </div>
  );
}
