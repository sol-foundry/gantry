import { useEffect, useState } from "react";
import {
  ArrowDownIcon,
  ArrowUpDownIcon,
  ArrowUpIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  FilterIcon,
  KeyRoundIcon,
  LockIcon,
  PlusIcon,
  RefreshCwIcon,
  Trash2Icon,
  XIcon,
} from "lucide-react";
import { toast } from "sonner";
import { api } from "@/api";
import { ConfirmButton, ErrorAlert } from "@/components/common";
import { GridContainer, ValueCell, clippedSet } from "@/components/DataGrid";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from "@/components/ui/input-group";
import { Kbd } from "@/components/ui/kbd";
import { Skeleton } from "@/components/ui/skeleton";
import { TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useAction, useLoad } from "@/hooks";
import { cn } from "@/lib/utils";
import type { SqlService, TableDetail } from "@/types";
import { InsertRowModal } from "./InsertRowModal";

const PAGE_SIZE = 100;

interface Editing {
  row: number;
  column: number;
  value: string;
}

/** The Data tab: paged, sortable, filterable rows; rows are editable when the table has a primary key. */
export function RowsGrid({
  app,
  service,
  table,
  refreshKey,
  onChanged,
}: {
  app: string;
  service: SqlService;
  table: TableDetail;
  refreshKey: number;
  onChanged: () => void;
}) {
  const db = api.db(app, service);
  const [page, setPage] = useState(0);
  const [order, setOrder] = useState<{ column: string; desc: boolean }>();
  const [filter, setFilter] = useState("");
  const [where, setWhere] = useState("");
  const [reload, setReload] = useState(0);
  const rows = useLoad(
    () => db.rows(table.schema, table.name, { offset: page * PAGE_SIZE, limit: PAGE_SIZE, order: order?.column, desc: order?.desc, where }),
    [app, service, table.schema, table.name, page, order?.column, order?.desc, where, refreshKey, reload],
  );
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [editing, setEditing] = useState<Editing>();
  const [inserting, setInserting] = useState(false);
  const { busy, error, run, clearError } = useAction();

  // Selection and edits are row indexes into the current page, so they don't survive a different page.
  useEffect(() => {
    setSelected(new Set());
    setEditing(undefined);
  }, [page, order?.column, order?.desc, where, refreshKey, table.name]);

  const data = rows.data;
  const primaryKey = data?.primaryKey ?? [];
  const editable = table.kind === "table" && primaryKey.length > 0;
  const columns = data?.result.columns ?? [];
  const pageRows = data?.result.rows ?? [];
  const clipped = clippedSet(data?.result);

  const refresh = () => {
    setSelected(new Set());
    setEditing(undefined);
    setReload((r) => r + 1);
  };
  const changed = () => {
    refresh();
    onChanged();
  };

  const keyOf = (rowIndex: number): Record<string, string | null> => {
    const row = pageRows[rowIndex];
    return Object.fromEntries(primaryKey.map((name) => [name, row[columns.indexOf(name)]]));
  };

  const sortBy = (column: string) => {
    setPage(0);
    setOrder(order?.column === column ? { column, desc: !order.desc } : { column, desc: false });
  };

  const applyFilter = () => {
    setPage(0);
    setWhere(filter.trim());
  };
  const clearFilter = () => {
    setFilter("");
    setWhere("");
    setPage(0);
  };

  const saveEdit = async (value: string | null) => {
    if (!editing) return;
    const column = columns[editing.column];
    if (await run("edit", () => db.update(table.schema, table.name, keyOf(editing.row), { [column]: value }))) {
      toast.success(`Updated ${column}`);
      changed();
    }
  };

  const deleteSelected = async () => {
    const keys = [...selected].map(keyOf);
    if (await run("delete", () => db.remove(table.schema, table.name, keys))) {
      toast.success(`Deleted ${keys.length} row${keys.length === 1 ? "" : "s"}`);
      changed();
    }
  };

  const toggle = (rowIndex: number) => {
    const next = new Set(selected);
    if (next.has(rowIndex)) next.delete(rowIndex);
    else next.add(rowIndex);
    setSelected(next);
  };
  const allSelected = pageRows.length > 0 && selected.size === pageRows.length;
  const toggleAll = () => setSelected(allSelected ? new Set() : new Set(pageRows.map((_, i) => i)));

  const first = page * PAGE_SIZE;
  const count = pageRows.length;

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <InputGroup className="min-w-64 flex-1">
          <InputGroupAddon>
            <FilterIcon />
            <span className="font-mono text-xs">WHERE</span>
          </InputGroupAddon>
          <InputGroupInput
            className="font-mono text-xs md:text-xs"
            value={filter}
            placeholder="price > 10 AND name LIKE 'A%'"
            spellCheck={false}
            onChange={(e) => setFilter(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && applyFilter()}
          />
          <InputGroupAddon align="inline-end">
            {where ? (
              <InputGroupButton size="icon-xs" onClick={clearFilter} aria-label="Clear filter" title="Clear filter">
                <XIcon />
              </InputGroupButton>
            ) : (
              <Kbd>↵</Kbd>
            )}
          </InputGroupAddon>
        </InputGroup>
        <Button variant="outline" size="sm" onClick={refresh}>
          <RefreshCwIcon />
          Refresh
        </Button>
        {editable && (
          <>
            <Button variant="outline" size="sm" onClick={() => setInserting(true)}>
              <PlusIcon />
              Insert row
            </Button>
            <ConfirmButton
              destructive
              size="sm"
              disabled={selected.size === 0 || !!busy}
              title={`Delete ${selected.size} row${selected.size === 1 ? "" : "s"}?`}
              description={`This deletes the selected rows from ${table.name} by primary key. It can't be undone.`}
              confirmLabel="Delete"
              onConfirm={deleteSelected}
            >
              <Trash2Icon />
              Delete{selected.size > 0 ? ` (${selected.size})` : ""}
            </ConfirmButton>
          </>
        )}
      </div>
      <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
        {editable ? (
          <>
            Double-click a cell to edit. <Kbd>↵</Kbd> saves, <Kbd>Esc</Kbd> cancels.
          </>
        ) : (
          <>
            <LockIcon className="size-3" />
            {table.kind !== "table" ? `This ${table.kind} is read-only.` : "Read-only: the table has no primary key. Use SQL to change rows."}
          </>
        )}
      </p>
      <ErrorAlert message={rows.error} />
      <ErrorAlert message={error} onDismiss={clearError} />
      {!data && !rows.error && <Skeleton className="h-48 w-full" />}
      {data && (
        <GridContainer>
          <TableHeader>
            <TableRow>
              {editable && (
                <TableHead className="w-8">
                  <Checkbox checked={allSelected} onCheckedChange={toggleAll} aria-label="Select all rows on this page" />
                </TableHead>
              )}
              {columns.map((c) => (
                <TableHead key={c}>
                  <button className="inline-flex items-center gap-1 font-mono text-xs hover:text-foreground" onClick={() => sortBy(c)} title="Sort">
                    {primaryKey.includes(c) && <KeyRoundIcon className="size-3 text-amber-500" />}
                    {c}
                    {order?.column === c ? (
                      order.desc ? (
                        <ArrowDownIcon className="size-3" />
                      ) : (
                        <ArrowUpIcon className="size-3" />
                      )
                    ) : (
                      <ArrowUpDownIcon className="size-3 opacity-30" />
                    )}
                  </button>
                </TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {pageRows.length === 0 && (
              <TableRow>
                <TableCell colSpan={columns.length + (editable ? 1 : 0)} className="py-8 text-center text-muted-foreground">
                  {where ? "No rows match the filter." : "No rows."}
                </TableCell>
              </TableRow>
            )}
            {pageRows.map((row, r) => (
              <TableRow key={r} data-state={selected.has(r) ? "selected" : undefined}>
                {editable && (
                  <TableCell className="w-8">
                    <Checkbox checked={selected.has(r)} onCheckedChange={() => toggle(r)} aria-label={`Select row ${first + r + 1}`} />
                  </TableCell>
                )}
                {row.map((cell, c) =>
                  editing?.row === r && editing.column === c ? (
                    <TableCell key={c} className="px-1 py-0.5">
                      <CellEditor
                        value={editing.value}
                        busy={!!busy}
                        onChange={(value) => setEditing({ ...editing, value })}
                        onSave={saveEdit}
                        onCancel={() => setEditing(undefined)}
                      />
                    </TableCell>
                  ) : (
                    <ValueCell
                      key={c}
                      value={cell}
                      clipped={clipped.has(`${r}:${c}`)}
                      className={cn(editable && !clipped.has(`${r}:${c}`) && "cursor-text")}
                      // Saving a clipped cell would overwrite the full value with the shortened text.
                      onDoubleClick={editable && !clipped.has(`${r}:${c}`) ? () => setEditing({ row: r, column: c, value: cell ?? "" }) : undefined}
                    />
                  ),
                )}
              </TableRow>
            ))}
          </TableBody>
        </GridContainer>
      )}
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs text-muted-foreground tabular-nums">
          {count === 0 ? "No rows" : `Rows ${first + 1}–${first + count}`}
          {data?.total != null && ` of ${data.total}`}
        </span>
        <div className="flex items-center gap-1">
          <Button variant="outline" size="icon-sm" disabled={page === 0} onClick={() => setPage(page - 1)} aria-label="Previous page">
            <ChevronLeftIcon />
          </Button>
          <span className="px-2 text-xs text-muted-foreground tabular-nums">Page {page + 1}</span>
          <Button variant="outline" size="icon-sm" disabled={count < PAGE_SIZE} onClick={() => setPage(page + 1)} aria-label="Next page">
            <ChevronRightIcon />
          </Button>
        </div>
      </div>
      {inserting && (
        <InsertRowModal
          app={app}
          service={service}
          table={table}
          onClose={() => setInserting(false)}
          onInserted={() => {
            setInserting(false);
            changed();
          }}
        />
      )}
    </div>
  );
}

function CellEditor({
  value,
  busy,
  onChange,
  onSave,
  onCancel,
}: {
  value: string;
  busy: boolean;
  onChange: (value: string) => void;
  onSave: (value: string | null) => void;
  onCancel: () => void;
}) {
  return (
    <InputGroup className="h-7 min-w-48">
      <InputGroupInput
        autoFocus
        className="h-full font-mono text-xs md:text-xs"
        value={value}
        disabled={busy}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter") onSave(value);
          if (e.key === "Escape") onCancel();
        }}
      />
      <InputGroupAddon align="inline-end">
        <InputGroupButton size="xs" className="font-mono text-xs" disabled={busy} onClick={() => onSave(null)} title="Set to NULL">
          NULL
        </InputGroupButton>
        <InputGroupButton size="icon-xs" disabled={busy} onClick={onCancel} aria-label="Cancel edit">
          <XIcon />
        </InputGroupButton>
      </InputGroupAddon>
    </InputGroup>
  );
}
