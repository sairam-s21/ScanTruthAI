// Pure, dependency-free parsing of a product label's OCR text. Used on its own when the cloud
// model is unavailable, and to check the cloud model's answer against what the label says.

/** Headings that start the ingredient list. OCR often garbles these, so matching is fuzzy. */
const HEADER_WORDS = ["ingredients", "ingredient", "composition", "contents"];

/**
 * Next sections of a label. These never appear inside an ingredient list, so they end it
 * wherever they occur, even if OCR lost a bracket and the parser thinks it is inside one.
 */
const SECTION_HEADINGS = [
  /\ballerg[a-z]*\b/, // allergen advice / allergy information
  /\bcommodity\b/,
  /\bcategory\s*[:\-]/,
  /\bnutrition(?:al)?\b/,
  /\b(?:typical|approximate)\s+values\b/,
  /\bstorage\b/,
  /\bstore\s+(?:in|at|below)\b/,
  /\bdirections?\b/,
  /\bhow\s+to\s+(?:use|prepare|make)\b/,
  /\bserving\s+(?:suggestions?|size)\b/,
  /\brecommended\s+(?:serving|dosage|daily)\b/,
  /\bbest\s+before\b/,
  /\buse\s+by\b/,
  /\b(?:mfg|mfd|pkd)\.?\s*(?:date|on|by)\b/,
  /\bexp(?:iry)?\.?\s*date\b/,
  /\b(?:manufactured|marketed|packed|distributed|imported)\s+(?:&\s+\w+\s+)?by\b/,
  /\bfssai\b/,
  /\blic(?:ence|ense)?\.?\s*no\b/,
  /\bnet\s+(?:wt|weight|qty|quantity|content)\b/,
  /\bmrp\b/,
  /\b(?:batch|lot)\s*(?:no|number)\b/,
  /\bcustomer\s+care\b/,
  /\bwarning\b/,
  /\bdisclaimer\b/,
];

/** Statements that end the list only outside brackets ("(contains added flavours)" is fine). */
const STATEMENTS = [
  /\bmay\s+contain\b/,
  /\bcontains\s+(?:non|naturally|no|added)\b/,
  /\bcontains\s*[:\-]/,
  /\bnot\s+(?:for|to\s+be)\b/,
  /\bdo\s+not\b/,
  /\bkeep\s+out\s+of\b/,
  /\bprocessed\s+(?:on|in)\b/,
  /\bcaution\b/,
];

/** Additive classes that name what an ingredient does rather than what it is. */
const FUNCTIONAL_CLASSES = new Set([
  "sweetener",
  "sweeteners",
  "emulsifier",
  "emulsifiers",
  "preservative",
  "preservatives",
  "stabiliser",
  "stabilizer",
  "stabilisers",
  "stabilizers",
  "thickener",
  "thickeners",
  "colour",
  "color",
  "colours",
  "colors",
  "flavour",
  "flavor",
  "flavouring",
  "flavoring",
  "acidity regulator",
  "acidity regulators",
  "antioxidant",
  "antioxidants",
  "raising agent",
  "raising agents",
  "humectant",
  "anticaking agent",
  "anti-caking agent",
  "probiotic",
  "probiotics",
  "prebiotic",
  "prebiotics",
]);

/** Words that are never an ingredient on their own (nutrition-table rows, connectors). */
const NOT_INGREDIENTS =
  /^(?:and|or|with|contains?|ingredients?|energy|carbohydrates?|total\s+(?:fat|salt|sugars?|carbohydrates?)|added\s+sugars?|sodium|dietary\s+fib(?:re|er)|protein|per\s+serve|nil|na|n\/a)$/i;

const QUANTITY =
  /\b\d+(?:[.,]\d+)?\s*(?:%|percent|mg|mcg|µg|g|gm|kg|ml|l|iu|kcal|kj|cfu(?:\s*\/\s*g)?|(?:billion|million|crore|lakh)\s*cfu(?:\s*\/\s*g)?)(?=\W|$)|\b\d+(?:[.,]\d+)?\s*(?:billion|million)\b|\(\s*\d+(?:[.,]\d+)?\s*%?\s*\)/gi;

const ABBREVIATIONS = ["vit", "nat", "no", "approx", "ins", "eg", "etc", "st", "dr"];

export function levenshtein(a: string, b: string): number {
  if (a === b) return 0;
  const prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    let diag = prev[0];
    prev[0] = i;
    for (let j = 1; j <= b.length; j++) {
      const temp = prev[j];
      prev[j] = Math.min(prev[j] + 1, prev[j - 1] + 1, diag + (a[i - 1] === b[j - 1] ? 0 : 1));
      diag = temp;
    }
  }
  return prev[b.length];
}

function normalize(text: string): string {
  return text
    .replace(/[‘’]/g, "'")
    .replace(/[“”^*]/g, " ") // "Probiotics^" or OCR's ” after a word
    .replace(/[–—]/g, "-")
    .replace(/[{[]/g, "(")
    .replace(/[}\]]/g, ")")
    .replace(/\s*\r?\n\s*/g, " ")
    .replace(/[ \t]+/g, " ")
    .trim();
}

/**
 * Index just after the ingredients heading (tolerates OCR errors like "INGREDIENIS"), or -1.
 * "Composition" and "Contents" also appear in ordinary sentences ("Indian Food Composition
 * Tables"), so they count only when followed by a colon; an "Ingredients" heading wins.
 */
function findHeaderEnd(text: string): number {
  const word = /[A-Za-z0-9|!]{6,14}/g;
  let best: { end: number; score: number } | null = null;
  let match: RegExpExecArray | null;
  while ((match = word.exec(text))) {
    const token = match[0].toLowerCase().replace(/[0|!1]/g, "i");
    const heading = HEADER_WORDS.find((h) => levenshtein(token, h) <= (h.length >= 10 ? 2 : 1));
    if (!heading) continue;
    const isIngredientWord = heading.startsWith("ingredient");
    const tokenEnd = match.index + match[0].length;
    const colon = text.slice(tokenEnd, tokenEnd + 3).match(/^\s*[:;\-.]/);
    if (!colon && !isIngredientWord) continue;
    if (!colon && !/^\s+[A-Za-z]/.test(text.slice(tokenEnd, tokenEnd + 3))) continue;
    const score = (colon ? 2 : 0) + (isIngredientWord ? 1 : 0);
    if (!best || score > best.score) best = { end: tokenEnd + (colon ? colon[0].length : 0), score };
    if (score === 3) break;
  }
  return best ? best.end : -1;
}

/**
 * Where the heading before a colon starts ("… CFU/g). Serving Suggestion:"), or -1 when the
 * words name an additive class inside the list ("Emulsifier: soy lecithin").
 */
function colonHeadingStart(text: string, colon: number): number {
  const windowStart = Math.max(0, colon - 40);
  const m = text.slice(windowStart, colon).match(/([A-Za-z]+(?:\s+[A-Za-z]+){0,2})\s*$/);
  if (!m || m.index === undefined) return -1;
  const phrase = m[1].toLowerCase();
  const lastWord = phrase.split(/\s+/).pop() || "";
  if (FUNCTIONAL_CLASSES.has(phrase) || FUNCTIONAL_CLASSES.has(lastWord)) return -1;
  return windowStart + m.index;
}

/** Cuts the section off where the next part of the label begins. */
function sliceSection(text: string): string {
  const lower = text.toLowerCase();
  let depth = 0;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (ch === "(") depth++;
    else if (ch === ")") depth = Math.max(0, depth - 1);

    // "Serving Suggestion:", "Category:" … a colon after plain words starts a new section.
    if (ch === ":" && i > 3) {
      const start = colonHeadingStart(text, i);
      if (start > 0) return text.slice(0, start);
    }

    const atWordStart = i > 2 && /[^\p{L}\p{N}]/u.test(text[i - 1]);
    if (!atWordStart) continue;
    const rest = lower.slice(i, i + 40);
    if (SECTION_HEADINGS.some((re) => new RegExp("^" + re.source).test(rest))) return text.slice(0, i);
    if (depth === 0 && STATEMENTS.some((re) => new RegExp("^" + re.source).test(rest))) return text.slice(0, i);
  }
  return text;
}

/** OCR drops or misreads brackets ("(Jowar" → "Wowar"): ignore stray closers, close what is open. */
function balanceBrackets(text: string): string {
  let depth = 0;
  let out = "";
  for (const ch of text) {
    if (ch === "(") depth++;
    if (ch === ")") {
      if (depth === 0) continue;
      depth--;
    }
    out += ch;
  }
  return out.replace(/[\s,;.]+$/, "") + ")".repeat(depth);
}

/**
 * Splits on separators that are not inside brackets: commas, semicolons, and full stops that
 * end a word (OCR often reads a comma as a full stop: "Dates Syrup. Peanut Protein").
 */
function splitTopLevel(text: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let current = "";
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (ch === "(") depth++;
    if (ch === ")") depth = Math.max(0, depth - 1);
    let separator = (ch === "," || ch === ";") && depth === 0;
    if (ch === "." && depth === 0 && /^\s+\p{L}/u.test(text.slice(i + 1, i + 3))) {
      const prevWord = (current.toLowerCase().match(/([a-z]+)$/) || [])[1] || "";
      separator = !ABBREVIATIONS.includes(prevWord) && !/\d$/.test(current);
    }
    if (separator) {
      parts.push(current);
      current = "";
    } else {
      current += ch;
    }
  }
  parts.push(current);
  return parts.map((p) => p.trim()).filter(Boolean);
}

/** Separates "Name (a) (b)" into the name and its bracket groups. */
function splitGroups(item: string): { name: string; groups: string[] } {
  const groups: string[] = [];
  let name = "";
  let depth = 0;
  let current = "";
  for (const ch of item) {
    if (ch === "(") {
      if (depth === 0) current = "";
      else current += ch;
      depth++;
    } else if (ch === ")") {
      if (depth === 0) continue;
      depth--;
      if (depth === 0) groups.push(current.trim());
      else current += ch;
    } else if (depth > 0) {
      current += ch;
    } else {
      name += ch;
    }
  }
  if (depth > 0 && current.trim()) groups.push(current.trim());
  return { name: name.trim(), groups };
}

function stripQuantities(text: string): string {
  return text.replace(QUANTITY, " ").replace(/\s+/g, " ").trim();
}

/** Sentence case for ALL-CAPS labels, keeping codes such as "INS 322" or "SNZ 1969". */
function tidyCase(text: string): string {
  const letters = text.replace(/[^A-Za-z]/g, "");
  const mostlyUpper = letters.length > 0 && letters.replace(/[^A-Z]/g, "").length / letters.length > 0.7;
  if (!mostlyUpper) return text.charAt(0).toUpperCase() + text.slice(1);
  const words = text.toLowerCase().split(" ");
  const out = words.map((w, i) => {
    const next = words[i + 1] || "";
    if (/\d/.test(w) || (/^[a-z]{1,4}$/.test(w) && /^\d/.test(next) && i > 0)) return w.toUpperCase();
    return w;
  });
  const joined = out.join(" ");
  return joined.charAt(0).toUpperCase() + joined.slice(1);
}

function cleanName(raw: string): string {
  return stripQuantities(raw)
    .replace(/[^\p{L}\p{N}\s&'\-/.()]/gu, " ") // OCR junk such as ¥ « | _ %
    .replace(/^[^\p{L}\p{N}]+|[^\p{L}\p{N})]+$/gu, "")
    .replace(/^(?:and|&)\s+/i, "")
    .replace(/\s+/g, " ")
    .trim();
}

function isIngredient(name: string): boolean {
  if (name.length < 2 || name.length > 60) return false;
  if ((name.match(/\p{L}/gu) || []).length < 2) return false;
  if (NOT_INGREDIENTS.test(name)) return false;
  return !/^(?:\d|[\W_])+$/.test(name);
}

/** "Soy lecithin & natural flavours" names two ingredients; "Mono & diglycerides" names one. */
function splitPair(name: string): string[] {
  const m = name.match(/^(.+?)\s+(?:&|and)\s+(.+)$/i);
  if (!m) return [name];
  const words = (s: string) => s.trim().split(/\s+/).length;
  return words(m[1]) >= 2 && words(m[2]) >= 2 ? [m[1], m[2]] : [name];
}

function expandItem(item: string, out: string[]) {
  const { name: rawName, groups } = splitGroups(item);
  const name = cleanName(rawName);
  const components = groups
    .map(stripQuantities)
    .flatMap((g) => splitTopLevel(g))
    .filter((c) => /\p{L}{2,}/u.test(stripQuantities(c)));

  // "Blend (a, b)" or "Fruits, nuts & seeds (raisins, chia ...)": list each component.
  if (components.length >= 2) {
    components.forEach((c) => expandItem(c, out));
    return;
  }
  if (components.length === 1) {
    const child = cleanName(splitGroups(components[0]).name);
    if (isIngredient(child)) {
      if (FUNCTIONAL_CLASSES.has(name.toLowerCase()) || !name) {
        out.push(name ? `${tidyCase(child)} (${name.toLowerCase()})` : tidyCase(child));
      } else if (isIngredient(name)) {
        out.push(`${tidyCase(name)} (${child.toLowerCase()})`);
      } else {
        out.push(tidyCase(child));
      }
      return;
    }
  }
  if (isIngredient(name)) splitPair(name).forEach((n) => out.push(tidyCase(n.trim())));
}

export interface ParsedIngredients {
  /** False when no ingredients heading was found; the caller should not guess. */
  sectionFound: boolean;
  /** The text between the heading and the next label section. */
  sectionText: string;
  ingredients: string[];
}

/**
 * Reads only the ingredients section of a label. Without a recognisable heading it returns
 * nothing rather than treating the whole label (nutrition table, warnings…) as ingredients.
 */
export function parseIngredientSection(ocrText: string): ParsedIngredients {
  const text = normalize(ocrText || "");
  const start = findHeaderEnd(text);
  if (start < 0) return { sectionFound: false, sectionText: "", ingredients: [] };

  const sectionText = balanceBrackets(sliceSection(text.slice(start)).trim());
  const raw: string[] = [];
  splitTopLevel(sectionText).forEach((item) => expandItem(item, raw));

  const seen = new Set<string>();
  const ingredients: string[] = [];
  for (const item of raw) {
    const key = item.toLowerCase();
    if (!seen.has(key)) {
      seen.add(key);
      ingredients.push(item);
    }
  }
  return { sectionFound: true, sectionText, ingredients };
}

/**
 * True when an ingredient named by the cloud model is backed by the label text: at least half
 * of its words of 4+ letters must appear in the text, allowing for OCR misspellings and words
 * OCR ran together. Stops the model from inventing ingredients while still letting it repair
 * "SACILUS" to "Bacillus" or "S¥ TEVIOL GLY CRE" to "Steviol glycosides".
 */
export function isGroundedIn(ingredient: string, ocrText: string): boolean {
  const lowerLabel = ocrText.toLowerCase();
  const labelWords = lowerLabel.match(/\p{L}{3,}/gu) || [];
  const compactLabel = lowerLabel.replace(/[^\p{L}]/gu, "");
  // Bracketed notes such as "(sweetener)" are the model's own wording, not label text.
  const name = ingredient.toLowerCase().replace(/\([^)]*\)/g, " ");
  const words = name.match(/\p{L}{4,}/gu) || [];
  if (words.length === 0) {
    const short = name.replace(/[^\p{L}]/gu, "");
    return short.length > 0 && compactLabel.includes(short);
  }
  const matched = words.filter((w) => {
    if (compactLabel.includes(w)) return true;
    const tolerance = w.length >= 8 ? 2 : 1;
    return labelWords.some((lw) => Math.abs(lw.length - w.length) <= tolerance && levenshtein(lw, w) <= tolerance);
  }).length;
  return matched >= Math.ceil(words.length / 2);
}
