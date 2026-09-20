import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // The dev server would otherwise write AGENTS.md and CLAUDE.md into this folder on every start.
  agentRules: false,
};

export default nextConfig;
