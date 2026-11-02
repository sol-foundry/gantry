import { useState } from "react";
import { PlusIcon, Table2Icon, Trash2Icon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { DatabaseInfo } from "@/types";
import { formatBytes, plural, qualifiedName, shortVersion, type RequestDdl, type TableRef } from "./format";
import { TableActionMenu } from "./TableActions";

/** Database-level view: every table with its size and table-level actions, plus schemas on Postgres. */
export function DatabaseOverview({
  info,
  onSelect,
  onDdl,
  onNewTable,
}: {
  info: DatabaseInfo;
  onSelect: (table: TableRef) => void;
  onDdl: RequestDdl;
  onNewTable: () => void;
}) {
  // Stats stay put; the tables card takes the leftover height and scrolls, schemas get a capped strip below.
  return (
    <div className="flex flex-col gap-6">
      <div className="grid shrink-0 grid-cols-2 gap-3 sm:grid-cols-4">
        <Stat label="Engine" value={shortVersion(info.version)} />
        <Stat label="Size" value={formatBytes(info.sizeBytes)} />
        <Stat label="Tables" value={String(info.tables.length)} />
        {info.hasSchemas && <Stat label="Schemas" value={String(info.schemas.length)} />}
      </div>
      <Card className="">
        <CardHeader>
          <CardTitle>Tables</CardTitle>
          <CardDescription>Row counts are estimates from the database statistics.</CardDescription>
          <CardAction>
            <Button size="sm" onClick={onNewTable}>
              <PlusIcon />
              New table
            </Button>
          </CardAction>
        </CardHeader>
        <CardContent>
          {info.tables.length === 0 ? (
            <Empty className="border border-dashed">
              <EmptyHeader>
                <EmptyMedia variant="icon">
                  <Table2Icon />
                </EmptyMedia>
                <EmptyTitle>No tables yet</EmptyTitle>
                <EmptyDescription>Create one here, or add Flyway migrations and run them from the Migrations tab.</EmptyDescription>
              </EmptyHeader>
            </Empty>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Table</TableHead>
                  <TableHead>Kind</TableHead>
                  <TableHead className="text-right">~Rows</TableHead>
                  <TableHead className="text-right">Size</TableHead>
                  <TableHead className="w-10" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {info.tables.map((t) => {
                  const label = qualifiedName(info.hasSchemas, t.schema, t.name);
                  return (
                    <TableRow key={`${t.schema}.${t.name}`}>
                      <TableCell>
                        <button className="font-mono text-xs hover:underline" onClick={() => onSelect({ schema: t.schema, name: t.name })}>
                          {label}
                        </button>
                      </TableCell>
                      <TableCell className="text-muted-foreground">{t.kind}</TableCell>
                      <TableCell className="text-right tabular-nums">{t.estimatedRows}</TableCell>
                      <TableCell className="text-right tabular-nums">{formatBytes(t.sizeBytes)}</TableCell>
                      <TableCell>
                        <TableActionMenu label={label} kind={t.kind} base={{ schema: t.schema, table: t.name }} onDdl={onDdl} />
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
      {info.hasSchemas && <SchemasCard info={info} onDdl={onDdl} />}
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <Card size="sm">
      <CardContent className="flex flex-col gap-0.5">
        <span className="text-xs text-muted-foreground">{label}</span>
        <span className="truncate font-heading text-lg font-semibold" title={value}>
          {value}
        </span>
      </CardContent>
    </Card>
  );
}

function SchemasCard({ info, onDdl }: { info: DatabaseInfo; onDdl: RequestDdl }) {
  const [name, setName] = useState("");
  const [cascade, setCascade] = useState(false);
  const counts = (schema: string) => info.tables.filter((t) => t.schema === schema).length;
  const create = () => {
    const schema = name.trim();
    if (!schema) return;
    onDdl(`Create schema ${schema}`, { op: "create_schema", schema }, `create schema ${schema}`);
    setName("");
  };

  return (
    <Card className="shrink-0">
      <CardHeader>
        <CardTitle>Schemas</CardTitle>
        <CardDescription>Namespaces inside the database.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Schema</TableHead>
              <TableHead className="text-right">Tables</TableHead>
              <TableHead className="w-10" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {info.schemas.map((schema) => (
              <TableRow key={schema}>
                <TableCell className="font-mono text-xs">{schema}</TableCell>
                <TableCell className="text-right tabular-nums">{plural(counts(schema), "table")}</TableCell>
                <TableCell>
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    className="text-destructive hover:text-destructive"
                    aria-label={`Drop schema ${schema}`}
                    title="Drop schema"
                    onClick={() =>
                      onDdl(
                        `Drop schema ${schema}`,
                        { op: "drop_schema", schema, cascade },
                        `drop schema ${schema}${cascade ? " cascade" : ""}`,
                      )
                    }
                  >
                    <Trash2Icon />
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        <Label className="text-xs font-normal text-muted-foreground">
          <Checkbox checked={cascade} onCheckedChange={setCascade} />
          Drop with CASCADE (also drops the schema&apos;s tables)
        </Label>
        <div className="flex gap-2">
          <Input
            className="font-mono"
            value={name}
            placeholder="new_schema"
            aria-label="New schema name"
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && create()}
          />
          <Button variant="outline" disabled={name.trim() === ""} onClick={create}>
            <PlusIcon />
            New schema
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
