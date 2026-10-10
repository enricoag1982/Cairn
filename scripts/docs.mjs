#!/usr/bin/env node
// Documentation tooling for this repository. No dependencies beyond `yaml`.
//
//   node scripts/docs.mjs check [--strict]   validate decisions, links, review dates and skills
//   node scripts/docs.mjs index              regenerate the decision log in docs/decisions/README.md
//   node scripts/docs.mjs new-adr "Title"    create the next decision record from the template
//
// Conventions enforced here are described in docs/process/documentation-lifecycle.md.

import { execFileSync } from "node:child_process";
import { existsSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, normalize, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parse as parseYaml } from "yaml";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const DECISIONS_DIR = "docs/decisions";
const DECISION_INDEX = join(DECISIONS_DIR, "README.md");
const ADR_TEMPLATE = "docs/templates/decision.md";
const INDEX_START = "<!-- decision-log:start -->";
const INDEX_END = "<!-- decision-log:end -->";
const ADR_FILE = /^(\d{4})-[a-z0-9]+(?:-[a-z0-9]+)*\.md$/;
const ADR_STATUS = /^(proposed|accepted|rejected|deprecated|superseded by ADR-(\d{4}))$/;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

// Living documents must carry `last-reviewed` in their frontmatter.
const REVIEWED_DIRS = ["docs/architecture", "docs/guides", "docs/process", "docs/reference"];
const REVIEWED_FILES = ["docs/glossary.md", "docs/principles.md"];
const REVIEW_MAX_AGE_DAYS = 180;

// Project skills (Agent Skills format). OpenSpec workflows are `/opsx:*` commands instead;
// an `openspec-*` skill here means `openspec update` ran with the default delivery mode.
const SKILLS_DIR = ".claude/skills";
const GENERATED_SKILL = /^openspec-/;
const SKILL_NAME = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;

// Historical records: never rewritten, so their links are not checked.
const LINK_CHECK_EXCLUDES = ["openspec/changes/archive/"];

const errors = [];
const warnings = [];

function markdownFiles() {
  const out = execFileSync(
    "git",
    ["ls-files", "--cached", "--others", "--exclude-standard", "--", "*.md"],
    { cwd: ROOT, encoding: "utf8" },
  );
  return [...new Set(out.split("\n").filter(Boolean))]
    .filter((f) => existsSync(join(ROOT, f)))
    .sort();
}

function read(file) {
  return readFileSync(join(ROOT, file), "utf8");
}

function splitFrontmatter(text) {
  const match = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?/.exec(text);
  if (!match) return { data: null, body: text };
  try {
    return { data: parseYaml(match[1]) ?? {}, body: text.slice(match[0].length) };
  } catch (e) {
    return { data: null, body: text, error: e.message };
  }
}

function asDateString(value) {
  if (value instanceof Date) return value.toISOString().slice(0, 10);
  return typeof value === "string" ? value : "";
}

function title(body) {
  const match = /^#\s+(.+)$/m.exec(body);
  return match ? match[1].trim() : null;
}

// ---------------------------------------------------------------- decisions

function loadDecisions() {
  const dir = join(ROOT, DECISIONS_DIR);
  if (!existsSync(dir)) return [];
  return readdirSync(dir)
    .filter((name) => name.endsWith(".md") && !["README.md", "AGENTS.md", "CLAUDE.md"].includes(name))
    .sort()
    .map((name) => {
      const file = join(DECISIONS_DIR, name);
      const { data, body, error } = splitFrontmatter(read(file));
      const numberMatch = ADR_FILE.exec(name);
      return {
        file,
        name,
        number: numberMatch ? numberMatch[1] : null,
        data,
        error,
        title: title(body),
      };
    });
}

function checkDecisions(decisions) {
  const seen = new Map();
  for (const adr of decisions) {
    if (!adr.number) {
      errors.push(`${adr.file}: file name must match NNNN-kebab-case-title.md`);
      continue;
    }
    if (seen.has(adr.number)) {
      errors.push(`${adr.file}: number ${adr.number} already used by ${seen.get(adr.number)}`);
    }
    seen.set(adr.number, adr.file);
    if (adr.error) errors.push(`${adr.file}: invalid frontmatter YAML: ${adr.error}`);
    if (!adr.data) {
      errors.push(`${adr.file}: missing YAML frontmatter (status, date)`);
      continue;
    }
    const status = String(adr.data.status ?? "");
    const statusMatch = ADR_STATUS.exec(status);
    if (!statusMatch) {
      errors.push(
        `${adr.file}: status "${status}" must be proposed | accepted | rejected | deprecated | superseded by ADR-NNNN`,
      );
    } else if (statusMatch[2] && !decisions.some((d) => d.number === statusMatch[2])) {
      errors.push(`${adr.file}: superseded by ADR-${statusMatch[2]}, which does not exist`);
    }
    if (!ISO_DATE.test(asDateString(adr.data.date))) {
      errors.push(`${adr.file}: date must be YYYY-MM-DD`);
    }
    if (!adr.title) errors.push(`${adr.file}: missing "# Title" heading`);
  }
}

function renderIndex(decisions) {
  const rows = decisions
    .filter((adr) => adr.number && adr.data)
    .map((adr) => {
      const status = String(adr.data.status ?? "").replace(/\|/g, "\\|");
      const date = asDateString(adr.data.date);
      const label = (adr.title ?? adr.name).replace(/\|/g, "\\|");
      return `| [ADR-${adr.number}](${adr.name}) | ${label} | ${status} | ${date} |`;
    });
  return [
    INDEX_START,
    "<!-- Generated by `npm run docs:index`. Do not edit by hand. -->",
    "",
    "| ID | Decision | Status | Date |",
    "| --- | --- | --- | --- |",
    ...rows,
    "",
    INDEX_END,
  ].join("\n");
}

function replaceIndex(text, decisions) {
  const start = text.indexOf(INDEX_START);
  const end = text.indexOf(INDEX_END);
  if (start === -1 || end === -1 || end < start) return null;
  return text.slice(0, start) + renderIndex(decisions) + text.slice(end + INDEX_END.length);
}

function checkIndex(decisions) {
  if (!existsSync(join(ROOT, DECISION_INDEX))) {
    errors.push(`${DECISION_INDEX}: missing decision log`);
    return;
  }
  const current = read(DECISION_INDEX);
  const expected = replaceIndex(current, decisions);
  if (expected === null) {
    errors.push(`${DECISION_INDEX}: missing ${INDEX_START} / ${INDEX_END} markers`);
  } else if (expected !== current) {
    errors.push(`${DECISION_INDEX}: decision log is out of date, run \`npm run docs:index\``);
  }
}

// -------------------------------------------------------------------- links

function stripCode(text) {
  return text
    .replace(/^(\s*)(`{3,}|~{3,})[\s\S]*?^\1\2\s*$/gm, "")
    .replace(/`[^`\n]*`/g, "")
    .replace(/<!--[\s\S]*?-->/g, "");
}

function checkLinks(files) {
  const known = new Set(files);
  for (const file of files) {
    if (LINK_CHECK_EXCLUDES.some((prefix) => file.startsWith(prefix))) continue;
    const text = stripCode(read(file));

    for (const match of text.matchAll(/(?<!!)\[\[([^\]]+)\]\]/g)) {
      errors.push(
        `${file}: wikilink [[${match[1]}]] does not render on GitHub, use a relative Markdown link`,
      );
    }

    const targets = [
      ...[...text.matchAll(/\]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)/g)].map((m) => m[1]),
      ...[...text.matchAll(/^\s*\[[^\]]+\]:\s*<?(\S+?)>?(?:\s+.*)?$/gm)].map((m) => m[1]),
    ];
    for (const target of targets) {
      if (/^[a-z][a-z0-9+.-]*:/i.test(target) || target.startsWith("#")) continue;
      const path = decodeURIComponent(target.split("#")[0].split("?")[0]);
      if (!path) continue;
      const resolved = path.startsWith("/")
        ? normalize(path.slice(1))
        : normalize(join(dirname(file), path));
      if (resolved.startsWith("..")) {
        errors.push(`${file}: link "${target}" points outside the repository`);
      } else if (!known.has(resolved) && !existsSync(join(ROOT, resolved))) {
        errors.push(`${file}: broken link "${target}"`);
      }
    }
  }
}

// ------------------------------------------------------------ review dates

function checkReviewDates(files, today) {
  const living = files.filter(
    (f) =>
      REVIEWED_FILES.includes(f) ||
      REVIEWED_DIRS.some((dir) => f.startsWith(`${dir}/`) && !/\/(AGENTS|CLAUDE)\.md$/.test(f)),
  );
  for (const file of living) {
    const { data } = splitFrontmatter(read(file));
    const reviewed = asDateString(data?.["last-reviewed"]);
    if (!ISO_DATE.test(reviewed)) {
      errors.push(`${file}: living document needs \`last-reviewed: YYYY-MM-DD\` frontmatter`);
      continue;
    }
    const ageDays = Math.floor((today - new Date(`${reviewed}T00:00:00Z`)) / 86_400_000);
    if (ageDays > REVIEW_MAX_AGE_DAYS) {
      warnings.push(
        `${file}: last reviewed ${ageDays} days ago (limit ${REVIEW_MAX_AGE_DAYS}), re-check it against reality`,
      );
    }
  }
}

// ------------------------------------------------------------------- skills

function checkSkills() {
  const dir = join(ROOT, SKILLS_DIR);
  if (!existsSync(dir)) return;
  const names = readdirSync(dir).filter(
    (name) => !name.startsWith(".") && statSync(join(dir, name)).isDirectory(),
  );
  for (const name of names) {
    const folder = join(SKILLS_DIR, name);
    if (GENERATED_SKILL.test(name)) {
      errors.push(
        `${folder}: duplicates an /opsx command; delete it and use \`npm run agents:update\`, not \`openspec update\``,
      );
      continue;
    }
    const skillFile = join(folder, "SKILL.md");
    if (!existsSync(join(ROOT, skillFile))) {
      errors.push(`${folder}: missing SKILL.md`);
      continue;
    }
    const { data } = splitFrontmatter(read(skillFile));
    if (data?.name !== name || !SKILL_NAME.test(name) || name.length > 64) {
      errors.push(`${skillFile}: frontmatter name must equal the folder name (a-z, 0-9, single hyphens)`);
    }
    const description = String(data?.description ?? "");
    if (!description || description.length > 1024) {
      errors.push(`${skillFile}: description is required and must be at most 1024 characters`);
    }
  }
}

// ----------------------------------------------------------------- commands

function commandCheck(strict) {
  const files = markdownFiles();
  const decisions = loadDecisions();
  checkDecisions(decisions);
  checkIndex(decisions);
  checkLinks(files);
  checkReviewDates(files, Date.now());
  checkSkills();

  for (const w of warnings) console.warn(`warning: ${w}`);
  for (const e of errors) console.error(`error: ${e}`);
  const failed = errors.length > 0 || (strict && warnings.length > 0);
  console.log(
    `docs check: ${files.length} files, ${decisions.length} decisions, ` +
      `${errors.length} errors, ${warnings.length} warnings`,
  );
  process.exit(failed ? 1 : 0);
}

function commandIndex() {
  const decisions = loadDecisions();
  const current = read(DECISION_INDEX);
  const next = replaceIndex(current, decisions);
  if (next === null) {
    console.error(`${DECISION_INDEX}: missing ${INDEX_START} / ${INDEX_END} markers`);
    process.exit(1);
  }
  if (next !== current) writeFileSync(join(ROOT, DECISION_INDEX), next);
  console.log(`decision log: ${decisions.length} entries`);
}

function commandNewAdr(titleWords) {
  const heading = titleWords.join(" ").trim();
  if (!heading) {
    console.error('usage: npm run adr:new -- "Short title of solved problem and solution"');
    process.exit(1);
  }
  const decisions = loadDecisions();
  const next = String(
    decisions.reduce((max, adr) => Math.max(max, Number(adr.number ?? 0)), 0) + 1,
  ).padStart(4, "0");
  const slug = heading
    .toLowerCase()
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "");
  const file = join(DECISIONS_DIR, `${next}-${slug}.md`);
  const today = new Date().toISOString().slice(0, 10);
  const content = read(ADR_TEMPLATE)
    .replace(/^status: .*$/m, "status: proposed")
    .replace(/^date: .*$/m, `date: ${today}`)
    .replace(/^# .*$/m, `# ${heading}`);
  writeFileSync(join(ROOT, file), content);
  commandIndex();
  console.log(`created ${relative(process.cwd(), join(ROOT, file))}`);
}

const [command, ...args] = process.argv.slice(2);
switch (command) {
  case "check":
    commandCheck(args.includes("--strict"));
    break;
  case "index":
    commandIndex();
    break;
  case "new-adr":
    commandNewAdr(args);
    break;
  default:
    console.error("usage: node scripts/docs.mjs <check [--strict] | index | new-adr <title>>");
    process.exit(1);
}
