import { type ReactNode, useEffect, useState } from "react";
import { DatabaseIcon, DownloadCloudIcon, FileCodeIcon, ListIcon, TerminalSquareIcon } from "lucide-react";
import { api } from "@/api";
import { ErrorAlert, IconStrip, STRIP_BUTTON } from "@/components/common";
import { DatabaseOverview } from "@/components/db/DatabaseOverview";
import { DdlReviewModal, type PendingDdl } from "@/components/db/DdlReviewModal";
import { type TableRef } from "@/components/db/format";
import { QueryList } from "@/components/db/QueryList";
import { MigrationsPanel } from "@/components/db/MigrationsPanel";
import { NewTableForm } from "@/components/db/NewTableForm";
import { SqlConsole } from "@/components/db/SqlConsole";
import { TablesAside } from "@/components/db/TablesAside";
import { TableView } from "@/components/db/TableView";
import { SyncDialog } from "@/components/share/SyncDialog";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { useLoad } from "@/hooks";
import { HeaderSlot } from "@/lib/header";
import { href, navigate } from "@/lib/router";
import { ServiceIcon, serviceLabel } from "@/lib/services";
import { cn } from "@/lib/utils";
import type { DdlRequest, SourceInfo, SqlService } from "@/types";

/** A tab panel that fills the area under the top bar and scrolls on its own. */
const PANEL = "flex min-h-0 flex-col";

const SYNC_POLL_MS = 2000;
const STATUS_POLL_MS = 5000;

/** Postgres / MySQL explorer: browse and edit tables, change the schema, run Flyway migrations, or use SQL. */
export function DatabasePage({ app, service, table }: { app: string; service: SqlService; table?: string[] }) {
  const info = useLoad(() => api.db(app, service).info(), [app, service]);
  const [creating, setCreating] = useState(false);
  const [pending, setPending] = useState<PendingDdl>();
  const [refreshKey, setRefreshKey] = useState(0);
  const [syncing, setSyncing] = useState(false);
  const source = useLoad(() => api.db(app, service).source(), [app, service]);
  // Polled like the app page, so turning the query log on or off elsewhere shows or hides the tab.
  const status = useLoad(() => api.status(app), [app], STATUS_POLL_MS);
  const queryLog = status.data?.services.find((s) => s.id === service)?.options.query_log === "true";
  const syncRunning = source.data?.job?.state === "running";
  const data = info.data;
  // The open table lives in the URL, so it survives a reload and can be linked. A MySQL link names only the table;
  // its schema is the database.
  const selected: TableRef | undefined =
    table && data ? (table.length > 1 ? { schema: table[0]!, name: table[1]! } : { schema: data.database, name: table[0]! }) : undefined;
  const setSelected = (next: TableRef | undefined, replace = false) =>
    navigate(
      next ? href.table(app, service, data?.hasSchemas ? [next.schema, next.name] : [next.name]) : href.tool(app, service),
      { replace },
    );

  // Follow a running sync (started here, from the CLI, or before a reload) so the button shows it, then refresh once done.
  useEffect(() => {
    if (!syncRunning) return;
    const id = setInterval(source.reload, SYNC_POLL_MS);
    return () => {
      clearInterval(id);
      info.reload();
    };
    // reload functions are stable per load; restarting the poll on each render would reset its timer.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [syncRunning]);

  const refresh = () => {
    info.reload();
    source.reload();
    setRefreshKey((k) => k + 1);
  };
  const requestDdl = (title: string, request: DdlRequest, description: string) => setPending({ title, request, description });

  const ddlDone = () => {
    const request = pending?.request;
    const touchesSelection =
      request &&
      selected &&
      (request.op === "drop_table" || request.op === "rename_table") &&
      request.table === selected.name &&
      request.schema === selected.schema;
    // Replaced, not pushed: Back must not return to a table that no longer exists under that name.
    if (touchesSelection && request.op === "rename_table") setSelected({ schema: selected.schema, name: request.newName ?? selected.name }, true);
    else if (touchesSelection || (request?.op === "drop_schema" && request.schema === selected?.schema)) setSelected(undefined, true);
    if (request?.op === "create_table") setCreating(false);
    refresh();
  };

  const select = (table: TableRef) => {
    setCreating(false);
    setSelected(table);
  };
  const newTable = () => {
    setSelected(undefined);
    setCreating(true);
  };

  const defaultSchema = data?.hasSchemas ? (data.schemas.includes("public") ? "public" : (data.schemas[0] ?? "")) : "";

  return (
    <>
      <HeaderSlot slot="crumb">
        <ServiceIcon id={service} className="size-4 shrink-0" />
        <span className="truncate">{data?.database ?? serviceLabel(service)}</span>
        {data && <span className="shrink-0 text-muted-foreground">({serviceLabel(service)})</span>}
      </HeaderSlot>
      <SyncDialog
        app={app}
        service={service}
        open={syncing}
        onOpenChange={setSyncing}
        database={data?.database}
        onSynced={refresh}
        onSaved={source.reload}
        onStarted={source.reload}
      />
      {/* Full bleed: cancels the content padding and fills the viewport under the top bar (and, on md+, the inset panel's
          margin), so each pane scrolls itself. */}
      <div className="-m-4 flex h-[calc(100svh-2.75rem)] min-h-0 flex-col md:-m-6 md:h-[calc(100svh-3.75rem)]">
        {(incompleteSync(source.data) || info.error) && (
          <div className="flex shrink-0 flex-col gap-4 border-b p-4">
            <ErrorAlert message={incompleteSync(source.data)} title="Local data is incomplete" />
            <ErrorAlert message={info.error} title={`Could not reach ${serviceLabel(service)}`} />
          </div>
        )}
        <Tabs defaultValue="browse" className="min-h-0 flex-1 gap-0">
          {/* The tab switcher and sync live in the top bar as one icon strip; the portal keeps them inside these tabs. */}
          <HeaderSlot slot="actions">
            <IconStrip>
              <TabsList className="h-full bg-transparent p-0">
                <TabIcon value="browse" label="Browse" icon={<DatabaseIcon />} />
                <TabIcon value="migrations" label="Migrations" icon={<FileCodeIcon />} />
                <TabIcon value="sql" label="SQL" icon={<TerminalSquareIcon />} />
                {queryLog && <TabIcon value="queries" label="Query logs" icon={<ListIcon />} />}
              </TabsList>
              <Separator orientation="vertical" className="mx-0.5 data-vertical:h-4 data-vertical:self-center" />
              <SyncButton info={source.data} running={syncRunning} onClick={() => setSyncing(true)} />
            </IconStrip>
          </HeaderSlot>
          <TabsContent value="browse" className="flex min-h-0">
            <ScrollArea className="w-64 shrink-0">
              <TablesAside
                info={data}
                selected={selected}
                overview={!selected && !creating}
                onSelect={select}
                onOverview={() => {
                  setCreating(false);
                  setSelected(undefined);
                }}
                onNewTable={newTable}
                onRefresh={refresh}
              />
            </ScrollArea>
            <Separator orientation="vertical" />
            {/* A table fills the pane and scrolls its rows itself; everything else scrolls in the padded pane. */}
            {selected && data ? (
              <TableView
                key={`${selected.schema}.${selected.name}`}
                app={app}
                service={service}
                hasSchemas={data.hasSchemas}
                table={selected}
                refreshKey={refreshKey}
                onDdl={requestDdl}
                onChanged={info.reload}
                source={source.data}
                onSourceChanged={source.reload}
              />
            ) : (
              <ScrollArea className="min-w-0 flex-1">
                <div className="flex min-w-0 flex-col gap-6 p-4 md:p-6">
                  {creating && data && (
                    <NewTableForm
                      service={service}
                      schemas={data.hasSchemas ? data.schemas : []}
                      defaultSchema={defaultSchema}
                      onDdl={requestDdl}
                      onCancel={() => setCreating(false)}
                    />
                  )}
                  {!creating && data && <DatabaseOverview info={data} onSelect={select} onDdl={requestDdl} onNewTable={newTable} />}
                  {!data && !info.error && <Skeleton className="h-64 w-full" />}
                </div>
              </ScrollArea>
            )}
          </TabsContent>
          <TabsContent value="migrations" className={PANEL}>
            <Padded>
              <MigrationsPanel app={app} service={service} onChanged={refresh} />
            </Padded>
          </TabsContent>
          <TabsContent value="sql" className={PANEL}>
            <Padded>
              <SqlConsole app={app} service={service} onRan={refresh} />
            </Padded>
          </TabsContent>
          {queryLog && (
            <TabsContent value="queries" className={PANEL}>
              <Padded>
                <QueryList app={app} service={service} />
              </Padded>
            </TabsContent>
          )}
        </Tabs>
      </div>
      {pending && <DdlReviewModal app={app} service={service} pending={pending} onClose={() => setPending(undefined)} onDone={ddlDone} />}
    </>
  );
}

/** Why the local database may be half-copied: a sync that failed mid-copy, or one a restart or kill cut off. */
function incompleteSync(info: SourceInfo | undefined): string | undefined {
  const job = info?.job;
  if (!job?.incomplete) return undefined;
  const copyFailed = ["interrupted", "clearing local data", "copying"].includes(job.phase);
  if (copyFailed && job.message) return job.message;
  return "An earlier sync was cut off mid-copy, so the local database is incomplete. Retry the sync or destroy the app.";
}

/** The source is complete and its password saved, so a sync can start without asking for anything. */
function readySource(info: SourceInfo | undefined) {
  const source = info?.source;
  if (!source || !info.passwordSaved || !source.host || !source.port || !source.database || !source.user) return undefined;
  return `${source.user}@${source.host}:${source.port}/${source.database}`;
}

/** A padded, independently scrolling body for the tabs that are not split into panes. */
function Padded({ children }: { children: ReactNode }) {
  return (
    <ScrollArea className="min-h-0 flex-1">
      <div className="flex min-w-0 flex-col gap-6 p-4 md:p-6">{children}</div>
    </ScrollArea>
  );
}

/** An icon-only tab for the top-bar strip, named by its tooltip and aria-label. */
function TabIcon({ value, label, icon }: { value: string; label: string; icon: ReactNode }) {
  return (
    <Tooltip>
      <TooltipTrigger render={<TabsTrigger value={value} aria-label={label} className="aspect-square px-0" />}>{icon}</TooltipTrigger>
      <TooltipContent>{label}</TooltipContent>
    </Tooltip>
  );
}

/**
 * Opens the sync dialog. Always enabled: a sync starts the local database itself, so it works while the info load
 * fails, and while one runs it reopens the progress. A dot marks a source that is ready to sync.
 */
function SyncButton({ info, running, onClick }: { info: SourceInfo | undefined; running: boolean; onClick: () => void }) {
  const ready = readySource(info);
  const label = running ? "Syncing…" : "Sync from source";
  return (
    <Tooltip>
      <TooltipTrigger
        render={<Button variant="ghost" size="icon-sm" className={cn(STRIP_BUTTON, "relative")} aria-label={label} onClick={onClick} />}
      >
        {running ? <Spinner /> : <DownloadCloudIcon />}
        {ready && !running && <span aria-hidden className="absolute top-1 right-1 size-1.5 rounded-full bg-emerald-500" />}
      </TooltipTrigger>
      <TooltipContent>
        {label}
        {ready && !running && (
          <>
            {" "}
            — ready from <span className="font-mono">{ready}</span>
          </>
        )}
      </TooltipContent>
    </Tooltip>
  );
}
