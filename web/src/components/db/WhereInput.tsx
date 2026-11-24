import { type ReactNode, useEffect, useRef, useState } from "react";
import { FilterIcon } from "lucide-react";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { cn } from "@/lib/utils";
import type { ColumnInfo, SqlService } from "@/types";
import { applySuggestion, type Step, type Suggestion, whereSuggestions } from "./whereSuggest";

const STEP_LABELS: Record<Step, string> = { column: "Column", operator: "Operator", value: "Value", join: "Combine with" };

/**
 * The rows filter: a WHERE clause with a dropdown that walks each condition: column, operator, value, then AND / OR.
 * Picking one moves on to the next step.
 * ↵ or Tab inserts the highlighted item, ↑ / ↓ pick another, Esc closes the list. Once a condition is complete the
 * list offers AND / OR, and there ↵ applies the filter unless one was picked with ↑ / ↓; ⌘↵ / Ctrl+↵ always applies it.
 */
export function WhereInput({
  service,
  columns,
  values,
  value,
  onChange,
  onSubmit,
  trailing,
}: {
  service: SqlService;
  columns: ColumnInfo[];
  /** Values of a column to offer, e.g. those on the rows page in view. */
  values: (column: string) => (string | null)[];
  value: string;
  onChange: (value: string) => void;
  onSubmit: () => void;
  /** The input's end addon, e.g. a clear button. */
  trailing: ReactNode;
}) {
  const input = useRef<HTMLInputElement>(null);
  const [caret, setCaret] = useState(0);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  // Set once ↑ / ↓ picks a suggestion; only then does ↵ insert AND / OR instead of applying the filter.
  const [picked, setPicked] = useState(false);
  const suggestions = open ? whereSuggestions(value, caret, { service, columns, values }) : undefined;
  const matches = suggestions?.items ?? [];
  const shown = matches.length > 0;
  const highlighted = Math.min(active, matches.length - 1);
  const list = useRef<HTMLUListElement>(null);

  // Keep the highlighted item visible as ↑ / ↓ move through a list that scrolls.
  useEffect(() => {
    list.current?.querySelector('[aria-selected="true"]')?.scrollIntoView({ block: "nearest" });
  }, [highlighted, shown]);

  const edit = (next: string, nextCaret: number) => {
    onChange(next);
    setCaret(nextCaret);
    setActive(0);
    setPicked(false);
    setOpen(true);
  };

  // The list stays open, now offering the next step at the new caret.
  const insert = (suggestion: Suggestion) => {
    if (!suggestions) return;
    const next = applySuggestion(value, suggestions, suggestion);
    onChange(next.text);
    setCaret(next.caret);
    setActive(0);
    setPicked(false);
    // The input re-renders with the new value first; then the caret goes after the inserted text.
    requestAnimationFrame(() => input.current?.setSelectionRange(next.caret, next.caret));
  };

  // Moves the caret past [char] when it is already there: the closing quote of a '' template, or of IN ().
  const stepOver = (e: React.KeyboardEvent<HTMLInputElement>, char: string) => {
    const at = e.currentTarget.selectionStart ?? 0;
    if (at !== e.currentTarget.selectionEnd || value[at] !== char) return false;
    if (char === "'" && (value.slice(0, at).match(/'/g)?.length ?? 0) % 2 === 0) return false;
    e.preventDefault();
    e.currentTarget.setSelectionRange(at + 1, at + 1);
    setCaret(at + 1);
    setOpen(true);
    return true;
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    const insertOnEnter = suggestions?.step !== "join" || picked;
    if ((e.key === "'" || e.key === ")") && stepOver(e, e.key)) return;
    if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) {
      setOpen(false);
      onSubmit();
    } else if (shown && (e.key === "ArrowDown" || e.key === "ArrowUp")) {
      e.preventDefault();
      setActive((highlighted + (e.key === "ArrowDown" ? 1 : matches.length - 1)) % matches.length);
      setPicked(true);
    } else if (shown && (e.key === "Tab" || (e.key === "Enter" && insertOnEnter))) {
      e.preventDefault();
      insert(matches[highlighted]!);
    } else if (shown && e.key === "Escape") {
      setOpen(false);
    } else if (e.key === "Enter") {
      setOpen(false);
      onSubmit();
    }
  };

  return (
    <div className="relative min-w-64 flex-1">
      {/* As tall as the small buttons beside it in the rows toolbar. */}
      <InputGroup className="h-7">
        <InputGroupAddon>
          <FilterIcon />
          <span className="font-mono text-xs">WHERE</span>
        </InputGroupAddon>
        <InputGroupInput
          ref={input}
          className="font-mono text-xs md:text-xs"
          value={value}
          placeholder="price > 10 AND name LIKE 'A%'"
          spellCheck={false}
          autoComplete="off"
          role="combobox"
          aria-expanded={shown}
          aria-autocomplete="list"
          onChange={(e) => edit(e.target.value, e.target.selectionStart ?? e.target.value.length)}
          onSelect={(e) => setCaret(e.currentTarget.selectionStart ?? 0)}
          onFocus={() => setOpen(true)}
          onClick={() => setOpen(true)}
          onBlur={() => setOpen(false)}
          onKeyDown={onKeyDown}
        />
        <InputGroupAddon align="inline-end">{trailing}</InputGroupAddon>
      </InputGroup>
      {shown && (
        <ul
          ref={list}
          role="listbox"
          aria-label={STEP_LABELS[suggestions!.step]}
          className="absolute top-full left-0 z-30 mt-1 max-h-80 w-full max-w-sm overflow-y-auto rounded-md border bg-popover p-1 text-popover-foreground shadow-md"
          // Keep focus in the input, so picking an item doesn't blur and close the list first.
          onMouseDown={(e) => e.preventDefault()}
        >
          <li role="presentation" className="px-2 pt-1 pb-1.5 text-[11px] font-medium text-muted-foreground">
            {STEP_LABELS[suggestions!.step]}
          </li>
          {matches.map((c, i) => (
            <li
              key={c.label}
              role="option"
              aria-selected={i === highlighted}
              className={cn(
                "flex cursor-pointer items-center gap-3 rounded-sm px-2 py-1.5 text-xs",
                i === highlighted && "bg-accent text-accent-foreground",
              )}
              onMouseEnter={() => setActive(i)}
              onClick={() => insert(c)}
            >
              <span className="min-w-0 flex-1 truncate font-mono">{c.label}</span>
              {c.detail && <span className="shrink-0 truncate text-[11px] text-muted-foreground">{c.detail}</span>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
