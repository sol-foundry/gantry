import { useEffect, useState } from "react";
import { toast } from "sonner";
import { InboxIcon, ListIcon, SettingsIcon, ShieldIcon, WaypointsIcon } from "lucide-react";
import { api } from "@/api";
import { ErrorAlert, PageHeader } from "@/components/common";
import { AuthorizerList } from "@/components/gateway/AuthorizerList";
import { CallDetail } from "@/components/gateway/CallDetail";
import { CallFilters, CallTable } from "@/components/gateway/CallList";
import { ConfigEditor } from "@/components/gateway/ConfigEditor";
import { Card, CardContent } from "@/components/ui/card";
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useLoad } from "@/hooks";
import { HeaderSlot } from "@/lib/header";
import { serviceLabel } from "@/lib/services";
import type { GatewayCall } from "@/types";

const CALLS_POLL_MS = 2000;
const CALLS_LIMIT = 200;
const PATH_DEBOUNCE_MS = 300;

/** The REST API gateway: live call log, gateway.yml editor and custom authorizers. */
export function GatewayPage({ app }: { app: string }) {
  return (
    <>
      <HeaderSlot slot="crumb">
        <WaypointsIcon className="size-4 shrink-0" />
        <span>{serviceLabel("gateway")}</span>
      </HeaderSlot>
      <PageHeader
        icon={<WaypointsIcon className="size-5" />}
        title={serviceLabel("gateway")}
        description="Calls through the gateway, its gateway.yml, and custom authorizers."
      />
      <Tabs defaultValue="calls" className="flex flex-col gap-4">
        <TabsList>
          <TabsTrigger value="calls">
            <ListIcon />
            Calls
          </TabsTrigger>
          <TabsTrigger value="config">
            <SettingsIcon />
            Config
          </TabsTrigger>
          <TabsTrigger value="authorizers">
            <ShieldIcon />
            Authorizers
          </TabsTrigger>
        </TabsList>
        <TabsContent value="calls">
          <Calls app={app} />
        </TabsContent>
        <TabsContent value="config" keepMounted>
          <ConfigEditor app={app} />
        </TabsContent>
        <TabsContent value="authorizers">
          <AuthorizerList app={app} />
        </TabsContent>
      </Tabs>
    </>
  );
}

function Calls({ app }: { app: string }) {
  const [status, setStatus] = useState("");
  const [pathInput, setPathInput] = useState("");
  const [path, setPath] = useState("");
  // The glob is sent after typing pauses, not on every keystroke.
  useEffect(() => {
    const id = setTimeout(() => setPath(pathInput), PATH_DEBOUNCE_MS);
    return () => clearTimeout(id);
  }, [pathInput]);
  const [paused, setPaused] = useState(false);
  const [selected, setSelected] = useState<GatewayCall>();
  const calls = useLoad(() => api.gatewayCalls(app, { status, path, limit: CALLS_LIMIT }), [app, status, path], paused ? undefined : CALLS_POLL_MS);
  const port = useLoad(() => api.status(app), [app]).data?.services.find((s) => s.id === "gateway")?.ports.main;
  const filtered = status !== "" || path !== "";
  const clear = async () => {
    try {
      await api.clearGatewayCalls(app);
      setSelected(undefined);
      toast.success("Cleared the call log");
      calls.reload();
    } catch (e) {
      toast.error((e as Error).message);
    }
  };

  return (
    <div className="flex flex-col gap-4">
      <CallFilters
        status={status}
        onStatus={setStatus}
        path={pathInput}
        onPath={setPathInput}
        paused={paused}
        onTogglePause={() => setPaused((p) => !p)}
        onRefresh={calls.reload}
        onClear={clear}
      />
      <ErrorAlert message={calls.error} />
      {calls.data === undefined && !calls.error && <Skeleton className="h-48" />}
      {calls.data?.length === 0 && (
        <Card>
          <CardContent>
            <Empty className="py-8">
              <EmptyHeader>
                <EmptyMedia variant="icon">
                  <InboxIcon />
                </EmptyMedia>
                <EmptyTitle>{filtered ? "No matching calls" : "No calls yet"}</EmptyTitle>
                <EmptyDescription>
                  {filtered
                    ? "Nothing in the log matches these filters."
                    : `No calls yet — send a request to http://localhost:${port ?? "<gateway port>"}`}
                </EmptyDescription>
              </EmptyHeader>
            </Empty>
          </CardContent>
        </Card>
      )}
      {calls.data && calls.data.length > 0 && (
        <div className="rounded-lg border">
          <CallTable calls={calls.data} onSelect={setSelected} />
        </div>
      )}
      {selected && <CallDetail call={selected} onClose={() => setSelected(undefined)} />}
    </div>
  );
}
