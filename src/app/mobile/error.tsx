"use client";
import { useEffect } from "react";

export default function MobileError({ retry }: { retry: () => void }) {
  useEffect(() => {
    document.documentElement.setAttribute("data-shunshoupai-error", "true");
    return () => { document.documentElement.removeAttribute("data-shunshoupai-error"); };
  }, []);
  return (
    <main style={{ padding: "64px 24px", maxWidth: 440, margin: "0 auto", lineHeight: 1.7 }}>
      <h1 style={{ fontSize: 24 }}>日程暂时未能显示</h1>
      <p>请重新加载。若仍无法显示，请更新手机的 Android System WebView 或 Chrome，并确认电脑应用保持运行。</p>
      <button onClick={retry}>重新加载日程</button>
    </main>
  );
}
