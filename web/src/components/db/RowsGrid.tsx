import { type ReactNode, useEffect, useState } from "react";
import {
  ArrowDownIcon,
  ArrowUpDownIcon,
  ArrowUpIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  KeyRoundIcon,
  LockIcon,
  PencilIcon,
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
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useAction, useLoad } from "@/hooks";
import { cn } from "@/lib/utils";
import type { ColumnInfo, SqlService, TableDetail } from "@/types";
import { RowModal, type RowEdit } from "./RowModal";
import { BooleanSelect, inputAttrs, valueKind } from "./ValueInput";
import { WhereInput } from "./WhereInput";

const PAGE_SIZE = 100;

/** The table's title and the rows toolbar: fixed above the rows, which scroll on their own below it. */
export const PANE_HEADER = "flex shrink-0 flex-col gap-3 border-b bg-muted/40 px-4 py-3 md:px-6";

interface Editing {
  row: number;
  column: number;
  value: string;
}

/** A table's rows: paged, sortable, filterable rows; rows are editable when the table has a primary key. */
export function RowsGrid({
  app,
  service,
  table,
  refreshKey,
  onChanged,
  heading,
}: {
  app: string;
  service: SqlService;
  table: TableDetail;
  refreshKey: number;
  onChanged: () => void;
  /** Shown above the toolbar, pinned with it. */
  heading?: ReactNode;
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
  // The row dialog: inserting a new row, or editing one on this page.
  const [rowDialog, setRowDialog] = useState<"insert" | RowEdit>();
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
    <div className="flex min-h-0 flex-1 flex-col">
      <div className={PANE_HEADER}>
        {heading}
        <div className="flex flex-wrap items-center gap-2">
          <WhereInput
            service={service}
            columns={table.columns}
            values={(name) => {
              const index = columns.indexOf(name);
              return index < 0 ? [] : pageRows.map((row) => row[index] ?? null);
            }}
            value={filter}
            onChange={setFilter}
            onSubmit={applyFilter}
            trailing={
              where ? (
                <InputGroupButton size="icon-xs" onClick={clearFilter} aria-label="Clear filter" title="Clear filter">
                  <XIcon />
                </InputGroupButton>
              ) : (
                <Kbd>↵</Kbd>
              )
            }
          />
          <Button variant="outline" size="sm" onClick={refresh}>
            <RefreshCwIcon />
            Refresh
          </Button>
          {editable && (
            <>
              <Button variant="outline" size="sm" onClick={() => setRowDialog("insert")}>
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
          {data && !editable && (
            <Tooltip>
              <TooltipTrigger render={<span className="inline-flex items-center gap-1 text-xs text-muted-foreground" />}>
                <LockIcon className="size-3" />
                Read-only
              </TooltipTrigger>
              <TooltipContent>
                {table.kind !== "table" ? `This ${table.kind} is read-only.` : "The table has no primary key. Use SQL to change rows."}
              </TooltipContent>
            </Tooltip>
          )}
        </div>
      </div>
      {(rows.error || error) && (
        <div className="flex shrink-0 flex-col gap-3 px-4 pt-3 md:px-6">
          <ErrorAlert message={rows.error} />
          <ErrorAlert message={error} onDismiss={clearError} />
        </div>
      )}
      {!data && !rows.error && (
        <div className="p-4 md:p-6">
          <Skeleton className="h-48 w-full" />
        </div>
      )}
      {data && (
        <GridContainer fill>
          <TableHeader>
            <TableRow>
              {editable && (
                <TableHead className="w-14 pl-0! md:pl-0!">
                  {/* No left padding: the edit button's own padding sets the row's edge (! beats the grid's edge padding). */}
                  {/* Lined up with the row checkboxes, which follow each row's edit button. */}
                  <Checkbox className="ml-7.5" checked={allSelected} onCheckedChange={toggleAll} aria-label="Select all rows on this page" />
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
              <TableRow key={r} className="group/row" data-state={selected.has(r) ? "selected" : undefined}>
                {editable && (
                  <TableCell className="w-14 pl-0! md:pl-0!">
                    <div className="flex items-center gap-1.5">
                      {/* Shown on the hovered (or focused) row: the whole row in the insert dialog, filled in for an update. */}
                      <Button
                        variant="ghost"
                        size="icon-xs"
                        className="text-muted-foreground opacity-0 group-hover/row:opacity-100 hover:text-foreground focus-visible:opacity-100"
                        aria-label={`Edit row ${first + r + 1}`}
                        title="Edit row"
                        onClick={() =>
                          setRowDialog({
                            key: keyOf(r),
                            values: Object.fromEntries(columns.map((name, c) => [name, row[c] ?? null])),
                            clipped: new Set(columns.filter((_, c) => clipped.has(`${r}:${c}`))),
                          })
                        }
                      >
                        <PencilIcon />
                      </Button>
                      <Checkbox checked={selected.has(r)} onCheckedChange={() => toggle(r)} aria-label={`Select row ${first + r + 1}`} />
                    </div>
                  </TableCell>
                )}
                {row.map((cell, c) =>
                  editing?.row === r && editing.column === c ? (
                    <TableCell key={c} className="px-1 py-0.5">
                      <CellEditor
                        service={service}
                        column={table.columns.find((col) => col.name === columns[editing.column])}
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
      <div className="flex shrink-0 items-center justify-between gap-2 border-t px-4 py-2 md:px-6">
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
      {rowDialog && (
        <RowModal
          app={app}
          service={service}
          table={table}
          edit={rowDialog === "insert" ? undefined : rowDialog}
          onClose={() => setRowDialog(undefined)}
          onSaved={() => {
            setRowDialog(undefined);
            changed();
          }}
        />
      )}
    </div>
  );
}

/** A cell being edited in place, typed by its column: a select that saves on choosing for booleans, else an input. */
function CellEditor({
  service,
  column,
  value,
  busy,
  onChange,
  onSave,
  onCancel,
}: {
  service: SqlService;
  column: ColumnInfo | undefined;
  value: string;
  busy: boolean;
  onChange: (value: string) => void;
  onSave: (value: string | null) => void;
  onCancel: () => void;
}) {
  const kind = column ? valueKind(column.type) : "text";
  const actions = (
    <>
      <InputGroupButton size="xs" className="font-mono text-xs" disabled={busy} onClick={() => onSave(null)} title="Set to NULL">
        NULL
      </InputGroupButton>
      <InputGroupButton size="icon-xs" disabled={busy} onClick={onCancel} aria-label="Cancel edit">
        <XIcon />
      </InputGroupButton>
    </>
  );
  if (kind === "boolean") {
    return (
      <div className="flex min-w-48 items-center gap-1">
        <BooleanSelect service={service} value={value} onChange={onSave} disabled={busy} defaultOpen className="h-7 flex-1" />
        {actions}
      </div>
    );
  }
  return (
    <InputGroup className="h-7 min-w-48">
      <InputGroupInput
        autoFocus
        className="h-full font-mono text-xs md:text-xs"
        value={value}
        disabled={busy}
        {...inputAttrs(kind, value)}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter") onSave(value);
          if (e.key === "Escape") onCancel();
        }}
      />
      <InputGroupAddon align="inline-end">{actions}</InputGroupAddon>
    </InputGroup>
  );
}
