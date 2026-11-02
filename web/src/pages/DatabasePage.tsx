import { useEffect, useState } from "react";
import { DatabaseIcon, DownloadCloudIcon, FileCodeIcon, TerminalSquareIcon } from "lucide-react";
import { api } from "@/api";
import { ErrorAlert } from "@/components/common";
import { DatabaseOverview } from "@/components/db/DatabaseOverview";
import { DdlReviewModal, type PendingDdl } from "@/components/db/DdlReviewModal";
import { type TableRef } from "@/components/db/format";
import { MigrationsPanel } from "@/components/db/MigrationsPanel";
import { NewTableForm } from "@/components/db/NewTableForm";
import { SqlConsole } from "@/components/db/SqlConsole";
import { TablesAside } from "@/components/db/TablesAside";
import { TableView } from "@/components/db/TableView";
import { SyncDialog } from "@/components/share/SyncDialog";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { useLoad } from "@/hooks";
import { HeaderSlot } from "@/lib/header";
import { ServiceIcon, serviceLabel } from "@/lib/services";
import { cn } from "@/lib/utils";
import type { DdlRequest, SourceInfo, SqlService } from "@/types";

/** A tab panel that passes the remaining height on to its content. */
const PANEL = "flex flex-col";

const SYNC_POLL_MS = 2000;

/** Postgres / MySQL explorer: browse and edit tables, change the schema, run Flyway migrations, or use SQL. */
export function DatabasePage({ app, service }: { app: string; service: SqlService }) {
  const info = useLoad(() => api.db(app, service).info(), [app, service]);
  const [selected, setSelected] = useState<TableRef>();
  const [creating, setCreating] = useState(false);
  const [pending, setPending] = useState<PendingDdl>();
  const [refreshKey, setRefreshKey] = useState(0);
  const [syncing, setSyncing] = useState(false);
  const source = useLoad(() => api.db(app, service).source(), [app, service]);
  const syncRunning = source.data?.job?.state === "running";

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
    if (touchesSelection && request.op === "rename_table") setSelected({ schema: selected.schema, name: request.newName ?? selected.name });
    else if (touchesSelection || (request?.op === "drop_schema" && request.schema === selected?.schema)) setSelected(undefined);
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

  const data = info.data;
  const defaultSchema = data?.hasSchemas ? (data.schemas.includes("public") ? "public" : (data.schemas[0] ?? "")) : "";

  return (
    <>
      <HeaderSlot slot="crumb">
        <ServiceIcon id={service} className="size-4 shrink-0" />
        <span className="truncate">{data?.database ?? serviceLabel(service)}</span>
        {data && <span className="shrink-0 text-muted-foreground">({serviceLabel(service)})</span>}
      </HeaderSlot>
      <HeaderSlot slot="actions">
        <SyncButton info={source.data} running={syncRunning} onClick={() => setSyncing(true)} />
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
      <ErrorAlert message={incompleteSync(source.data)} title="Local data is incomplete" />
      <ErrorAlert message={info.error} title={`Could not reach ${serviceLabel(service)}`} />
      {/* The tabs take the rest of the viewport; each panel splits it so lists and grids scroll in their cards. */}
      <Tabs defaultValue="browse" className={cn("flex flex-col gap-6", "gap-4")}>
        <TabsList>
          <TabsTrigger value="browse">
            <DatabaseIcon />
            Browse
          </TabsTrigger>
          <TabsTrigger value="migrations">
            <FileCodeIcon />
            Migrations
          </TabsTrigger>
          <TabsTrigger value="sql">
            <TerminalSquareIcon />
            SQL
          </TabsTrigger>
        </TabsList>
        <TabsContent value="browse" className={PANEL}>
          <div className="grid items-start gap-6 lg:grid-cols-[16rem_minmax(0,1fr)]">
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
            <div className="flex min-w-0 flex-col gap-6">
              {creating && data && (
                <NewTableForm
                  service={service}
                  schemas={data.hasSchemas ? data.schemas : []}
                  defaultSchema={defaultSchema}
                  onDdl={requestDdl}
                  onCancel={() => setCreating(false)}
                />
              )}
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
                />
              ) : (
                !creating && data && <DatabaseOverview info={data} onSelect={select} onDdl={requestDdl} onNewTable={newTable} />
              )}
              {!data && !info.error && <Skeleton className="h-64 w-full" />}
            </div>
          </div>
        </TabsContent>
        <TabsContent value="migrations" className={PANEL}>
          <MigrationsPanel app={app} service={service} onChanged={refresh} />
        </TabsContent>
        <TabsContent value="sql" className={PANEL}>
          <SqlConsole app={app} service={service} onRan={refresh} />
        </TabsContent>
      </Tabs>
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

/**
 * Opens the sync dialog. Always enabled: a sync starts the local database itself, so it works while the info load
 * fails, and while one runs it reopens the progress. A dot marks a source that is ready to sync.
 */
function SyncButton({ info, running, onClick }: { info: SourceInfo | undefined; running: boolean; onClick: () => void }) {
  const ready = readySource(info);
  const button = (
    <Button variant="outline" size="sm" onClick={onClick}>
      {running ? <Spinner /> : <DownloadCloudIcon />}
      {running ? "Syncing…" : "Sync from source"}
      {ready && !running && <span aria-label="Source ready" className="size-1.5 rounded-full bg-emerald-500" />}
    </Button>
  );
  if (!ready || running) return button;
  return (
    <Tooltip>
      <TooltipTrigger render={button} />
      <TooltipContent>
        Ready to sync from <span className="font-mono">{ready}</span>
      </TooltipContent>
    </Tooltip>
  );
}
