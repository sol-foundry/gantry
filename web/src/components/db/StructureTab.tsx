import { useState } from "react";
import { KeyRoundIcon, PencilIcon, PlusIcon, Trash2Icon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { ColumnDef, ColumnInfo, SqlService, TableDetail } from "@/types";
import { ColumnFields, cleaned } from "./ColumnFields";
import type { RequestDdl } from "./format";

/** Columns of a table, with add / edit / drop going through the shared SQL review. */
export function StructureTab({ service, table, onDdl }: { service: SqlService; table: TableDetail; onDdl: RequestDdl }) {
  const [editing, setEditing] = useState<ColumnInfo>();
  const [adding, setAdding] = useState(false);
  const editable = table.kind === "table";
  const base = { schema: table.schema, table: table.name };

  return (
    <div className="flex flex-col gap-3">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Column</TableHead>
            <TableHead>Type</TableHead>
            <TableHead>Nullable</TableHead>
            <TableHead>Default</TableHead>
            <TableHead>Extra</TableHead>
            {editable && <TableHead className="w-20" />}
          </TableRow>
        </TableHeader>
        <TableBody>
          {table.columns.map((c) => (
            <TableRow key={c.name}>
              <TableCell className="font-mono text-xs">
                <span className="inline-flex items-center gap-1.5">
                  {c.primaryKey && <KeyRoundIcon className="size-3 text-amber-500" aria-label="Primary key" />}
                  {c.name}
                </span>
              </TableCell>
              <TableCell>
                <Badge variant="secondary" className="font-mono text-[11px]">
                  {c.type}
                </Badge>
              </TableCell>
              <TableCell className="text-muted-foreground">{c.nullable ? "yes" : "no"}</TableCell>
              <TableCell className="max-w-60 truncate font-mono text-xs" title={c.default ?? undefined}>
                {c.default ?? <span className="text-muted-foreground">—</span>}
              </TableCell>
              <TableCell className="text-xs text-muted-foreground">{c.extra}</TableCell>
              {editable && (
                <TableCell>
                  <div className="flex justify-end gap-0.5">
                    <Button variant="ghost" size="icon-sm" aria-label={`Edit ${c.name}`} title="Edit column" onClick={() => setEditing(c)}>
                      <PencilIcon />
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      className="text-destructive hover:text-destructive"
                      aria-label={`Drop ${c.name}`}
                      title="Drop column"
                      onClick={() =>
                        onDdl(`Drop column ${c.name}`, { op: "drop_column", ...base, column: c.name }, `drop column ${c.name} from ${table.name}`)
                      }
                    >
                      <Trash2Icon />
                    </Button>
                  </div>
                </TableCell>
              )}
            </TableRow>
          ))}
        </TableBody>
      </Table>
      {editable && (
        <div>
          <Button variant="outline" size="sm" onClick={() => setAdding(true)}>
            <PlusIcon />
            Add column
          </Button>
        </div>
      )}
      {adding && (
        <ColumnDialog
          title={`Add column to ${table.name}`}
          service={service}
          initial={{ name: "", type: "", nullable: true, default: "", extra: "" }}
          onClose={() => setAdding(false)}
          onSubmit={(definition) => {
            setAdding(false);
            onDdl(`Add column ${definition.name}`, { op: "add_column", ...base, definition }, `add column ${definition.name} to ${table.name}`);
          }}
        />
      )}
      {editing && (
        <ColumnDialog
          title={`Edit column ${editing.name}`}
          service={service}
          initial={{ name: editing.name, type: editing.type, nullable: editing.nullable, default: editing.default ?? "", extra: editing.extra }}
          onClose={() => setEditing(undefined)}
          onSubmit={(definition) => {
            const column = editing.name;
            setEditing(undefined);
            onDdl(`Alter column ${column}`, { op: "alter_column", ...base, column, definition }, `alter column ${column} in ${table.name}`);
          }}
        />
      )}
    </div>
  );
}

function ColumnDialog({
  title,
  service,
  initial,
  onClose,
  onSubmit,
}: {
  title: string;
  service: SqlService;
  initial: ColumnDef;
  onClose: () => void;
  onSubmit: (column: ColumnDef) => void;
}) {
  const [column, setColumn] = useState(initial);
  const valid = column.name.trim() !== "" && column.type.trim() !== "";
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>Type, default and extra are SQL. You&apos;ll review the statement before anything runs.</DialogDescription>
        </DialogHeader>
        <ColumnFields service={service} column={column} onChange={setColumn} />
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button disabled={!valid} onClick={() => onSubmit(cleaned(column))}>
            Review SQL
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
