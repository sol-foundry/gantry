import { useEffect, useRef, useState } from "react";
import { CheckCircle2Icon } from "lucide-react";
import { api } from "@/api";
import { CodeBlock, ErrorAlert } from "@/components/common";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { DialogFooter } from "@/components/ui/dialog";
import { Spinner } from "@/components/ui/spinner";
import type { SqlService, SyncJob } from "@/types";

const POLL_MS = 1000;

function seconds(job: SyncJob) {
  const elapsed = Math.max(0, Date.now() - Date.parse(job.startedAt));
  return Number.isFinite(elapsed) ? `${Math.max(1, Math.round(elapsed / 1000))}s` : undefined;
}

/** Follows a running sync: phase, live log, then the result or the reason it failed. */
export function SyncProgress({
  app,
  service,
  initial,
  onRetry,
  onClose,
  onSucceeded,
}: {
  app: string;
  service: SqlService;
  initial: SyncJob;
  onRetry: () => void;
  onClose: () => void;
  onSucceeded: () => void;
}) {
  const [job, setJob] = useState(initial);
  const [duration, setDuration] = useState<string>();
  const [pollError, setPollError] = useState<string>();
  const finished = useRef(false);
  const running = job.state === "running";

  useEffect(() => {
    if (!running) return;
    let cancelled = false;
    const id = setInterval(() => {
      api
        .db(app, service)
        .syncJob()
        .then((next) => {
          if (cancelled) return;
          setPollError(undefined);
          setJob(next);
          if (next.state !== "running" && !finished.current) {
            finished.current = true;
            setDuration(seconds(next));
            if (next.state === "succeeded") onSucceeded();
          }
        })
        .catch((e: Error) => !cancelled && setPollError(e.message));
    }, POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
    // onSucceeded is a fresh closure each render; the poll only needs the latest job state.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [app, service, running]);

  const log = job.log.join("\n");
  // Keep the newest line in view while the log grows.
  const end = useRef<HTMLDivElement>(null);
  useEffect(() => {
    end.current?.scrollIntoView({ block: "nearest" });
  }, [job.log.length]);

  return (
    <>
      {running ? (
        <p className="flex items-center gap-2 text-sm">
          <Spinner />
          <span className="capitalize">{job.phase}…</span>
        </p>
      ) : job.state === "succeeded" ? (
        <Alert>
          <CheckCircle2Icon />
          <AlertTitle>Sync finished{duration ? ` in ${duration}` : ""}</AlertTitle>
          <AlertDescription>
            {[
              typeof job.tables === "number" ? `${job.tables} ${job.tables === 1 ? "table" : "tables"}` : undefined,
              job.flywayVersion ? `Flyway version ${job.flywayVersion}` : undefined,
            ]
              .filter(Boolean)
              .join(" · ") || "The local database is a copy of the source."}
          </AlertDescription>
        </Alert>
      ) : (
        <ErrorAlert title="Sync failed" message={job.message ?? "The sync did not finish."} />
      )}
      <ErrorAlert message={pollError} title="Lost contact with the sync" />
      {log && (
        <CodeBlock className="max-h-[50vh]">
          {log}
          <div ref={end} />
        </CodeBlock>
      )}
      <DialogFooter>
        {job.state === "failed" && (
          <Button variant="outline" onClick={onRetry}>
            Retry
          </Button>
        )}
        <Button variant={running ? "outline" : "default"} onClick={onClose}>
          {running ? "Hide" : "Close"}
        </Button>
      </DialogFooter>
    </>
  );
}
