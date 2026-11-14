import { useEffect, useState } from "react";
import { InboxIcon, PauseIcon, PlayIcon, RefreshCwIcon, Trash2Icon } from "lucide-react";
import { toast } from "sonner";
import { api } from "@/api";
import { CodeBlock, ConfirmButton, CopyButton, ErrorAlert, IconButton } from "@/components/common";
import { DataGrid } from "@/components/DataGrid";
import { clock } from "@/components/gateway/CallList";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { useLoad } from "@/hooks";
import { cn } from "@/lib/utils";
import type { DbQuery, SqlService, TableResult } from "@/types";

const POLL_MS = 2000;
const SEARCH_DEBOUNCE_MS = 300;
const LIMIT = 200;
const PRESETS = [
  { value: "", label: "All" },
  { value: "5m", label: "5m" },
  { value: "15m", label: "15m" },
  { value: "1h", label: "1h" },
  { value: "24h", label: "24h" },
  { value: "custom", label: "Custom" },
] as const;

const number = (n: number) => n.toLocaleString("en-US");

const duration = (ms: number) => (ms >= 1000 ? `${(ms / 1000).toFixed(2)} s` : `${ms.toFixed(ms < 10 ? 2 : 0)} ms`);

/** A datetime-local value as an ISO instant, or undefined while it is empty or invalid. */
function isoOf(local: string): string | undefined {
  const d = new Date(local);
  return local && !Number.isNaN(d.getTime()) ? d.toISOString() : undefined;
}

function outcome(q: DbQuery): { text: string; tone: string } {
  if (q.error) return { text: `error ${q.error.code}`, tone: "text-destructive" };
  if (q.rowCount !== null) return { text: `rows ${number(q.rowCount)}`, tone: "text-emerald-600 dark:text-emerald-400" };
  if (q.affected !== null) return { text: `affected ${number(q.affected)}`, tone: "text-sky-600 dark:text-sky-400" };
  return { text: q.command ?? "ok", tone: "text-muted-foreground" };
}

export function QueryList({ app, service }: { app: string; service: SqlService }) {
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  useEffect(() => {
    const id = setTimeout(() => setSearch(searchInput), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(id);
  }, [searchInput]);
  const [preset, setPreset] = useState("");
  const [fromLocal, setFromLocal] = useState("");
  const [toLocal, setToLocal] = useState("");
  const [paused, setPaused] = useState(false);
  const [selected, setSelected] = useState<DbQuery>();

  const custom = preset === "custom";
  const from = custom ? isoOf(fromLocal) : preset || undefined;
  const to = custom ? isoOf(toLocal) : undefined;
  const queries = useLoad(
    () => api.db(app, service).queries({ search, from, to, limit: LIMIT }),
    [app, service, search, from, to],
    paused ? undefined : POLL_MS,
  );
  const filtered = search !== "" || from !== undefined || to !== undefined;

  const clear = async () => {
    try {
      await api.db(app, service).clearQueries();
      setSelected(undefined);
      toast.success("Cleared the query log");
      queries.reload();
    } catch (e) {
      toast.error((e as Error).message);
    }
  };

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <Input
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
          placeholder="Search SQL or client"
          aria-label="Search SQL or client"
          className="h-7 w-full font-mono text-xs sm:w-64"
        />
        <ToggleGroup
          variant="outline"
          size="sm"
          spacing={0}
          value={[preset]}
          onValueChange={(next) => setPreset(next[0] ?? "")}
          aria-label="Time range"
          className="flex-wrap"
        >
          {PRESETS.map((p) => (
            <ToggleGroupItem key={p.value || "all"} value={p.value}>
              {p.label}
            </ToggleGroupItem>
          ))}
        </ToggleGroup>
        <div className="ml-auto flex items-center gap-1">
          {paused && <Badge variant="outline">Paused</Badge>}
          <IconButton label={paused ? "Resume auto-refresh" : "Pause auto-refresh"} onClick={() => setPaused((p) => !p)}>
            {paused ? <PlayIcon /> : <PauseIcon />}
          </IconButton>
          <IconButton label="Refresh now" onClick={queries.reload}>
            <RefreshCwIcon />
          </IconButton>
          <ConfirmButton
            destructive
            variant="outline"
            size="sm"
            title="Clear the query log?"
            description="Every recorded query for this database is deleted. New queries keep being recorded."
            confirmLabel="Clear"
            onConfirm={clear}
          >
            <Trash2Icon />
            Clear
          </ConfirmButton>
        </div>
      </div>
      {custom && (
        <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
          <label className="flex items-center gap-1.5">
            From
            <Input type="datetime-local" value={fromLocal} onChange={(e) => setFromLocal(e.target.value)} className="h-7 w-auto text-xs" />
          </label>
          <label className="flex items-center gap-1.5">
            To
            <Input type="datetime-local" value={toLocal} onChange={(e) => setToLocal(e.target.value)} className="h-7 w-auto text-xs" />
          </label>
        </div>
      )}
      <ErrorAlert message={queries.error} />
      {queries.data === undefined && !queries.error && <Skeleton className="h-48" />}
      {queries.data?.length === 0 && (
        <Card>
          <CardContent>
            <Empty className="py-8">
              <EmptyHeader>
                <EmptyMedia variant="icon">
                  <InboxIcon />
                </EmptyMedia>
                <EmptyTitle>{filtered ? "No matching queries" : "No queries yet"}</EmptyTitle>
                <EmptyDescription>
                  {filtered ? "Nothing in the log matches these filters." : "Queries your app runs against this database show up here."}
                </EmptyDescription>
              </EmptyHeader>
            </Empty>
          </CardContent>
        </Card>
      )}
      {queries.data && queries.data.length > 0 && (
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Time</TableHead>
                <TableHead className="text-right">Duration</TableHead>
                <TableHead>Client</TableHead>
                <TableHead>SQL</TableHead>
                <TableHead>Outcome</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {queries.data.map((q) => {
                const o = outcome(q);
                return (
                  <TableRow key={q.id} className="cursor-pointer" onClick={() => setSelected(q)}>
                    <TableCell className="font-mono text-xs text-muted-foreground">{clock(q.time)}</TableCell>
                    <TableCell className="text-right font-mono text-xs tabular-nums">{duration(q.durationMs)}</TableCell>
                    <TableCell className="max-w-40 truncate font-mono text-xs text-muted-foreground">{q.client ?? "-"}</TableCell>
                    <TableCell className="max-w-[28rem] min-w-48 truncate font-mono text-xs">{q.sql.replace(/\s+/g, " ")}</TableCell>
                    <TableCell className={cn("font-mono text-xs font-medium whitespace-nowrap", o.tone)}>
                      {o.text}
                      {q.truncated && (
                        <Badge variant="outline" className="ml-2" title="Rows or a value were cut at the configured size">
                          truncated
                        </Badge>
                      )}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </div>
      )}
      {selected && <QueryDetail app={app} service={service} listed={selected} onClose={() => setSelected(undefined)} />}
    </div>
  );
}

/** The listed entry shows at once; its result rows come from the full entry, which the list leaves out. */
function QueryDetail({ app, service, listed, onClose }: { app: string; service: SqlService; listed: DbQuery; onClose: () => void }) {
  const full = useLoad(() => api.db(app, service).query(listed.id), [app, service, listed.id]);
  const q = full.data ?? listed;
  const o = outcome(q);
  const shown = q.rows?.length ?? 0;
  const result: TableResult | undefined = q.columns
    ? {
        columns: q.columns,
        rows: q.rows ?? [],
        truncated: q.truncated,
        note: q.rowCount !== null && shown < q.rowCount ? `showing ${number(shown)} of ${number(q.rowCount)} rows` : undefined,
        clippedCells: [],
      }
    : undefined;
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-5xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2 font-mono text-sm">
            {q.command && <span>{q.command}</span>}
            <span className={cn("font-medium", o.tone)}>{o.text}</span>
            {q.truncated && <Badge variant="outline">truncated</Badge>}
          </DialogTitle>
          <DialogDescription>
            {clock(q.time)} · {duration(q.durationMs)} · {q.client ?? "unknown client"} · connection {q.connectionId}
          </DialogDescription>
        </DialogHeader>

        {full.data?.replaySql !== undefined && full.data.params.length > 0 && (
          <section className="flex min-w-0 flex-col gap-2">
            <div className="flex items-center justify-between gap-2">
              <h3 className="font-heading text-sm font-medium">Replayable SQL</h3>
              <CopyButton text={full.data.replaySql} />
            </div>
            <CodeBlock className="max-h-64">{full.data.replaySql}</CodeBlock>
            {full.data.replayComplete === false && (
              <p className="text-xs text-amber-600 dark:text-amber-400">
                Some values were cut or are missing, so this SQL may not run exactly as logged.
              </p>
            )}
          </section>
        )}

        <section className="flex min-w-0 flex-col gap-2">
          <div className="flex items-center justify-between gap-2">
            <h3 className="font-heading text-sm font-medium">SQL</h3>
            <CopyButton text={q.sql} />
          </div>
          <CodeBlock className="max-h-64">{q.sql}</CodeBlock>
        </section>

        {q.error && <ErrorAlert title={`Error ${q.error.code}`} message={q.error.message} />}

        <section className="flex min-w-0 flex-col gap-2">
          <h3 className="font-heading text-sm font-medium">Parameters</h3>
          {q.params.length === 0 ? (
            <p className="text-xs text-muted-foreground">No parameters.</p>
          ) : (
            <div className="max-h-48 overflow-auto rounded-lg border">
              <Table>
                <TableBody>
                  {q.params.map((p, i) => (
                    <TableRow key={i}>
                      <TableCell className="w-12 align-top font-mono text-xs text-muted-foreground">${i + 1}</TableCell>
                      <TableCell className={cn("font-mono text-xs break-all whitespace-normal", p === null && "text-muted-foreground italic")}>
                        {p === null ? "NULL" : p}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </section>

        {full.error && <ErrorAlert title="Could not load the result rows" message={full.error} />}
        {!full.data && !full.error && q.rowCount !== null && <Skeleton className="h-32" />}
        {result && (
          <section className="flex min-w-0 flex-col gap-2">
            <h3 className="font-heading text-sm font-medium">Results</h3>
            <DataGrid result={result} />
          </section>
        )}
      </DialogContent>
    </Dialog>
  );
}
