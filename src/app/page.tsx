import { connection } from "next/server";
import { headers } from "next/headers";
import Dashboard from "@/components/dashboard";
import { shanghaiDay } from "@/lib/client";

export default async function Home() {
  // Generate today's date for each request, then reuse it during hydration.
  // A static build date would go stale when the deployed app crosses midnight.
  await connection();
  const userAgent = (await headers()).get("user-agent") || "";
  if (!userAgent.includes("ShunshoupaiAndroid/") && process.env.APP_MOBILE_UI_TEST !== "true") {
    return (
      <main style={{ maxWidth: 640, margin: "12vh auto", padding: 24 }}>
        <h1>顺手排服务已启动</h1>
        <p>电脑端使用原生 C++ / Qt 桌面应用。此服务为桌面客户端和安卓客户端提供 API。</p>
        <a href="https://github.com/G-E-E-D/shunshoupai/releases/latest">下载安装包</a>
      </main>
    );
  }
  return <Dashboard initialDay={shanghaiDay()} />;
}
