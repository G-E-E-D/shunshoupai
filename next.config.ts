import type { NextConfig } from "next";
import { networkInterfaces } from "node:os";

const localAddresses = Object.values(networkInterfaces()).flatMap((entries) =>
  (entries || [])
    .filter((entry) => entry.family === "IPv4" && !entry.internal)
    .map((entry) => entry.address),
);

const config: NextConfig = {
  output: "export",
  trailingSlash: true,
  distDir: process.env.NEXT_DIST_DIR || ".next",
  devIndicators: false,
  allowedDevOrigins: ["127.0.0.1", ...localAddresses],
  poweredByHeader: false,
};
export default config;
