import { useState } from "react";
import { Table2Icon } from "lucide-react";
import { api } from "@/api";
import { ErrorAlert } from "@/components/common";
import { Badge } from "@/components/ui/badge";
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useLoad } from "@/hooks";
import type { SqlService } from "@/types";
import { plural, qualifiedName, type RequestDdl, type TableRef } from "./format";
import { IndexesTab } from "./IndexesTab";
import { RowsGrid } from "./RowsGrid";
import { StructureTab } from "./StructureTab";
import { TableActionButtons } from "./TableActions";

/** One table: header with table-level actions, then Data / Structure / Indexes. */
export function TableView({
  app,
  service,
  hasSchemas,
  table,
  refreshKey,
  onDdl,
  onChanged,
}: {
  app: string;
  service: SqlService;
  hasSchemas: boolean;
  table: TableRef;
  refreshKey: number;
  onDdl: RequestDdl;
  onChanged: () => void;
}) {
  const [rowsChanged, setRowsChanged] = useState(0);
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
                refreshKey={refreshKey}
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
