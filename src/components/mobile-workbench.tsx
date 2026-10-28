"use client";

import dynamic from "next/dynamic";
import { shanghaiDay } from "@/lib/client";

// Initialize the day when this WebView opens, never at static build time.
const Workbench = dynamic(async () => {
  const { default: Dashboard } = await import("./dashboard");
  return function CurrentWorkbench() {
    return <Dashboard initialDay={shanghaiDay()} />;
  };
}, { ssr: false, loading: () => <main aria-busy="true">正在打开本机日程…</main> });

export default function MobileWorkbench() { return <Workbench />; }
