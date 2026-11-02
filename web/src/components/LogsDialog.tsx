import { RefreshCwIcon } from "lucide-react";
import { api } from "@/api";
import { CodeBlock, ErrorAlert } from "@/components/common";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { useLoad } from "@/hooks";
import { serviceLabel } from "@/lib/services";

const LOGS_POLL_MS = 3000;

export function LogsDialog({ app, service, onClose }: { app: string; service: string; onClose: () => void }) {
  const logs = useLoad(() => api.logs(app, service), [app, service], LOGS_POLL_MS);
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-4xl">
        <DialogHeader>
          <DialogTitle>{serviceLabel(service)} logs</DialogTitle>
          <DialogDescription>Last 300 lines, refreshed every few seconds.</DialogDescription>
        </DialogHeader>
        <ErrorAlert message={logs.error} />
        <CodeBlock className="max-h-[60vh]" wrap={false}>{logs.data?.logs || "(no output)"}</CodeBlock>
        <div className="flex justify-end">
          <Button variant="outline" size="sm" onClick={logs.reload}>
            <RefreshCwIcon />
            Refresh
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
