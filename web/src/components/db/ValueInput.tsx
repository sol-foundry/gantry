import type { ComponentProps } from "react";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";
import type { ColumnInfo, SqlService } from "@/types";

/** How a column's values are edited. Timestamps stay text: their fractions and zones don't fit the browser pickers. */
export type ValueKind = "boolean" | "integer" | "decimal" | "date" | "time" | "json" | "text";

export function valueKind(type: string): ValueKind {
  const t = type.toLowerCase();
  // MySQL has no boolean type: BOOLEAN columns are tinyint(1).
  if (/^bool/.test(t) || t === "tinyint(1)" || t === "bit(1)") return "boolean";
  if (/^(tiny|small|medium|big)?int|^integer|serial/.test(t)) return "integer";
  if (/^(numeric|decimal|real|double|float)/.test(t)) return "decimal";
  if (t === "date") return "date";
  if (/^time(\(\d\))?( without time zone)?$/.test(t)) return "time";
  if (/^jsonb?$/.test(t)) return "json";
  return "text";
}

const TRUE = /^(t|true|1|y|yes|on)$/i;
const FALSE = /^(f|false|0|n|no|off)$/i;

/** A boolean as "true" / "false", whichever way the database wrote it (Postgres t / f, MySQL 1 / 0); else "". */
export function booleanText(value: string | null): string {
  if (value == null) return "";
  return TRUE.test(value) ? "true" : FALSE.test(value) ? "false" : "";
}

/** "true" / "false" as the database takes it. */
function writeBoolean(service: SqlService, choice: string): string {
  if (service === "mysql") return choice === "true" ? "1" : "0";
  return choice;
}

/** Whether two values of a column are the same, so an untouched t isn't saved back as true. */
export function sameValue(column: ColumnInfo, a: string | null, b: string | null): boolean {
  if (a === null || b === null) return a === b;
  return valueKind(column.type) === "boolean" ? booleanText(a) === booleanText(b) : a === b;
}

/**
 * Input attributes for a column's kind. A value that doesn't fit the typed input (a time with fractions, NaN) keeps a
 * text input, so it shows as it is rather than blank.
 */
export function inputAttrs(kind: ValueKind, value: string): ComponentProps<"input"> {
  const fits = (pattern: RegExp) => value === "" || pattern.test(value);
  switch (kind) {
    case "integer":
      return fits(/^-?\d+$/) ? { type: "number", step: 1, inputMode: "numeric" } : {};
    case "decimal":
      return fits(/^-?\d+(\.\d+)?([eE][-+]?\d+)?$/) ? { type: "number", step: "any", inputMode: "decimal" } : {};
    case "date":
      return fits(/^\d{4}-\d{2}-\d{2}$/) ? { type: "date" } : {};
    case "time":
      return fits(/^\d{2}:\d{2}(:\d{2})?$/) ? { type: "time", step: 1 } : {};
    default:
      return {};
  }
}

/** True / false for a boolean column, written the way the database takes it. */
export function BooleanSelect({
  service,
  value,
  onChange,
  disabled,
  defaultOpen,
  onOpenChange,
  id,
  className,
}: {
  service: SqlService;
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
  defaultOpen?: boolean;
  onOpenChange?: (open: boolean) => void;
  id?: string;
  className?: string;
}) {
  return (
    <Select
      value={booleanText(value) || null}
      onValueChange={(choice) => choice && onChange(writeBoolean(service, choice))}
      disabled={disabled}
      defaultOpen={defaultOpen}
      onOpenChange={onOpenChange}
    >
      <SelectTrigger id={id} className={cn("w-full font-mono text-xs", className)}>
        <SelectValue placeholder="Choose" />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value="true" className="font-mono text-xs">
          true
        </SelectItem>
        <SelectItem value="false" className="font-mono text-xs">
          false
        </SelectItem>
      </SelectContent>
    </Select>
  );
}

/** A field for one column's value in the row dialog: a select for booleans, typed inputs, a text area for JSON. */
export function ValueInput({
  service,
  column,
  value,
  onChange,
  disabled,
  id,
  placeholder,
}: {
  service: SqlService;
  column: ColumnInfo;
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
  id?: string;
  placeholder?: string;
}) {
  const kind = valueKind(column.type);
  if (kind === "boolean") return <BooleanSelect id={id} service={service} value={value} onChange={onChange} disabled={disabled} />;
  if (kind === "json") {
    const invalid = value.trim() !== "" && !isJson(value);
    return (
      <Textarea
        id={id}
        className="min-h-16 font-mono text-xs"
        value={value}
        disabled={disabled}
        placeholder={placeholder}
        spellCheck={false}
        aria-invalid={invalid || undefined}
        title={invalid ? "Not valid JSON" : undefined}
        onChange={(e) => onChange(e.target.value)}
      />
    );
  }
  return (
    <Input
      id={id}
      className="font-mono text-xs"
      value={value}
      disabled={disabled}
      placeholder={placeholder}
      {...inputAttrs(kind, value)}
      onChange={(e) => onChange(e.target.value)}
    />
  );
}

function isJson(text: string): boolean {
  try {
    JSON.parse(text);
    return true;
  } catch {
    return false;
  }
}
