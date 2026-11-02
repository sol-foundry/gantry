import { useState } from "react";
import { KeyRoundIcon } from "lucide-react";
import { toast } from "sonner";
import { api } from "@/api";
import { ErrorAlert } from "@/components/common";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Label } from "@/components/ui/label";
import { Spinner } from "@/components/ui/spinner";
import { useAction } from "@/hooks";
import type { SqlService, TableDetail } from "@/types";

/** One input per column; blank fields are left out so the column default (or identity) applies. */
export function InsertRowModal({
  app,
  service,
  table,
  onClose,
  onInserted,
}: {
  app: string;
  service: SqlService;
  table: TableDetail;
  onClose: () => void;
  onInserted: () => void;
}) {
  const [values, setValues] = useState<Record<string, string>>({});
  const [nulls, setNulls] = useState<Set<string>>(new Set());
  const { busy, error, run } = useAction();

  const toggleNull = (column: string) => {
    const next = new Set(nulls);
    if (next.has(column)) next.delete(column);
    else next.add(column);
    setNulls(next);
  };

  const insert = async () => {
    const row: Record<string, string | null> = {};
    for (const column of table.columns) {
      if (nulls.has(column.name)) row[column.name] = null;
      else if ((values[column.name] ?? "") !== "") row[column.name] = values[column.name];
    }
    if (await run("insert", () => api.db(app, service).insert(table.schema, table.name, row))) {
      toast.success(`Inserted a row into ${table.name}`);
      onInserted();
    }
  };

  return (
    <Dialog open onOpenChange={(open) => !open && !busy && onClose()}>
      <DialogContent className="sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>Insert into {table.name}</DialogTitle>
          <DialogDescription>Leave a field blank to use the column default.</DialogDescription>
        </DialogHeader>
        <ScrollArea className="-mx-4 max-h-[60vh]" contentClassName="grid gap-3 px-4">
          {table.columns.map((column) => (
            <div key={column.name} className="grid gap-1.5">
              <Label htmlFor={`insert-${column.name}`} className="font-mono text-xs">
                {column.name}
                {column.primaryKey && <KeyRoundIcon className="size-3 text-amber-500" />}
                {!column.nullable && <span className="text-destructive">*</span>}
              </Label>
              <div className="flex items-center gap-3">
                <Input
                  id={`insert-${column.name}`}
                  className="font-mono text-xs"
                  value={values[column.name] ?? ""}
                  disabled={nulls.has(column.name)}
                  placeholder={[column.type, column.default && `default ${column.default}`, column.extra].filter(Boolean).join(" · ")}
                  onChange={(e) => setValues({ ...values, [column.name]: e.target.value })}
                />
                {column.nullable && (
                  <Label className="shrink-0 text-xs font-normal text-muted-foreground">
                    <Checkbox checked={nulls.has(column.name)} onCheckedChange={() => toggleNull(column.name)} />
                    NULL
                  </Label>
                )}
              </div>
            </div>
          ))}
        </ScrollArea>
        <ErrorAlert message={error} />
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button disabled={!!busy} onClick={insert}>
            {busy && <Spinner />}
            Insert row
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
