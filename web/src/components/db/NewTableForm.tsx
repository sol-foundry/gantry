import { useState } from "react";
import { KeyRoundIcon, PlusIcon, XIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Field, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { ColumnDef, SqlService } from "@/types";
import { TypeOptions, cleaned } from "./ColumnFields";
import { identityExtra, type RequestDdl } from "./format";

const blankColumn = (): ColumnDef => ({ name: "", type: "", nullable: true, default: "", extra: "", primaryKey: false });

/** Column editor for a new table; the CREATE TABLE goes through the shared SQL review. */
export function NewTableForm({
  service,
  schemas,
  defaultSchema,
  onDdl,
  onCancel,
}: {
  service: SqlService;
  schemas: string[];
  defaultSchema: string;
  onDdl: RequestDdl;
  onCancel: () => void;
}) {
  const [name, setName] = useState("");
  const [schema, setSchema] = useState(defaultSchema);
  const [columns, setColumns] = useState<ColumnDef[]>([
    { name: "id", type: "bigint", nullable: false, default: "", extra: identityExtra[service], primaryKey: true },
  ]);
  const listId = `new-table-types-${service}`;

  const update = (index: number, column: ColumnDef) => setColumns(columns.map((c, i) => (i === index ? column : c)));
  const valid = name.trim() !== "" && columns.length > 0 && columns.every((c) => c.name.trim() !== "" && c.type.trim() !== "");

  const submit = () =>
    onDdl(
      `Create table ${name.trim()}`,
      { op: "create_table", schema, table: name.trim(), columns: columns.map(cleaned) },
      `create table ${name.trim()}`,
    );

  return (
    <Card className="">
      <CardHeader>
        <CardTitle>New table</CardTitle>
        <CardDescription>Types, defaults and extras are SQL; pick a common type or type your own.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <TypeOptions service={service} listId={listId} />
        <div className="flex flex-wrap gap-3">
          {schemas.length > 0 && (
            <Field className="w-40">
              <FieldLabel>Schema</FieldLabel>
              <Select value={schema} onValueChange={(value) => value && setSchema(value)}>
                <SelectTrigger className="w-full font-mono">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {schemas.map((s) => (
                    <SelectItem key={s} value={s} className="font-mono">
                      {s}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
          )}
          <Field className="min-w-56 flex-1">
            <FieldLabel htmlFor="new-table-name">Table name</FieldLabel>
            <Input id="new-table-name" className="font-mono" value={name} placeholder="users" onChange={(e) => setName(e.target.value)} />
          </Field>
        </div>
        <ScrollArea orientation="horizontal" className="rounded-lg border">
          <table className="w-full text-sm">
            <TableHeader className="bg-muted/50">
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Type</TableHead>
                <TableHead className="text-center">Null</TableHead>
                <TableHead>Default</TableHead>
                <TableHead className="text-center">PK</TableHead>
                <TableHead>Extra</TableHead>
                <TableHead className="w-10" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {columns.map((column, i) => (
                <TableRow key={i} className="hover:bg-transparent">
                  <TableCell className="p-1.5">
                    <Input
                      className="h-7 min-w-28 font-mono text-xs md:text-xs"
                      value={column.name}
                      aria-label="Column name"
                      onChange={(e) => update(i, { ...column, name: e.target.value })}
                    />
                  </TableCell>
                  <TableCell className="p-1.5">
                    <Input
                      className="h-7 min-w-32 font-mono text-xs md:text-xs"
                      list={listId}
                      value={column.type}
                      aria-label="Column type"
                      onChange={(e) => update(i, { ...column, type: e.target.value })}
                    />
                  </TableCell>
                  <TableCell className="p-1.5 text-center">
                    <Checkbox
                      className="mx-auto"
                      checked={column.nullable}
                      aria-label="Nullable"
                      onCheckedChange={(checked) => update(i, { ...column, nullable: checked })}
                    />
                  </TableCell>
                  <TableCell className="p-1.5">
                    <Input
                      className="h-7 min-w-24 font-mono text-xs md:text-xs"
                      value={column.default ?? ""}
                      aria-label="Default"
                      onChange={(e) => update(i, { ...column, default: e.target.value })}
                    />
                  </TableCell>
                  <TableCell className="p-1.5 text-center">
                    <Checkbox
                      className="mx-auto"
                      checked={!!column.primaryKey}
                      aria-label="Primary key"
                      onCheckedChange={(checked) =>
                        update(i, { ...column, primaryKey: checked, nullable: checked ? false : column.nullable })
                      }
                    />
                  </TableCell>
                  <TableCell className="p-1.5">
                    <Input
                      className="h-7 min-w-40 font-mono text-xs md:text-xs"
                      value={column.extra ?? ""}
                      placeholder={identityExtra[service]}
                      aria-label="Extra"
                      onChange={(e) => update(i, { ...column, extra: e.target.value })}
                    />
                  </TableCell>
                  <TableCell className="p-1.5">
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      disabled={columns.length === 1}
                      aria-label="Remove column"
                      onClick={() => setColumns(columns.filter((_, j) => j !== i))}
                    >
                      <XIcon />
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </table>
        </ScrollArea>
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <Button variant="outline" size="sm" onClick={() => setColumns([...columns, blankColumn()])}>
            <PlusIcon />
            Column
          </Button>
          <KeyRoundIcon className="ml-2 size-3 text-amber-500" />
          Primary key columns are made NOT NULL.
        </div>
      </CardContent>
      <CardFooter className="justify-end gap-2 border-t">
        <Button variant="outline" onClick={onCancel}>
          Cancel
        </Button>
        <Button disabled={!valid} onClick={submit}>
          Review SQL
        </Button>
      </CardFooter>
    </Card>
  );
}
