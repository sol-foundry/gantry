import { useState } from "react";
import { KeyRoundIcon } from "lucide-react";
import { toast } from "sonner";
import { api } from "@/api";
import { ErrorAlert } from "@/components/common";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Label } from "@/components/ui/label";
import { Spinner } from "@/components/ui/spinner";
import { useAction } from "@/hooks";
import type { SqlService, TableDetail } from "@/types";
import { sameValue, ValueInput } from "./ValueInput";

/** A row to change: its primary key, its current values by column, and the columns whose values the server cut. */
export interface RowEdit {
  key: Record<string, string | null>;
  values: Record<string, string | null>;
  clipped: Set<string>;
}

/**
 * One input per column. Inserting, blank fields are left out so the column default (or identity) applies. With [edit],
 * the fields start from the row's values and only the ones changed are written, by primary key.
 */
export function RowModal({
  app,
  service,
  table,
  edit,
  onClose,
  onSaved,
}: {
  app: string;
  service: SqlService;
  table: TableDetail;
  edit?: RowEdit;
  onClose: () => void;
  onSaved: () => void;
}) {
  const initial = edit?.values ?? {};
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(Object.entries(initial).map(([column, value]) => [column, value ?? ""])),
  );
  const [nulls, setNulls] = useState<Set<string>>(
    () => new Set(Object.entries(initial).flatMap(([column, value]) => (value === null ? [column] : []))),
  );
  const { busy, error, run } = useAction();

  const toggleNull = (column: string) => {
    const next = new Set(nulls);
    if (next.has(column)) next.delete(column);
    else next.add(column);
    setNulls(next);
  };

  const value = (column: string) => (nulls.has(column) ? null : (values[column] ?? ""));

  const insert = async () => {
    const row: Record<string, string | null> = {};
    for (const column of table.columns) {
      if (nulls.has(column.name)) row[column.name] = null;
      else if ((values[column.name] ?? "") !== "") row[column.name] = values[column.name];
    }
    if (await run("insert", () => api.db(app, service).insert(table.schema, table.name, row))) {
      toast.success(`Inserted a row into ${table.name}`);
      onSaved();
    }
  };

  const update = async (row: RowEdit) => {
    // Only what changed, and never a value the server cut: writing it back would replace the full value with the cut one.
    const changed = Object.fromEntries(
      table.columns
        .filter((column) => !row.clipped.has(column.name) && !sameValue(column, value(column.name), row.values[column.name] ?? null))
        .map((column) => [column.name, value(column.name)]),
    );
    if (Object.keys(changed).length === 0) {
      onClose();
      return;
    }
    if (await run("update", () => api.db(app, service).update(table.schema, table.name, row.key, changed))) {
      toast.success(`Updated ${Object.keys(changed).join(", ")}`);
      onSaved();
    }
  };

  return (
    <Dialog open onOpenChange={(open) => !open && !busy && onClose()}>
      <DialogContent className="sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>{edit ? `Edit row in ${table.name}` : `Insert into ${table.name}`}</DialogTitle>
          <DialogDescription>
            {edit ? "Only the fields you change are saved. Tick NULL to clear a value." : "Leave a field blank to use the column default."}
          </DialogDescription>
        </DialogHeader>
        <ScrollArea className="-mx-4 max-h-[60vh]" contentClassName="grid gap-3 px-4">
          {table.columns.map((column) => {
            const clipped = edit?.clipped.has(column.name) ?? false;
            return (
              <div key={column.name} className="grid gap-1.5">
                <Label htmlFor={`row-${column.name}`} className="font-mono text-xs">
                  {column.name}
                  {column.primaryKey && <KeyRoundIcon className="size-3 text-amber-500" />}
                  {!column.nullable && <span className="text-destructive">*</span>}
                </Label>
                <div className="flex items-center gap-3">
                  <ValueInput
                    id={`row-${column.name}`}
                    service={service}
                    column={column}
                    value={values[column.name] ?? ""}
                    disabled={nulls.has(column.name) || clipped}
                    placeholder={[column.type, column.default && `default ${column.default}`, column.extra].filter(Boolean).join(" · ")}
                    onChange={(next) => setValues({ ...values, [column.name]: next })}
                  />
                  {column.nullable && (
                    <Label className="shrink-0 text-xs font-normal text-muted-foreground">
                      <Checkbox checked={nulls.has(column.name)} disabled={clipped} onCheckedChange={() => toggleNull(column.name)} />
                      NULL
                    </Label>
                  )}
                </div>
                {clipped && <p className="text-xs text-muted-foreground">Cut at 64 KB here, so it can't be edited; change it with SQL.</p>}
              </div>
            );
          })}
        </ScrollArea>
        <ErrorAlert message={error} />
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button disabled={!!busy} onClick={() => (edit ? update(edit) : insert())}>
            {busy && <Spinner />}
            {edit ? "Save changes" : "Insert row"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
