import MobileWorkbench from "@/components/mobile-workbench";

// The installed Android client has an explicit route. WebView vendors may
// replace the User-Agent; rendering must never depend on a vendor-specific UA.
export default function MobilePage() {
  return <MobileWorkbench />;
}
