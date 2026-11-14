import { CodeBlock, CopyButton } from "@/components/common";
import { Badge } from "@/components/ui/badge";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Table, TableBody, TableCell, TableRow } from "@/components/ui/table";
import { prettyJson } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { GatewayCall } from "@/types";
import { clock, statusTone } from "./CallList";

export function CallDetail({ call, onClose }: { call: GatewayCall; onClose: () => void }) {
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-5xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2 font-mono text-sm">
            <span>{call.method}</span>
            <span className="min-w-0 truncate">{call.uri}</span>
            <span className={cn("font-medium", statusTone(call.status))}>{call.status}</span>
          </DialogTitle>
          <DialogDescription>
            {clock(call.time)} · {call.latencyMs} ms
          </DialogDescription>
        </DialogHeader>

        <dl className="grid grid-cols-[8rem_minmax(0,1fr)] gap-x-4 gap-y-1.5 text-sm">
          <Fact label="Auth decision">
            {call.authDecision ? (
              <>
                <Badge variant={call.authDecision === "allow" ? "secondary" : "destructive"}>{call.authDecision}</Badge>
                {call.authReason && <span className="ml-2 text-muted-foreground">{call.authReason}</span>}
              </>
            ) : (
              "-"
            )}
          </Fact>
          <Fact label="User">{call.userId ?? "-"}</Fact>
          <Fact label="Route">{call.route ?? "-"}</Fact>
          <Fact label="Request id">
            <span className="flex items-center gap-2">
              <span className="font-mono text-xs">{call.id}</span>
              <CopyButton text={call.id} />
            </span>
          </Fact>
        </dl>

        <Exchange title="Request" headers={call.requestHeaders} body={call.requestBody} truncated={call.truncated} />
        <Exchange title="Response" headers={call.responseHeaders} body={call.responseBody} truncated={call.truncated} />
      </DialogContent>
    </Dialog>
  );
}

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="min-w-0 break-all">{children}</dd>
    </>
  );
}

function Exchange({
  title,
  headers,
  body,
  truncated,
}: {
  title: string;
  headers: Record<string, string>;
  body: string | null;
  truncated: boolean;
}) {
  const entries = Object.entries(headers);
  const shown = body ? (prettyJson(body) ?? body) : undefined;
  return (
    <section className="flex min-w-0 flex-col gap-2">
      <h3 className="font-heading text-sm font-medium">{title}</h3>
      {entries.length > 0 ? (
        <div className="max-h-48 overflow-auto rounded-lg border">
          <Table>
            <TableBody>
              {entries.map(([k, v]) => (
                <TableRow key={k}>
                  <TableCell className="w-48 align-top font-mono text-xs text-muted-foreground">{k}</TableCell>
                  <TableCell className="font-mono text-xs break-all whitespace-normal">{v}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      ) : (
        <p className="text-xs text-muted-foreground">No headers.</p>
      )}
      {shown ? (
        <CodeBlock className="max-h-64">{shown}</CodeBlock>
      ) : (
        <p className="text-xs text-muted-foreground">No body.</p>
      )}
      {shown && truncated && <p className="text-xs text-muted-foreground">(truncated)</p>}
    </section>
  );
}
