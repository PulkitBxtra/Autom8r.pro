import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // A self-contained server (.next/standalone) for the Docker image; npm run dev is unaffected.
  output: "standalone",
};

export default nextConfig;
