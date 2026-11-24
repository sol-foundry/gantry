// Checks the WHERE clause suggestions without a browser: `npm run check:where-suggest` (Node strips the types).
import assert from "node:assert/strict";
import { applySuggestion, whereSuggestions, type Context } from "../src/components/db/whereSuggest.ts";
import type { ColumnInfo } from "../src/types.ts";

const column = (name: string, type: string): ColumnInfo => ({ name, type, nullable: true, default: null, primaryKey: false, extra: "" });
const context: Context = {
  service: "postgres",
  columns: [column("id", "uuid"), column("task_id", "uuid"), column("status", "text"), column("score", "integer"), column("done", "boolean"), column("Label", "text")],
  values: (name) => (name === "status" ? ["open", "closed", "open", null, "it's"] : name === "score" ? ["10", "7"] : []),
};
const at = (text: string, caret = text.length) => whereSuggestions(text, caret, context);
const labels = (text: string) => at(text)?.items.map((s) => s.label);
const step = (text: string) => at(text)?.step;

// The steps of a condition, repeated after AND / OR.
assert.equal(step(""), "column");
assert.equal(step("status "), "operator");
assert.equal(step("status = "), "value");
assert.equal(step("status = 'open' "), "join");
assert.equal(step("status = 'open' AND "), "column");
assert.equal(step("status = 'open' or ("), "column");
assert.equal(step("status IS NULL "), "join");
assert.equal(step("status IS NOT NULL "), "join");
assert.equal(step("status IN ('a', 'b') "), "join");
assert.equal(step("score BETWEEN 1 "), "join");
assert.equal(step("score BETWEEN 1 AND "), "value");
assert.equal(step("score BETWEEN 1 AND 5 "), "join");
assert.equal(step("score NOT LIKE "), "value");

// Columns: a name typed in full first, then prefix matches, then ones containing the word.
assert.deepEqual(labels("i"), ["id", "task_id"]);
assert.deepEqual(labels("id"), ["id", "task_id"]);
assert.deepEqual(labels("stat"), ["status"]);
assert.deepEqual(labels("score > 1 and sta"), ["status"]);
// Mid-word and inside a string, nothing is offered as a column.
assert.equal(at("status", 2), undefined);

// Operators narrow by what's typed; ILIKE is Postgres only.
assert.deepEqual(labels("status is"), ["IS NULL", "IS NOT NULL"]);
assert.ok(labels("status ")!.includes("ILIKE"));
assert.ok(!whereSuggestions("status ", 7, { ...context, service: "mysql" })!.items.some((s) => s.label === "ILIKE"));
assert.deepEqual(labels("status IS "), ["NULL", "NOT NULL"]);

// Values: a template for the type, then distinct values from the page, quoted and escaped for SQL.
assert.deepEqual(labels("status = "), ["'…'", "'open'", "'closed'", "'it''s'"]);
assert.deepEqual(labels("status = 'cl"), ["'closed'"]);
assert.deepEqual(labels("score > "), ["10", "7"]);
assert.deepEqual(labels("score > 1"), ["10"]);
assert.deepEqual(labels("score > 10"), []);
assert.deepEqual(labels("status = 'op"), ["'open'"]);
assert.deepEqual(labels("done = "), ["TRUE", "FALSE"]);
assert.deepEqual(labels("status LIKE ").slice(0, 2), ["'%…%'", "'…%'"]);

assert.deepEqual(labels("status = 'open' a"), ["AND"]);

// Applying replaces the typed word, quotes names that need it, and places the caret.
const pick = (text: string, label: string) => {
  const s = at(text)!;
  return applySuggestion(text, s, s.items.find((i) => i.label === label)!);
};
assert.deepEqual(pick("sta", "status"), { text: "status ", caret: 7 });
assert.deepEqual(pick("lab", "Label"), { text: '"Label" ', caret: 8 });
assert.deepEqual(pick("status ", "IN"), { text: "status IN () ", caret: 11 });
assert.deepEqual(pick("status LIKE ", "'%…%'"), { text: "status LIKE '%%' ", caret: 14 });
assert.deepEqual(pick("status = 'op", "'open'"), { text: "status = 'open' ", caret: 16 });
// No doubled space when one already follows; the caret still lands past it, on the next step.
const mid = whereSuggestions("sta AND score > 1", 3, context)!;
assert.deepEqual(applySuggestion("sta AND score > 1", mid, mid.items[0]!), { text: "status AND score > 1", caret: 7 });
// Set apart from text typed without spaces.
assert.deepEqual(pick("status='open'", "AND"), { text: "status='open' AND ", caret: 18 });
assert.deepEqual(pick("status=", "'open'"), { text: "status= 'open' ", caret: 15 });
assert.deepEqual(pick("status IN (", "'open'"), { text: "status IN ('open' ", caret: 18 });
// Inside a '' template the chosen value replaces both quotes.
const template = "status = '' ";
const inQuotes = whereSuggestions(template, 10, context)!;
assert.equal(inQuotes.step, "value");
assert.deepEqual(
  applySuggestion(template, inQuotes, inQuotes.items.find((i) => i.label === "'closed'")!),
  { text: "status = 'closed' ", caret: 18 },
);

console.log("where-suggest: ok");
