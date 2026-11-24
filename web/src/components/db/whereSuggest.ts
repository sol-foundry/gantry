import type { ColumnInfo, SqlService } from "@/types";

/** What the WHERE clause needs next at the caret: a condition is column, operator, value, then AND / OR, repeated. */
export type Step = "column" | "operator" | "value" | "join";

export interface Suggestion {
  label: string;
  /** Text that replaces the word being typed. */
  insert: string;
  detail?: string;
  /** Where the caret lands inside [insert] (e.g. between quotes); the end when unset. */
  caret?: number;
}

export interface Suggestions {
  step: Step;
  /** The span of the text the chosen suggestion replaces: the partly typed word, or the bare caret. */
  start: number;
  end: number;
  items: Suggestion[];
}

export interface Context {
  service: SqlService;
  columns: ColumnInfo[];
  /** Values of [column] to offer, e.g. those on the rows page in view. */
  values: (column: string) => (string | null)[];
}

/** Most columns or values listed at once; the operators are a fixed set and always listed in full. */
const MAX = 12;

interface Token {
  kind: "word" | "string" | "number" | "op" | "open" | "close" | "comma";
  text: string;
  start: number;
  end: number;
  /** A quoted identifier ("Name", `name`): never a keyword. */
  quoted?: boolean;
  /** A string literal still missing its closing quote. */
  open?: boolean;
}

function tokenize(text: string): Token[] {
  const tokens: Token[] = [];
  let i = 0;
  while (i < text.length) {
    const c = text[i]!;
    const start = i;
    if (/\s/.test(c)) {
      i++;
    } else if (c === "'") {
      i++;
      while (i < text.length && !(text[i] === "'" && text[i + 1] !== "'")) i += text[i] === "'" ? 2 : 1;
      const open = i >= text.length;
      if (!open) i++;
      tokens.push({ kind: "string", text: text.slice(start, i), start, end: i, open });
    } else if (c === '"' || c === "`") {
      const end = text.indexOf(c, i + 1);
      i = end < 0 ? text.length : end + 1;
      tokens.push({ kind: "word", text: text.slice(start + 1, end < 0 ? text.length : end), start, end: i, quoted: true });
    } else if (/[A-Za-z_]/.test(c)) {
      while (i < text.length && /[\w$]/.test(text[i]!)) i++;
      tokens.push({ kind: "word", text: text.slice(start, i), start, end: i });
    } else if (/[\d.-]/.test(c)) {
      i++;
      while (i < text.length && /[\d.]/.test(text[i]!)) i++;
      tokens.push({ kind: "number", text: text.slice(start, i), start, end: i });
    } else if (/[<>!=]/.test(c)) {
      while (i < text.length && /[<>!=]/.test(text[i]!)) i++;
      tokens.push({ kind: "op", text: text.slice(start, i), start, end: i });
    } else {
      i++;
      if (c === "(") tokens.push({ kind: "open", text: c, start, end: i });
      else if (c === ")") tokens.push({ kind: "close", text: c, start, end: i });
      else if (c === ",") tokens.push({ kind: "comma", text: c, start, end: i });
    }
  }
  return tokens;
}

const keyword = (t: Token) => (t.kind === "word" && !t.quoted ? t.text.toUpperCase() : "");

interface State {
  step: Step;
  column?: string;
  /** The operator just written, which shapes the value offered (LIKE patterns, IS NULL). */
  operator?: string;
}

/** Walks the finished tokens of the clause to find which part of a condition comes next. */
function walk(tokens: Token[]): State {
  let state: State = { step: "column" };
  let inList = false;
  let betweenAnd = false;
  for (const t of tokens) {
    const kw = keyword(t);
    switch (state.step) {
      case "column":
        if (t.kind === "open" || kw === "NOT") break;
        state = { step: "operator", column: t.kind === "word" ? t.text : undefined };
        break;
      case "operator":
        if (kw === "NOT") break;
        state = { ...state, step: "value", operator: kw || t.text };
        inList = kw === "IN";
        break;
      case "value":
        if (state.operator === "IS") {
          if (kw !== "NOT") state = { step: "join" };
        } else if (inList) {
          if (t.kind === "close") {
            inList = false;
            state = { step: "join" };
          }
        } else {
          betweenAnd = state.operator === "BETWEEN";
          state = { ...state, step: "join" };
        }
        break;
      case "join":
        if (betweenAnd && kw === "AND") {
          betweenAnd = false;
          state = { ...state, step: "value", operator: "AND" };
        } else if (kw === "AND" || kw === "OR") {
          state = { step: "column" };
        }
        break;
    }
  }
  return state;
}

/** A column name as SQL needs it: quoted unless it's a plain lower-case (Postgres) or word (MySQL) name. */
export function identifier(service: SqlService, name: string): string {
  if (service === "mysql") return /^[A-Za-z_][\w$]*$/.test(name) ? name : `\`${name.replaceAll("`", "``")}\``;
  return /^[a-z_][a-z0-9_$]*$/.test(name) ? name : `"${name.replaceAll('"', '""')}"`;
}

const NUMERIC = /int|serial|numeric|decimal|float|double|real|money/i;
const BOOLEAN = /^bool/i;

/** [value] as a SQL literal for a column of [type]. */
function literal(type: string, value: string): string {
  if (NUMERIC.test(type) && /^-?\d+(\.\d+)?$/.test(value)) return value;
  if (BOOLEAN.test(type) && /^(t|f|true|false|0|1)$/i.test(value)) return /^(t|true|1)$/i.test(value) ? "TRUE" : "FALSE";
  return `'${value.replaceAll("'", "''")}'`;
}

function operators(service: SqlService): Suggestion[] {
  return [
    { label: "=", insert: "= ", detail: "equals" },
    { label: "!=", insert: "!= ", detail: "not equal" },
    { label: ">", insert: "> ", detail: "greater than" },
    { label: ">=", insert: ">= ", detail: "at least" },
    { label: "<", insert: "< ", detail: "less than" },
    { label: "<=", insert: "<= ", detail: "at most" },
    { label: "LIKE", insert: "LIKE ", detail: "pattern, % matches anything" },
    ...(service === "postgres" ? [{ label: "ILIKE", insert: "ILIKE ", detail: "pattern, any case" }] : []),
    { label: "NOT LIKE", insert: "NOT LIKE ", detail: "doesn't match the pattern" },
    { label: "IN", insert: "IN () ", detail: "one of a list", caret: 4 },
    { label: "BETWEEN", insert: "BETWEEN ", detail: "within a range, inclusive" },
    { label: "IS NULL", insert: "IS NULL ", detail: "has no value" },
    { label: "IS NOT NULL", insert: "IS NOT NULL ", detail: "has a value" },
  ];
}

function values(state: State, typed: string, context: Context): Suggestion[] {
  if (state.operator === "IS") {
    return [
      { label: "NULL", insert: "NULL " },
      { label: "NOT NULL", insert: "NOT NULL " },
    ];
  }
  const column = context.columns.find((c) => c.name === state.column);
  const type = column?.type ?? "";
  const like = state.operator === "LIKE" || state.operator === "ILIKE";
  const templates: Suggestion[] = like
    ? [
        { label: "'%…%'", insert: "'%%' ", detail: "contains", caret: 2 },
        { label: "'…%'", insert: "'%' ", detail: "starts with", caret: 1 },
      ]
    : BOOLEAN.test(type)
      ? [
          { label: "TRUE", insert: "TRUE " },
          { label: "FALSE", insert: "FALSE " },
        ]
      : NUMERIC.test(type)
        ? []
        : [{ label: "'…'", insert: "'' ", detail: "text", caret: 1 }];
  const seen = new Set<string>();
  const samples: Suggestion[] = [];
  for (const v of column ? context.values(column.name) : []) {
    if (v === null || seen.has(v) || v.length > 200) continue;
    seen.add(v);
    const text = literal(type, v);
    samples.push({ label: text, insert: `${text} `, detail: "on this page" });
  }
  // A typed value narrows the samples by their text; the templates only help before anything is typed. A value
  // typed in full isn't offered again, so ↵ goes on to apply the filter.
  if (!typed) return [...templates, ...samples];
  const needle = typed.toLowerCase();
  const text = (s: Suggestion) => s.label.toLowerCase();
  return samples.filter((s) => text(s) !== needle && text(s).replace(/^'/, "").startsWith(needle.replace(/^'/, "")));
}

/**
 * What can come next at [caret] in [text], a WHERE clause: the table's columns, then operators, then values (from
 * [Context.values]), then AND / OR. Undefined mid-word, where inserting would split a name.
 */
export function whereSuggestions(text: string, caret: number, context: Context): Suggestions | undefined {
  if (/^[\w$]/.test(text.slice(caret))) return undefined;
  const tokens = tokenize(text.slice(0, caret));
  // The last token is still being typed when it runs up to the caret (a word, a number, or an unclosed string).
  const last = tokens.at(-1);
  const typing =
    last?.end === caret && ((last.kind === "word" && !last.quoted) || last.kind === "number" || (last.kind === "string" && last.open));
  const done = typing ? tokens.slice(0, -1) : tokens;
  const typed = typing ? text.slice(last.start, caret) : "";
  const start = typing ? last.start : caret;
  // Inside the quotes of a '' value template, the closing quote goes too: the chosen value brings its own.
  const end = typing && last.kind === "string" && text[caret] === "'" ? caret + 1 : caret;
  const state = walk(done);
  const lower = typed.toLowerCase();
  const byPrefix = (items: Suggestion[]) => items.filter((s) => s.label.toLowerCase().startsWith(lower));

  let items: Suggestion[];
  switch (state.step) {
    case "column": {
      // A name typed in full comes first, so ↵ confirms it and moves on to the operator.
      const name = (c: ColumnInfo) => c.name.toLowerCase();
      const exact = context.columns.filter((c) => name(c) === lower);
      const prefix = context.columns.filter((c) => name(c) !== lower && name(c).startsWith(lower));
      const inside = context.columns.filter((c) => !name(c).startsWith(lower) && name(c).includes(lower));
      items = [...exact, ...prefix, ...inside]
        .slice(0, MAX)
        .map((c) => ({ label: c.name, insert: `${identifier(context.service, c.name)} `, detail: c.type }));
      break;
    }
    case "operator":
      items = byPrefix(operators(context.service));
      break;
    case "value":
      items = values(state, typed, context).slice(0, MAX);
      break;
    case "join":
      items = byPrefix([
        { label: "AND", insert: "AND ", detail: "both conditions" },
        { label: "OR", insert: "OR ", detail: "either condition" },
      ]);
      break;
  }
  return { step: state.step, start, end, items };
}

/**
 * [text] with [suggestion] in place of the typed span, and where the caret goes after it. The suggestion is set apart
 * from what precedes it by a space (`'x'` then `AND`), and its trailing space isn't doubled when one already follows.
 */
export function applySuggestion(text: string, at: Suggestions, suggestion: Suggestion): { text: string; caret: number } {
  const before = text.slice(0, at.start);
  const rest = text.slice(at.end);
  const lead = before && !/[\s(]$/.test(before) ? " " : "";
  const shared = rest.startsWith(" ") && suggestion.insert.endsWith(" ");
  const insert = lead + (shared ? suggestion.insert.slice(0, -1) : suggestion.insert);
  // With the trailing space shared, the caret still lands after it, ready for the next step.
  const caret = suggestion.caret != null ? lead.length + suggestion.caret : insert.length + (shared ? 1 : 0);
  return { text: before + insert + rest, caret: at.start + caret };
}
