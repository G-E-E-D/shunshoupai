import { connection } from "next/server";
import Dashboard from "@/components/dashboard";
import { shanghaiDay } from "@/lib/client";

export default async function Home() {
  // Generate today's date for each request, then reuse it during hydration.
  // A static build date would go stale when the deployed app crosses midnight.
  await connection();
  return <Dashboard initialDay={shanghaiDay()} />;
}
