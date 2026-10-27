"use client";

import Image from "next/image";
import { useEffect, useState } from "react";
import QRCode from "qrcode";
import { Check, Copy, LoaderCircle, QrCode, Smartphone } from "lucide-react";
import { api, errorMessage } from "@/lib/client";

type ConnectionInfo = {
  current_origin: string;
  candidates: string[];
  hint: string;
};

function connectionUrl(value: string): { url: string; warning: string } {
  try {
    const parsed = new URL(value.trim());
    if (
      !["http:", "https:"].includes(parsed.protocol) ||
      parsed.username ||
      parsed.password ||
      parsed.search ||
      parsed.hash ||
      !["", "/"].includes(parsed.pathname)
    ) {
      return {
        url: "",
        warning: "请填写 HTTP 或 HTTPS 服务根地址，不附加路径、账号或参数。",
      };
    }
    if (
      ["localhost", "127.0.0.1", "[::1]", "[::]", "0.0.0.0"].includes(
        parsed.hostname.toLowerCase(),
      ) ||
      parsed.hostname.endsWith(".localhost") ||
      /^127\./.test(parsed.hostname)
    ) {
      return {
        url: "",
        warning:
          "这个地址指向扫码手机自身。请改用电脑的局域网 IP 或已部署的网址。",
      };
    }
    return { url: parsed.origin, warning: "" };
  } catch {
    return {
      url: "",
      warning: value
        ? "地址格式还不完整，请填写协议、IP 或域名及端口。"
        : "请选择手机能够访问的服务地址。",
    };
  }
}

export default function ConnectionPanel() {
  const [info, setInfo] = useState<ConnectionInfo | null>(null);
  const [address, setAddress] = useState("");
  const [generated, setGenerated] = useState<{
    address: string;
    image: string;
  } | null>(null);
  const [error, setError] = useState("");
  const [copied, setCopied] = useState(false);
  const [loading, setLoading] = useState(true);
  const parsed = connectionUrl(address);

  useEffect(() => {
    let active = true;
    api<ConnectionInfo>("/api/connection-info")
      .then((result) => {
        if (!active) return;
        setInfo(result);
        const current = connectionUrl(result.current_origin);
        setAddress(current.url || result.candidates[0] || "");
      })
      .catch((error) => {
        if (active) setError(errorMessage(error));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    let active = true;
    if (!parsed.url) return;
    QRCode.toDataURL(parsed.url, {
      width: 272,
      margin: 2,
      errorCorrectionLevel: "M",
      color: { dark: "#303542", light: "#ffffff" },
    })
      .then((data) => {
        if (active) setGenerated({ address: parsed.url, image: data });
      })
      .catch(() => {
        if (active) setError("二维码暂时无法生成，请复制服务地址连接。");
      });
    return () => {
      active = false;
    };
  }, [parsed.url]);

  async function copy() {
    if (!parsed.url) return;
    try {
      await navigator.clipboard.writeText(parsed.url);
      setCopied(true);
    } catch {
      setError("当前浏览器无法自动复制，请长按或选中下方地址复制。");
    }
  }

  if (loading)
    return (
      <div className="model-loading" role="status">
        <LoaderCircle size={20} className="spin" />
        正在准备连接地址…
      </div>
    );
  return (
    <div className="connection-panel">
      <div className="model-prompt-note">
        <Smartphone size={19} />
        <div>
          <strong>手机接着安排</strong>
          <p>
            打开安卓应用，点击「扫码连接」，扫描下方二维码即可打开同一个日程服务。
          </p>
        </div>
      </div>
      <div className="connection-qr">
        {parsed.url && generated?.address === parsed.url ? (
          <Image
            src={generated.image}
            alt="连接顺手排服务的二维码"
            width={240}
            height={240}
            unoptimized
          />
        ) : (
          <div className="qr-placeholder">
            <QrCode size={45} strokeWidth={1.3} />
            <span>填写有效地址后生成二维码</span>
          </div>
        )}
      </div>
      <label className="connection-address-label">
        手机访问的服务地址
        <input
          type="url"
          aria-label="手机访问的服务地址"
          value={address}
          placeholder="http://192.168.1.20:3000"
          autoComplete="off"
          spellCheck={false}
          maxLength={2048}
          onChange={(e) => {
            setAddress(e.target.value);
            setCopied(false);
            setError("");
          }}
        />
      </label>
      {info && info.candidates.length > 0 && (
        <div className="connection-candidates">
          <span>本机可选地址</span>
          {info.candidates.map((candidate) => (
            <button
              type="button"
              key={candidate}
              className={address === candidate ? "selected" : ""}
              onClick={() => {
                setAddress(candidate);
                setCopied(false);
              }}
            >
              {candidate}
            </button>
          ))}
        </div>
      )}
      {parsed.warning && (
        <p className="connection-url-warning" role="status">
          {parsed.warning}
        </p>
      )}
      {error && (
        <div className="inline-error" role="alert">
          {error}
        </div>
      )}
      <button
        className="secondary-button wide"
        disabled={!parsed.url}
        onClick={() => void copy()}
      >
        {copied ? <Check size={15} /> : <Copy size={15} />}
        {copied ? "已复制服务地址" : "复制服务地址"}
      </button>
      <div className="connection-steps">
        <p>
          <strong>同一局域网</strong>手机和电脑连接同一个
          Wi-Fi，电脑保持服务运行，并允许可信局域网访问端口。
        </p>
        <p>
          <strong>同一个账号</strong>二维码只包含服务地址，不含登录令牌或 API
          Key；扫码后登录自己的账号。
        </p>
        {info?.hint && <p>{info.hint}</p>}
      </div>
    </div>
  );
}
