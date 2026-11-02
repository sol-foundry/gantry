import { Checkbox } from "@/components/ui/checkbox";
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import type { ColumnDef, SqlService } from "@/types";
import { commonTypes, identityExtra } from "./format";

/** Shared datalist of common column types; render once per form and point inputs at `listId`. */
export function TypeOptions({ service, listId }: { service: SqlService; listId: string }) {
  return (
    <datalist id={listId}>
      {commonTypes[service].map((t) => (
        <option key={t} value={t} />
      ))}
    </datalist>
  );
}

/** The editable parts of one column, laid out as labeled fields (add/edit column). */
export function ColumnFields({
  service,
  column,
  onChange,
}: {
  service: SqlService;
  column: ColumnDef;
  onChange: (column: ColumnDef) => void;
}) {
  const listId = `types-${service}`;
  return (
    <FieldGroup className="gap-4">
      <TypeOptions service={service} listId={listId} />
      <div className="grid gap-4 sm:grid-cols-2">
        <Field>
          <FieldLabel htmlFor="column-name">Name</FieldLabel>
          <Input id="column-name" className="font-mono" value={column.name} onChange={(e) => onChange({ ...column, name: e.target.value })} />
        </Field>
        <Field>
          <FieldLabel htmlFor="column-type">Type</FieldLabel>
          <Input
            id="column-type"
            className="font-mono"
            list={listId}
            value={column.type}
            placeholder="e.g. varchar(255)"
            onChange={(e) => onChange({ ...column, type: e.target.value })}
          />
        </Field>
      </div>
      <Field>
        <FieldLabel htmlFor="column-default">Default</FieldLabel>
        <Input
          id="column-default"
          className="font-mono"
          value={column.default ?? ""}
          placeholder="SQL expression, e.g. 0, 'text', now()"
          onChange={(e) => onChange({ ...column, default: e.target.value })}
        />
      </Field>
      <Field>
        <FieldLabel htmlFor="column-extra">Extra</FieldLabel>
        <Input
          id="column-extra"
          className="font-mono"
          value={column.extra ?? ""}
          placeholder={`e.g. ${identityExtra[service]}`}
          onChange={(e) => onChange({ ...column, extra: e.target.value })}
        />
      </Field>
      <Field orientation="horizontal">
        <Checkbox id="column-nullable" checked={column.nullable} onCheckedChange={(checked) => onChange({ ...column, nullable: checked })} />
        <FieldLabel htmlFor="column-nullable" className="font-normal">
          Nullable
        </FieldLabel>
      </Field>
    </FieldGroup>
  );
}

/** Blank default/extra mean "none" to the server. */
export function cleaned(column: ColumnDef): ColumnDef {
  return {
    ...column,
    name: column.name.trim(),
    type: column.type.trim(),
    default: column.default?.trim() || null,
    extra: column.extra?.trim() || null,
  };
}
