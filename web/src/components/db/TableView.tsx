import { useState } from "react";
import { toast } from "sonner";
import { Columns3Icon, DownloadCloudIcon, KeyRoundIcon, Table2Icon } from "lucide-react";
import { api } from "@/api";
import { ErrorAlert, IconStrip, StripButton } from "@/components/common";
import { Badge } from "@/components/ui/badge";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { useLoad } from "@/hooks";
import type { SourceInfo, SqlService } from "@/types";
import { plural, qualifiedName, type RequestDdl, type TableRef } from "./format";
import { IndexesTab } from "./IndexesTab";
import { PANE_HEADER, RowsGrid } from "./RowsGrid";
import { StructureTab } from "./StructureTab";
import { TableActionButtons } from "./TableActions";

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

/** One table: a pinned header with table-level actions over its rows; Structure and Indexes open in dialogs. */
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

  // Structure and Indexes open over the rows. A schema change closes them, so its review dialog isn't stacked on top.
  const [panel, setPanel] = useState<"structure" | "indexes">();
  const ddlFromPanel: RequestDdl = (...args) => {
    setPanel(undefined);
    onDdl(...args);
  };

  const heading = (
    <div className="flex items-center gap-3">
      <h2 className="flex min-w-0 flex-1 items-center gap-2 font-mono text-base font-medium">
        <Table2Icon className="size-4 shrink-0 text-muted-foreground" />
        <span className="truncate">{label}</span>
        {data && data.kind !== "table" && <Badge variant="outline">{data.kind}</Badge>}
      </h2>
      {!data && <Skeleton className="h-4 w-24" />}
      {data && (
        // The same strip as the page's top-bar group: the row count, what the table is, then what can be done to it.
        <IconStrip className="shrink-0">
          {data.rowCount != null && (
            <>
              <span className="px-2 text-xs text-foreground/60 tabular-nums dark:text-muted-foreground">{plural(data.rowCount, "row")}</span>
              <Separator orientation="vertical" className="mx-0.5 data-vertical:h-4 data-vertical:self-center" />
            </>
          )}
          <StripButton label={`Structure: ${plural(data.columns.length, "column")}`} onClick={() => setPanel("structure")}>
            <Columns3Icon />
          </StripButton>
          <StripButton label={`Indexes & constraints: ${plural(data.indexes.length, "index")}`} onClick={() => setPanel("indexes")}>
            <KeyRoundIcon />
          </StripButton>
          <Separator orientation="vertical" className="mx-0.5 data-vertical:h-4 data-vertical:self-center" />
          {data.kind === "table" && (
            <StripButton label={syncing ? `Syncing ${label}…` : "Sync from source"} tooltip={missing} disabled={syncing || !!missing} onClick={syncTable}>
              {syncing ? <Spinner /> : <DownloadCloudIcon />}
            </StripButton>
          )}
          <TableActionButtons label={label} kind={data.kind} base={{ schema: table.schema, table: table.name }} onDdl={onDdl} />
        </IconStrip>
      )}
    </div>
  );

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col">
      {data ? (
        <RowsGrid
          app={app}
          service={service}
          table={data}
          refreshKey={refreshKey + synced}
          heading={heading}
          onChanged={() => {
            setRowsChanged((n) => n + 1);
            onChanged();
          }}
        />
      ) : (
        <div className={PANE_HEADER}>{heading}</div>
      )}
      {detail.error && (
        <div className="p-4 md:p-6">
          <ErrorAlert message={detail.error} />
        </div>
      )}
      {data && (
        <Dialog open={!!panel} onOpenChange={(open) => !open && setPanel(undefined)}>
          <DialogContent className="flex max-h-[85vh] flex-col sm:max-w-5xl">
            <DialogHeader>
              <DialogTitle className="flex items-center gap-2">
                {panel === "structure" ? "Structure" : "Indexes & constraints"}
                <span className="font-mono text-sm font-normal text-muted-foreground">{label}</span>
              </DialogTitle>
            </DialogHeader>
            <div className="-mx-1 min-h-0 overflow-y-auto px-1">
              {panel === "structure" && <StructureTab service={service} table={data} onDdl={ddlFromPanel} />}
              {panel === "indexes" && <IndexesTab table={data} onDdl={ddlFromPanel} />}
            </div>
          </DialogContent>
        </Dialog>
      )}
    </div>
  );
}
