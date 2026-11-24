import type { ReactNode } from "react";
import { ScrollArea } from "@/components/ui/scroll-area";
import { TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { cn } from "@/lib/utils";
import type { TableResult } from "@/types";

/**
 * A bordered result grid at its full height; wide grids scroll sideways with the app's overlay scrollbar.
 * With [fill] it is borderless and takes the height it is given instead, scrolling both ways under a pinned header row.
 */
export function GridContainer({ children, className, fill = false }: { children: ReactNode; className?: string; fill?: boolean }) {
  if (fill) {
    return (
      <ScrollArea orientation="both" className={cn("min-h-0 flex-1", className)}>
        <table
          className={cn(
            "w-full caption-bottom text-sm",
            // The header row stays put; a shadow stands in for its border, which wouldn't move with a sticky cell.
            "[&_th]:sticky [&_th]:top-0 [&_th]:z-10 [&_th]:bg-muted [&_th]:shadow-[inset_0_-1px_0_var(--border)]",
            // The outer columns line up with the pane's padding above and below the grid.
            "[&_td:first-child]:pl-4 [&_td:last-child]:pr-4 [&_th:first-child]:pl-4 [&_th:last-child]:pr-4",
            "md:[&_td:first-child]:pl-6 md:[&_td:last-child]:pr-6 md:[&_th:first-child]:pl-6 md:[&_th:last-child]:pr-6",
          )}
        >
          {children}
        </table>
      </ScrollArea>
    );
  }
  return (
    <ScrollArea orientation="horizontal" className={cn("rounded-lg border", className)}>
      <table className="w-full caption-bottom text-sm [&_th]:bg-muted">{children}</table>
    </ScrollArea>
  );
}

/** Lookup for TableResult.clippedCells. */
export function clippedSet(result: TableResult | undefined): Set<string> {
  return new Set((result?.clippedCells ?? []).map(([row, column]) => `${row}:${column}`));
}

/**
 * One result cell: monospace, truncated with the full value on hover, NULL set apart from the text "NULL".
 * A [clipped] value was cut by the server, so it is marked and its tooltip says so.
 */
export function ValueCell({
  value,
  clipped = false,
  className,
  ...props
}: { value: string | null; clipped?: boolean } & React.ComponentProps<"td">) {
  const title =
    value === null ? "NULL" : clipped ? `${value.slice(0, 2000)}…\n\n(cut at 64 KB; read or edit the full value with SQL)` : value;
  return (
    <TableCell
      title={title}
      className={cn("max-w-80 truncate font-mono text-xs", value === null && "text-muted-foreground italic", className)}
      {...props}
    >
      {clipped && <span className="mr-1 rounded bg-amber-500/15 px-1 text-[10px] text-amber-700 dark:text-amber-400">64 KB+</span>}
      {value === null ? "NULL" : value}
    </TableCell>
  );
}

/** Read-only result of an arbitrary query (SQL console). */
export function DataGrid({ result }: { result: TableResult }) {
  const clipped = clippedSet(result);
  if (result.columns.length === 0) {
    return <p className="text-sm text-muted-foreground">{result.note || "OK (no result set)"}</p>;
  }
  return (
    <div className="flex flex-col gap-2">
      <GridContainer>
        <TableHeader>
          <TableRow>
            {result.columns.map((column, i) => (
              <TableHead key={i} className="font-mono text-xs">
                {column}
              </TableHead>
            ))}
          </TableRow>
        </TableHeader>
        <TableBody>
          {result.rows.map((row, r) => (
            <TableRow key={r}>
              {row.map((cell, c) => (
                <ValueCell key={c} value={cell} clipped={clipped.has(`${r}:${c}`)} />
              ))}
            </TableRow>
          ))}
        </TableBody>
      </GridContainer>
      <p className="text-xs text-muted-foreground">
        {result.rows.length} row{result.rows.length === 1 ? "" : "s"}
        {result.note && ` · ${result.note}`}
      </p>
    </div>
  );
}
