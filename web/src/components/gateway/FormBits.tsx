import { memo, useId, type ReactNode } from "react";
import { BracesIcon, PlusIcon, Trash2Icon } from "lucide-react";
import { IconButton } from "@/components/common";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { Field, FieldDescription, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { newId, newPair, type Item, type Pair } from "./gatewayForm";

/** Placeholders a header value can use; picked from the insert menu next to a value box. */
export const PLACEHOLDERS = [
  { value: "{{userId}}", label: "User ID from a lookup step" },
  { value: "{{uid}}", label: "Token's user ID" },
  { value: "{{email}}", label: "Email" },
  { value: "{{sub}}", label: "Token subject" },
  { value: "{{errorCode}}", label: "Error code of a soft failure" },
  { value: "{{secret:NAME}}", label: "A secret (edit NAME)" },
];

/** A labelled text box with optional help under it. */
export function TextField({
  label,
  value,
  onChange,
  placeholder,
  help,
  mono = true,
  className,
  error,
}: {
  label: ReactNode;
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  help?: ReactNode;
  mono?: boolean;
  className?: string;
  /** Shown under the box, which is marked invalid. */
  error?: string;
}) {
  const id = useId();
  return (
    <Field className={className} data-invalid={!!error}>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <Input
        id={id}
        value={value}
        placeholder={placeholder}
        spellCheck={false}
        className={mono ? "font-mono text-xs md:text-xs" : undefined}
        aria-invalid={!!error}
        onChange={(e) => onChange(e.target.value)}
      />
      {error && <FieldError className="text-xs">{error}</FieldError>}
      {help && <FieldDescription className="text-xs">{help}</FieldDescription>}
    </Field>
  );
}

/** A labelled select over fixed options. */
export function SelectField({
  label,
  value,
  options,
  onChange,
  help,
  className,
}: {
  label: ReactNode;
  value: string;
  options: { value: string; label: string }[];
  onChange: (value: string) => void;
  help?: ReactNode;
  className?: string;
}) {
  return (
    <Field className={className}>
      <FieldLabel>{label}</FieldLabel>
      <SmallSelect value={value} options={options} onChange={onChange} />
      {help && <FieldDescription className="text-xs">{help}</FieldDescription>}
    </Field>
  );
}

export function SmallSelect({
  value,
  options,
  onChange,
  className = "w-full",
  ariaLabel,
}: {
  value: string;
  options: { value: string; label: string }[];
  onChange: (value: string) => void;
  className?: string;
  ariaLabel?: string;
}) {
  return (
    <Select value={value} items={options} onValueChange={(v) => v != null && onChange(v)}>
      <SelectTrigger className={className} aria-label={ariaLabel}>
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {options.map((o) => (
          <SelectItem key={o.value} value={o.value}>
            {o.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

/** A checkbox with its label to the right. */
export function CheckboxLabel({ checked, onChange, children }: { checked: boolean; onChange: (checked: boolean) => void; children: ReactNode }) {
  return (
    <label className="flex items-center gap-2 text-sm">
      <Checkbox checked={checked} onCheckedChange={(c) => onChange(c === true)} />
      {children}
    </label>
  );
}

/** Appends a common placeholder to a header value. */
function InsertMenu({ onInsert }: { onInsert: (placeholder: string) => void }) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger render={<Button variant="outline" size="icon-sm" aria-label="Insert a placeholder" title="Insert a placeholder" />}>
        <BracesIcon />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-64">
        {PLACEHOLDERS.map((p) => (
          <DropdownMenuItem key={p.value} onClick={() => onInsert(p.value)}>
            <span className="font-mono text-xs">{p.value}</span>
            <span className="ml-auto text-xs text-muted-foreground">{p.label}</span>
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** Name/value rows with add and remove, for headers, env, body fields and step maps. */
export const PairRows = memo(function PairRows({
  rows,
  onChange,
  namePlaceholder = "Header name",
  valuePlaceholder = "Value",
  addLabel = "Add header",
  insert = false,
}: {
  rows: Pair[];
  onChange: (rows: Pair[]) => void;
  namePlaceholder?: string;
  valuePlaceholder?: string;
  addLabel?: string;
  /** Offer the placeholder menu next to each value. */
  insert?: boolean;
}) {
  const set = (id: number, patch: Partial<Pair>) => onChange(rows.map((r) => (r.id === id ? { ...r, ...patch } : r)));
  return (
    <div className="flex flex-col gap-2">
      {rows.map((row) => (
        <div key={row.id} className="flex items-center gap-2">
          <Input
            aria-label={namePlaceholder}
            value={row.name}
            placeholder={namePlaceholder}
            spellCheck={false}
            className="w-2/5 font-mono text-xs md:text-xs"
            onChange={(e) => set(row.id, { name: e.target.value })}
          />
          <Input
            aria-label={valuePlaceholder}
            value={row.value}
            placeholder={valuePlaceholder}
            spellCheck={false}
            className="flex-1 font-mono text-xs md:text-xs"
            onChange={(e) => set(row.id, { value: e.target.value })}
          />
          {insert && <InsertMenu onInsert={(p) => set(row.id, { value: row.value + p })} />}
          <IconButton label="Remove" onClick={() => onChange(rows.filter((r) => r.id !== row.id))}>
            <Trash2Icon />
          </IconButton>
        </div>
      ))}
      <div>
        <Button variant="outline" size="sm" onClick={() => onChange([...rows, newPair()])}>
          <PlusIcon />
          {addLabel}
        </Button>
      </div>
    </div>
  );
});

/** "Deny" or "Continue with code", as `deny` / `continue:<code>`. */
export function FailureField({
  label,
  value,
  onChange,
  continueLabel,
  help,
}: {
  label: ReactNode;
  value: string;
  onChange: (value: string) => void;
  continueLabel: string;
  help?: ReactNode;
}) {
  const kind = value.startsWith("continue:") ? "continue" : "deny";
  const code = kind === "continue" ? value.slice("continue:".length) : "";
  return (
    <Field>
      <FieldLabel>{label}</FieldLabel>
      <div className="flex gap-2">
        <SmallSelect
          className="w-48"
          ariaLabel={typeof label === "string" ? label : undefined}
          value={kind}
          options={[
            { value: "deny", label: "Deny" },
            { value: "continue", label: continueLabel },
          ]}
          onChange={(k) => onChange(k === "deny" ? "deny" : `continue:${code || "error"}`)}
        />
        {kind === "continue" && (
          <Input
            aria-label="Error code"
            value={code}
            placeholder="error code"
            spellCheck={false}
            className="flex-1 font-mono text-xs md:text-xs"
            onChange={(e) => onChange(`continue:${e.target.value}`)}
          />
        )}
      </div>
      {help && <FieldDescription className="text-xs">{help}</FieldDescription>}
    </Field>
  );
}

/** A heading with help inside a card, for groups of fields such as "Lookup" or "Map". */
export function SubHeading({ title, help }: { title: string; help?: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <div className="text-sm font-medium">{title}</div>
      {help && <p className="text-xs text-muted-foreground">{help}</p>}
    </div>
  );
}

/** Inline code in help text, such as a placeholder. */
export function Code({ children }: { children: ReactNode }) {
  return <code className="rounded bg-muted px-1 py-0.5 font-mono text-[0.85em] text-foreground">{children}</code>;
}

/** A list edited one item per box, so an item may hold any text, commas included. */
export const ListRows = memo(function ListRows({
  rows,
  onChange,
  placeholder,
  addLabel = "Add",
  label,
}: {
  rows: Item[];
  onChange: (rows: Item[]) => void;
  placeholder?: string;
  addLabel?: string;
  /** Accessible name of each box. */
  label: string;
}) {
  return (
    <div className="flex flex-col gap-2">
      {rows.map((row) => (
        <div key={row.id} className="flex items-center gap-2">
          <Input
            aria-label={label}
            value={row.value}
            placeholder={placeholder}
            spellCheck={false}
            className="flex-1 font-mono text-xs md:text-xs"
            onChange={(e) => onChange(rows.map((r) => (r.id === row.id ? { ...r, value: e.target.value } : r)))}
          />
          <IconButton label="Remove" onClick={() => onChange(rows.filter((r) => r.id !== row.id))}>
            <Trash2Icon />
          </IconButton>
        </div>
      ))}
      <div>
        <Button variant="outline" size="sm" onClick={() => onChange([...rows, { id: newId(), value: "" }])}>
          <PlusIcon />
          {addLabel}
        </Button>
      </div>
    </div>
  );
});

/** A labelled list of items with help. */
export function ListField({
  label,
  rows,
  onChange,
  placeholder,
  addLabel,
  help,
  className,
}: {
  label: string;
  rows: Item[];
  onChange: (rows: Item[]) => void;
  placeholder?: string;
  addLabel?: string;
  help?: ReactNode;
  className?: string;
}) {
  return (
    <Field className={className}>
      <FieldLabel>{label}</FieldLabel>
      <ListRows rows={rows} onChange={onChange} placeholder={placeholder} addLabel={addLabel} label={label} />
      {help && <FieldDescription className="text-xs">{help}</FieldDescription>}
    </Field>
  );
}
