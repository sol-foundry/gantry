import { type ReactNode, useState } from "react";
import { ChevronRightIcon, LayoutGridIcon, PlusIcon, RefreshCwIcon, SearchIcon, Table2Icon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { Skeleton } from "@/components/ui/skeleton";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { cn } from "@/lib/utils";
import type { DatabaseInfo, TableSummary } from "@/types";
import type { TableRef } from "./format";

export type { TableRef } from "./format";

type Kind = "" | "table" | "view";

const KINDS: { value: Kind; label: string }[] = [
  { value: "", label: "All" },
  { value: "table", label: "Tables" },
  { value: "view", label: "Views" },
];

/**
 * The table list, grouped by schema where the database has schemas. Groups fold; mirror schemas (views repeating
 * other schemas' tables) go together in a section at the bottom that starts folded.
 */
export function TablesAside({
  info,
  selected,
  overview,
  onSelect,
  onOverview,
  onNewTable,
  onRefresh,
}: {
  info: DatabaseInfo | undefined;
  selected: TableRef | undefined;
  /** The database overview is showing (nothing selected, no new-table form). */
  overview: boolean;
  onSelect: (table: TableRef) => void;
  onOverview: () => void;
  onNewTable: () => void;
  onRefresh: () => void;
}) {
  const [filter, setFilter] = useState("");
  const [kind, setKind] = useState<Kind>("");
  // Groups the viewer folded; the mirror section starts folded.
  const [folds, setFolds] = useState<Record<string, boolean>>({});
  const [mirrorsOpen, setMirrorsOpen] = useState(false);
  // Views, materialized views and foreign tables all count as views here.
  const tables = (info?.tables ?? []).filter(
    (t) => t.name.toLowerCase().includes(filter.toLowerCase()) && (!kind || (kind === "table") === (t.kind === "table")),
  );
  const groups = info?.hasSchemas ? [...new Set(tables.map((t) => t.schema))] : [""];
  const mirrors = new Set(info?.mirrorSchemas ?? []);
  const mirrorGroups = groups.filter((schema) => mirrors.has(schema));
  // A search or the selected table shows through a fold.
  const folded = (schema: string) => !filter && selected?.schema !== schema && (folds[schema] ?? false);
  const mirrorsShown = mirrorsOpen || !!filter || (!!selected && mirrors.has(selected.schema));

  const group = (schema: string) => {
    const items = tables.filter((t) => !info?.hasSchemas || t.schema === schema);
    const closed = info?.hasSchemas && folded(schema);
    return (
      <div key={schema} className="flex flex-col gap-0.5">
        {info?.hasSchemas && (
          <FoldHeader open={!closed} count={items.length} onToggle={() => setFolds((f) => ({ ...f, [schema]: !closed }))}>
            {schema}
          </FoldHeader>
        )}
        {!closed &&
          items.map((t) => (
            <TableItem
              key={`${t.schema}.${t.name}`}
              table={t}
              active={selected?.schema === t.schema && selected.name === t.name}
              onSelect={onSelect}
            />
          ))}
      </div>
    );
  };

  return (
    <Card size="sm">
      <CardContent className="flex flex-col gap-3">
        <div className="flex items-center justify-between">
          <span className="text-sm font-medium">Tables</span>
          <Button variant="ghost" size="icon-xs" onClick={onRefresh} aria-label="Refresh tables" title="Refresh">
            <RefreshCwIcon />
          </Button>
        </div>
        <InputGroup>
          <InputGroupAddon>
            <SearchIcon />
          </InputGroupAddon>
          <InputGroupInput value={filter} placeholder="Filter tables" onChange={(e) => setFilter(e.target.value)} />
        </InputGroup>
        <ToggleGroup
          variant="outline"
          size="sm"
          spacing={0}
          className="w-full"
          value={[kind]}
          onValueChange={(next) => setKind((next[0] ?? "") as Kind)}
          aria-label="Kind"
        >
          {KINDS.map((k) => (
            <ToggleGroupItem key={k.value || "all"} value={k.value} className="flex-1">
              {k.label}
            </ToggleGroupItem>
          ))}
        </ToggleGroup>
        <Button variant="outline" size="sm" onClick={onNewTable}>
          <PlusIcon />
          New table
        </Button>
        <button
          className={cn(
            "flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm transition-colors hover:bg-muted",
            overview && "bg-muted font-medium",
          )}
          onClick={onOverview}
        >
          <LayoutGridIcon className="size-3.5 text-muted-foreground" />
          Overview
          {info && <span className="ml-auto text-[11px] text-muted-foreground tabular-nums">{info.tables.length}</span>}
        </button>
        <div className="-mx-1 flex flex-col gap-3 px-1">
          {!info && [0, 1, 2, 3].map((i) => <Skeleton key={i} className="h-7 w-full" />)}
          {info && tables.length === 0 && (
            <p className="px-2 text-xs text-muted-foreground">{filter || kind ? "No match." : "No tables yet."}</p>
          )}
          {groups.filter((schema) => !mirrors.has(schema)).map(group)}
          {mirrorGroups.length > 0 && (
            <div className="flex flex-col gap-3 border-t pt-3">
              <FoldHeader
                open={mirrorsShown}
                count={mirrorGroups.length}
                title="Schemas of nothing but views over same-named tables in other schemas, such as a read-only access layer"
                onToggle={() => setMirrorsOpen(!mirrorsShown)}
              >
                Mirror schemas
              </FoldHeader>
              {mirrorsShown && <div className="flex flex-col gap-3 pl-2">{mirrorGroups.map(group)}</div>}
            </div>
          )}
        </div>
      </CardContent>
    </Card>
  );
}

function FoldHeader({
  open,
  count,
  title,
  onToggle,
  children,
}: {
  open: boolean;
  count: number;
  title?: string;
  onToggle: () => void;
  children: ReactNode;
}) {
  return (
    <button
      className="flex items-center gap-1 rounded-md px-1 pb-1 text-left text-[11px] font-medium tracking-wide text-muted-foreground uppercase hover:text-foreground"
      aria-expanded={open}
      title={title}
      onClick={onToggle}
    >
      <ChevronRightIcon className={cn("size-3 shrink-0 transition-transform", open && "rotate-90")} />
      <span className="truncate">{children}</span>
      <span className="ml-auto tracking-normal tabular-nums normal-case">{count}</span>
    </button>
  );
}

function TableItem({ table, active, onSelect }: { table: TableSummary; active: boolean; onSelect: (table: TableRef) => void }) {
  return (
    <button
      className={cn(
        "flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm transition-colors hover:bg-muted",
        active && "bg-muted font-medium",
      )}
      onClick={() => onSelect({ schema: table.schema, name: table.name })}
    >
      <Table2Icon className="size-3.5 shrink-0 text-muted-foreground" />
      <span className="min-w-0 flex-1 truncate font-mono text-xs">{table.name}</span>
      {table.kind !== "table" && (
        <Badge variant="outline" className="h-4 px-1 text-[10px]">
          {table.kind}
        </Badge>
      )}
      <span className="shrink-0 text-[11px] text-muted-foreground tabular-nums">~{table.estimatedRows}</span>
    </button>
  );
}
