import { AppError } from "@/lib/server/errors";
import { handle, sameOrigin } from "@/lib/server/http";
export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export async function POST(request: Request) {
  return handle(() => { sameOrigin(request); throw new AppError("ACCOUNTS_REMOVED", "此版本无需账号。打开本机应用即可使用，其他设备通过局域网扫码配对。", 410); });
}
