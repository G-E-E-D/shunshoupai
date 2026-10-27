import { connection } from "next/server";
import Dashboard from "@/components/dashboard";
import { shanghaiDay } from "@/lib/client";

// The installed Android client has an explicit route. WebView vendors may
// replace the User-Agent; rendering must never depend on a vendor-specific UA.
export default async function MobileWorkbench() {
  await connection();
  return <Dashboard initialDay={shanghaiDay()} />;
}
