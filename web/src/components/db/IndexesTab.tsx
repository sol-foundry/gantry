import { useState } from "react";
import { KeyRoundIcon, PlusIcon, Trash2Icon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Field, FieldGroup, FieldLabel, FieldLegend, FieldSet } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { TableDetail } from "@/types";
import type { RequestDdl } from "./format";

/** Indexes and constraints of a table; index create/drop go through the shared SQL review. */
export function IndexesTab({ table, onDdl }: { table: TableDetail; onDdl: RequestDdl }) {
  const editable = table.kind === "table";
  const base = { schema: table.schema, table: table.name };

  return (
    <div className="flex flex-col gap-6">
      <section className="flex flex-col gap-2">
        <h3 className="text-sm font-medium">Indexes</h3>
        {table.indexes.length === 0 ? (
          <p className="text-sm text-muted-foreground">No indexes.</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Columns</TableHead>
                <TableHead>Kind</TableHead>
                {editable && <TableHead className="w-10" />}
              </TableRow>
            </TableHeader>
            <TableBody>
              {table.indexes.map((index) => (
                <TableRow key={index.name}>
                  <TableCell className="font-mono text-xs">{index.name}</TableCell>
                  <TableCell className="font-mono text-xs">{index.columns}</TableCell>
                  <TableCell>
                    {index.primary ? (
                      <Badge variant="outline" className="gap-1">
                        <KeyRoundIcon className="size-3 text-amber-500" />
                        primary
                      </Badge>
                    ) : index.unique ? (
                      <Badge variant="secondary">unique</Badge>
                    ) : (
                      <span className="text-muted-foreground">index</span>
                    )}
                  </TableCell>
                  {editable && (
                    <TableCell>
                      {!index.primary && (
                        <Button
                          variant="ghost"
                          size="icon-sm"
                          className="text-destructive hover:text-destructive"
                          aria-label={`Drop index ${index.name}`}
                          title="Drop index"
                          onClick={() =>
                            onDdl(`Drop index ${index.name}`, { op: "drop_index", ...base, index: index.name }, `drop index ${index.name} on ${table.name}`)
                          }
                        >
                          <Trash2Icon />
                        </Button>
                      )}
                    </TableCell>
                  )}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </section>
      {editable && <CreateIndexCard table={table} onDdl={onDdl} />}
      <section className="flex flex-col gap-2">
        <h3 className="text-sm font-medium">Constraints</h3>
        {table.constraints.length === 0 ? (
          <p className="text-sm text-muted-foreground">No constraints.</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Kind</TableHead>
                <TableHead>Definition</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {table.constraints.map((c) => (
                <TableRow key={c.name + c.kind}>
                  <TableCell className="font-mono text-xs">{c.name}</TableCell>
                  <TableCell>
                    <Badge variant="outline">{c.kind}</Badge>
                  </TableCell>
                  <TableCell className="font-mono text-xs break-all whitespace-normal">{c.definition}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </section>
    </div>
  );
}

function CreateIndexCard({ table, onDdl }: { table: TableDetail; onDdl: RequestDdl }) {
  const [name, setName] = useState("");
  const [columns, setColumns] = useState<string[]>([]);
  const [unique, setUnique] = useState(false);

  // Keeps picked columns in table order, so the index column order is predictable.
  const toggle = (column: string) => {
    const next = columns.includes(column) ? columns.filter((c) => c !== column) : [...columns, column];
    setColumns(table.columns.map((c) => c.name).filter((c) => next.includes(c)));
  };

  const submit = () => {
    onDdl(
      "Create index",
      { op: "create_index", schema: table.schema, table: table.name, index: name.trim(), indexColumns: columns, unique },
      `add ${unique ? "unique " : ""}index on ${table.name} ${columns.join(" ")}`,
    );
    setName("");
    setColumns([]);
    setUnique(false);
  };

  return (
    <Card size="sm" className="bg-muted/30">
      <CardHeader>
        <CardTitle>Create index</CardTitle>
        <CardDescription>Columns are indexed in table order.</CardDescription>
      </CardHeader>
      <CardContent>
        <FieldGroup className="gap-4">
          <Field>
            <FieldLabel htmlFor="index-name">Name</FieldLabel>
            <Input
              id="index-name"
              className="font-mono"
              value={name}
              placeholder="optional; generated from the columns"
              onChange={(e) => setName(e.target.value)}
            />
          </Field>
          <FieldSet>
            <FieldLegend variant="label">Columns</FieldLegend>
            <div className="flex flex-wrap gap-x-4 gap-y-2">
              {table.columns.map((c) => (
                <Label key={c.name} className="font-mono text-xs font-normal">
                  <Checkbox checked={columns.includes(c.name)} onCheckedChange={() => toggle(c.name)} />
                  {c.name}
                </Label>
              ))}
            </div>
          </FieldSet>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <Label className="font-normal">
              <Checkbox checked={unique} onCheckedChange={setUnique} />
              Unique
            </Label>
            <Button size="sm" disabled={columns.length === 0} onClick={submit}>
              <PlusIcon />
              Review SQL
            </Button>
          </div>
        </FieldGroup>
      </CardContent>
    </Card>
  );
}
