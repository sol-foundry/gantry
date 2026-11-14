import { PauseIcon, PlayIcon, RefreshCwIcon, Trash2Icon } from "lucide-react";
import { ConfirmButton, IconButton } from "@/components/common";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { cn } from "@/lib/utils";
import type { GatewayCall } from "@/types";

export const STATUS_CLASSES = ["", "2xx", "3xx", "4xx", "5xx"] as const;

/** Local HH:mm:ss.SSS of an ISO timestamp. */
export function clock(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const p = (n: number, w = 2) => String(n).padStart(w, "0");
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${p(d.getMilliseconds(), 3)}`;
}

/** Status colour by class, shared by the list and the detail. */
export function statusTone(status: number): string {
  if (status >= 500) return "text-destructive";
  if (status >= 400) return "text-amber-600 dark:text-amber-400";
  if (status >= 300) return "text-sky-600 dark:text-sky-400";
  if (status >= 200) return "text-emerald-600 dark:text-emerald-400";
  return "text-muted-foreground";
}

export function CallFilters({
  status,
  onStatus,
  path,
  onPath,
  paused,
  onTogglePause,
  onRefresh,
  onClear,
}: {
  status: string;
  onStatus: (status: string) => void;
  path: string;
  onPath: (path: string) => void;
  paused: boolean;
  onTogglePause: () => void;
  onRefresh: () => void;
  onClear: () => Promise<unknown>;
}) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <ToggleGroup
        variant="outline"
        size="sm"
        spacing={0}
        value={[status]}
        onValueChange={(next) => onStatus(next[0] ?? "")}
        aria-label="Status class"
      >
        {STATUS_CLASSES.map((s) => (
          <ToggleGroupItem key={s || "all"} value={s}>
            {s || "All"}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
      <Input
        value={path}
        onChange={(e) => onPath(e.target.value)}
        placeholder="Path glob, e.g. /api/*"
        aria-label="Path glob"
        className="h-7 w-56 font-mono text-xs"
      />
      <div className="ml-auto flex items-center gap-1">
        {paused && <Badge variant="outline">Paused</Badge>}
        <IconButton label={paused ? "Resume auto-refresh" : "Pause auto-refresh"} onClick={onTogglePause}>
          {paused ? <PlayIcon /> : <PauseIcon />}
        </IconButton>
        <IconButton label="Refresh now" onClick={onRefresh}>
          <RefreshCwIcon />
        </IconButton>
        <ConfirmButton
          destructive
          variant="outline"
          size="sm"
          title="Clear the call log?"
          description="Every recorded call through the gateway is deleted. New calls keep being recorded."
          confirmLabel="Clear"
          onConfirm={onClear}
        >
          <Trash2Icon />
          Clear
        </ConfirmButton>
      </div>
    </div>
  );
}

export function CallTable({ calls, onSelect }: { calls: GatewayCall[]; onSelect: (call: GatewayCall) => void }) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Time</TableHead>
          <TableHead>Method</TableHead>
          <TableHead>Path</TableHead>
          <TableHead>Status</TableHead>
          <TableHead className="text-right">Latency</TableHead>
          <TableHead>User</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {calls.map((c) => (
          <TableRow key={c.id} className="cursor-pointer" onClick={() => onSelect(c)}>
            <TableCell className="font-mono text-xs text-muted-foreground">{clock(c.time)}</TableCell>
            <TableCell className="font-mono text-xs font-medium">{c.method}</TableCell>
            <TableCell className="max-w-[28rem] truncate font-mono text-xs">{c.uri}</TableCell>
            <TableCell className={cn("font-mono text-xs font-medium", statusTone(c.status))}>{c.status}</TableCell>
            <TableCell className="text-right font-mono text-xs tabular-nums">{c.latencyMs} ms</TableCell>
            <TableCell className="font-mono text-xs text-muted-foreground">{c.userId ?? "-"}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
