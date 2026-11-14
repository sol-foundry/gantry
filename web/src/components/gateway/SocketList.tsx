import { RadioTowerIcon } from "lucide-react";
import { api } from "@/api";
import { ErrorAlert } from "@/components/common";
import { clock } from "@/components/gateway/CallList";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useLoad } from "@/hooks";
import type { SocketEvent } from "@/types";

const SOCKETS_POLL_MS = 2000;

const EVENT_VARIANT: Record<string, "default" | "secondary" | "destructive" | "outline"> = {
  connect: "default",
  disconnect: "outline",
  $disconnect: "outline",
  rejected: "destructive",
  gone: "destructive",
  message: "secondary",
  post: "secondary",
};

function detail(e: SocketEvent): string {
  switch (e.type) {
    case "post":
      return `${e.connectionId}: ${e.bytes} bytes pushed`;
    case "gone":
      return `${e.connectionId}: push to a closed connection (410)`;
    case "message":
      return `${e.connectionId} → ${e.route ?? "no route"} (${e.status})`;
    case "rejected":
      return `${e.connectionId}: $connect answered ${e.status}`;
    case "disconnect":
      return `${e.connectionId} (${e.reason})`;
    case "$disconnect":
      return `${e.connectionId}: $disconnect answered ${e.status}`;
    default:
      return `${e.connectionId}${e.path ? ` on ${e.path}` : ""}`;
  }
}

/** The gateway's WebSocket API: open connections, and what happened on them, newest first. */
export function SocketList({ app }: { app: string }) {
  const status = useLoad(() => api.gatewaySockets(app), [app], SOCKETS_POLL_MS);
  const data = status.data;

  return (
    <div className="flex flex-col gap-4">
      <ErrorAlert message={status.error} />
      {data === undefined && !status.error && <Skeleton className="h-48" />}
      {data && !data.running && (
        <Card>
          <CardContent>
            <Empty className="py-8">
              <EmptyHeader>
                <EmptyMedia variant="icon">
                  <RadioTowerIcon />
                </EmptyMedia>
                <EmptyTitle>The WebSocket API is not running</EmptyTitle>
                <EmptyDescription>Start the app to accept WebSocket connections.</EmptyDescription>
              </EmptyHeader>
            </Empty>
          </CardContent>
        </Card>
      )}
      {data?.running && (
        <>
          <div className="rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Connection</TableHead>
                  <TableHead>User</TableHead>
                  <TableHead>Since</TableHead>
                  <TableHead>Last active</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.connections.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={4} className="text-muted-foreground">
                      No open connections
                    </TableCell>
                  </TableRow>
                )}
                {data.connections.map((c) => (
                  <TableRow key={c.connectionId}>
                    <TableCell className="font-mono text-xs">{c.connectionId}</TableCell>
                    <TableCell className="font-mono text-xs">{c.userId ?? "—"}</TableCell>
                    <TableCell className="font-mono text-xs">{c.since ? clock(c.since) : "—"}</TableCell>
                    <TableCell className="font-mono text-xs">{c.lastActiveAt ? clock(c.lastActiveAt) : "—"}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <div className="rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Time</TableHead>
                  <TableHead>Event</TableHead>
                  <TableHead>User</TableHead>
                  <TableHead>Detail</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.events.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={4} className="text-muted-foreground">
                      No socket events yet
                    </TableCell>
                  </TableRow>
                )}
                {data.events.map((e, i) => (
                  <TableRow key={`${e.time}-${i}`}>
                    <TableCell className="font-mono text-xs">{clock(e.time)}</TableCell>
                    <TableCell>
                      <Badge variant={EVENT_VARIANT[e.type] ?? "outline"}>{e.type}</Badge>
                    </TableCell>
                    <TableCell className="font-mono text-xs">{e.userId ?? "—"}</TableCell>
                    <TableCell className="text-xs">{detail(e)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        </>
      )}
    </div>
  );
}
