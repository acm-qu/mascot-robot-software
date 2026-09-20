// The tag words live twice: Tags.kt on the tablet (the source of truth) and TAG_WORDS in lib/acmo.ts
// for the console's row of tag buttons. `npm run lint` runs this so the two cannot drift.
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const kt = readFileSync(join(here, "../../app/src/main/java/com/acmqu/acmo/remote/Tags.kt"), "utf8");
const ts = readFileSync(join(here, "../lib/acmo.ts"), "utf8");
const words = (s) => (s.match(/"([^"]*)"/g) ?? []).map((w) => w.slice(1, -1));

// Tags.kt: `Expression.HAPPY to listOf("laughs", "giggles", "cheerful")`; the label is the enum name in lower case.
const extras = {};
for (const m of kt.matchAll(/Expression\.([A-Z]+) to listOf\(([^)]*)\)/g)) extras[m[1].toLowerCase()] = words(m[2]);

// lib/acmo.ts: FEELINGS lists the faces; TAG_WORDS has a row per face.
const feelings = words(ts.match(/export const FEELINGS = \[([^\]]*)\]/)[1]);
const table = {};
for (const m of ts.match(/export const TAG_WORDS[^{]*\{([\s\S]*?)\n\};/)[1].matchAll(/^\s*([a-z]+): \[([^\]]*)\]/gm)) table[m[1]] = words(m[2]);

let drift = 0;
for (const face of feelings) {
  const tablet = [face, ...(extras[face] ?? [])];
  const console_ = table[face] ?? [];
  if (tablet.join() !== console_.join()) {
    console.error(`${face}: Tags.kt says [${tablet}] but TAG_WORDS says [${console_}]`);
    drift++;
  }
}
if (Object.keys(extras).length === 0 || feelings.length === 0) {
  console.error("check-words: could not read one of the tables; did their shape change?");
  process.exit(1);
}
if (drift) process.exit(1);
console.log(`check-words: ${feelings.length} faces, the tablet and the console agree`);
