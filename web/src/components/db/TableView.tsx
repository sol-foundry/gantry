import { useState } from "react";
import { toast } from "sonner";
import { DownloadCloudIcon, Table2Icon } from "lucide-react";
import { api } from "@/api";
import { ErrorAlert, IconButton } from "@/components/common";
import { Badge } from "@/components/ui/badge";
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useLoad } from "@/hooks";
import type { SourceInfo, SqlService } from "@/types";
import { plural, qualifiedName, type RequestDdl, type TableRef } from "./format";
import { IndexesTab } from "./IndexesTab";
import { RowsGrid } from "./RowsGrid";
import { StructureTab } from "./StructureTab";
import { TableActionButtons } from "./TableActions";

/** One table: header with table-level actions, then Data / Structure / Indexes. */
const SYNC_POLL_MS = 1000;

/** Why the table can't be synced in one click yet, or undefined when its source and password are saved. */
function missingSource(info: SourceInfo | undefined): string | undefined {
  if (!info) return "Loading the source settings…";
  const source = info.source;
  const absent = [
    !source?.host && "host",
    !source?.port && "port",
    !source?.database && "database",
    !source?.user && "user",
    !info.passwordSaved && "password",
  ].filter(Boolean);
  if (absent.length === 0) return undefined;
  return `Set up the source first (missing ${absent.join(", ")}): use Sync from source at the top of the database page.`;
}

export function TableView({
  app,
  service,
  hasSchemas,
  table,
  refreshKey,
  onDdl,
  onChanged,
  source,
  onSourceChanged,
}: {
  app: string;
  service: SqlService;
  hasSchemas: boolean;
  table: TableRef;
  refreshKey: number;
  onDdl: RequestDdl;
  onChanged: () => void;
  /** The database's saved source, owned by the page so saving it in the sync dialog updates this button too. */
  source: SourceInfo | undefined;
  onSourceChanged: () => void;
}) {
  const [rowsChanged, setRowsChanged] = useState(0);
  const [syncing, setSyncing] = useState(false);
  // Bumped after a table sync so the rows grid reloads too (its own edits reload it already).
  const [synced, setSynced] = useState(0);
  const missing = missingSource(source);
  const syncName = hasSchemas ? `${table.schema}.${table.name}` : table.name;

  // With the source and its password saved there's nothing to ask, so the sync starts at once and is followed here.
  const syncTable = async () => {
    const saved = source?.source;
    if (!saved) return;
    setSyncing(true);
    try {
      await api.db(app, service).startSync(saved, undefined, [syncName]);
      let job = await api.db(app, service).syncJob();
      while (job.state === "running") {
        await new Promise((resolve) => setTimeout(resolve, SYNC_POLL_MS));
        job = await api.db(app, service).syncJob();
      }
      if (job.state === "succeeded") toast.success(job.message ?? `Synced ${syncName}`);
      else toast.error(job.message ?? `Syncing ${syncName} failed`);
      setRowsChanged((n) => n + 1);
      setSynced((n) => n + 1);
      onChanged();
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setSyncing(false);
      onSourceChanged();
    }
  };
  const detail = useLoad(
    () => api.db(app, service).table(table.schema, table.name),
    [app, service, table.schema, table.name, refreshKey, rowsChanged],
  );
  const label = qualifiedName(hasSchemas, table.schema, table.name);
  const data = detail.data;

  return (
    <Card className="min-w-0">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 font-mono text-base">
          <Table2Icon className="size-4 text-muted-foreground" />
          {label}
          {data && data.kind !== "table" && <Badge variant="outline">{data.kind}</Badge>}
        </CardTitle>
        <CardDescription>
          {data ? (
            <>
              {data.rowCount != null ? plural(data.rowCount, "row") : "row count unavailable"} · {plural(data.columns.length, "column")} ·{" "}
              {plural(data.indexes.length, "index")}
            </>
          ) : (
            <Skeleton className="h-4 w-40" />
          )}
        </CardDescription>
        {data && (
          <CardAction className="flex flex-wrap gap-2">
            {data.kind === "table" && (
              <IconButton label={syncing ? `Syncing ${label}…` : "Sync from source"} tooltip={missing} disabled={syncing || !!missing} onClick={syncTable}>
                {syncing ? <Spinner /> : <DownloadCloudIcon />}
              </IconButton>
            )}
            <TableActionButtons label={label} kind={data.kind} base={{ schema: table.schema, table: table.name }} onDdl={onDdl} />
          </CardAction>
        )}
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <ErrorAlert message={detail.error} />
        {data && (
          <Tabs defaultValue="data">
            <TabsList variant="line">
              <TabsTrigger value="data">Data</TabsTrigger>
              <TabsTrigger value="structure">Structure</TabsTrigger>
              <TabsTrigger value="indexes">Indexes &amp; constraints</TabsTrigger>
            </TabsList>
            <TabsContent value="data" className="flex flex-col pt-2">
              <RowsGrid
                app={app}
                service={service}
                table={data}
                refreshKey={refreshKey + synced}
                onChanged={() => {
                  setRowsChanged((n) => n + 1);
                  onChanged();
                }}
              />
            </TabsContent>
            <TabsContent value="structure" className="pt-2">
              <StructureTab service={service} table={data} onDdl={onDdl} />
            </TabsContent>
            <TabsContent value="indexes" className="pt-2">
              <IndexesTab table={data} onDdl={onDdl} />
            </TabsContent>
          </Tabs>
        )}
      </CardContent>
    </Card>
  );
}
