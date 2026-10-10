#!/usr/bin/env node
// Regenerate OpenSpec's Claude Code integration with this project's settings.
//
//   npm run agents:update [-- --force]
//
// Run it through npm so the `openspec` CLI pinned in package.json (node_modules/.bin) is used.
//
// OpenSpec reads its delivery mode and workflow list from a per-machine global config.
// Its default delivery ("both") writes every workflow twice for Claude Code: as a
// `.claude/skills/openspec-*` skill and as a `/opsx:*` command. This project keeps only
// the commands (ADR-0006), so we run `openspec update` against a temporary config.

import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");

const PROJECT_OPENSPEC_CONFIG = {
  profile: "custom",
  delivery: "commands",
  workflows: ["propose", "explore", "apply", "update", "sync", "archive"],
};

const configHome = mkdtempSync(join(tmpdir(), "openspec-config-"));
try {
  mkdirSync(join(configHome, "openspec"));
  writeFileSync(
    join(configHome, "openspec", "config.json"),
    JSON.stringify(PROJECT_OPENSPEC_CONFIG, null, 2),
  );
  const result = spawnSync("openspec", ["update", ...process.argv.slice(2), ROOT], {
    cwd: ROOT,
    stdio: "inherit",
    shell: process.platform === "win32",
    env: { ...process.env, XDG_CONFIG_HOME: configHome, OPENSPEC_TELEMETRY: "0" },
  });
  if (result.error) throw result.error;
  process.exitCode = result.status ?? 1;
} finally {
  rmSync(configHome, { recursive: true, force: true });
}
