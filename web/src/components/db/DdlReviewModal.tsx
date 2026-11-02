import { useEffect, useState } from "react";
import { CheckCircle2Icon, FileCodeIcon, PlayIcon, TriangleAlertIcon } from "lucide-react";
import { toast } from "sonner";
import { api } from "@/api";
import { CodeBlock, ErrorAlert } from "@/components/common";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Field, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { useAction } from "@/hooks";
import type { DdlRequest, DdlResult, SqlService } from "@/types";

/** A schema change waiting for review: what to send, and how to describe it as a migration. */
export interface PendingDdl {
  title: string;
  request: DdlRequest;
  description: string;
}

const DESTRUCTIVE = new Set(["drop_table", "truncate_table", "drop_column", "drop_index", "drop_schema"]);

/**
 * Every schema change goes through here: the server renders the SQL first, then the user chooses to run it
 * directly or save it as the next Flyway migration. Reviewing the SQL doubles as the confirmation step.
 */
export function DdlReviewModal({
  app,
  service,
  pending,
  onClose,
  onDone,
}: {
  app: string;
  service: SqlService;
  pending: PendingDdl;
  onClose: () => void;
  onDone: () => void;
}) {
  const [preview, setPreview] = useState<string>();
  const [description, setDescription] = useState(pending.description);
  const [result, setResult] = useState<DdlResult>();
  const previewAction = useAction();
  const { busy, error, run } = useAction();
  const db = api.db(app, service);

  useEffect(() => {
    void previewAction.run("preview", async () => setPreview((await db.ddl({ ...pending.request, mode: "preview" })).sql));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [app, service, pending]);

  const apply = (mode: "execute" | "migration") =>
    run(mode, async () => {
      const outcome = await db.ddl({ ...pending.request, mode, description });
      setResult(outcome);
      toast.success(outcome.migration ? `Saved ${outcome.migration}` : pending.title, {
        description: outcome.migration ? "Apply it from the Migrations tab." : "The database was changed directly.",
      });
      onDone();
    });

  const destructive = DESTRUCTIVE.has(pending.request.op);

  return (
    <Dialog open onOpenChange={(open) => !open && !busy && onClose()}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{pending.title}</DialogTitle>
          <DialogDescription>
            Run now changes the database directly. Save as migration writes a Flyway file so the change is versioned with your app.
          </DialogDescription>
        </DialogHeader>
        {destructive && !result && (
          <Alert variant="destructive">
            <TriangleAlertIcon />
            <AlertDescription>This permanently removes data or structure.</AlertDescription>
          </Alert>
        )}
        <ErrorAlert message={previewAction.error} title="Could not generate the SQL" />
        {preview === undefined && !previewAction.error && <Skeleton className="h-16 w-full" />}
        {preview !== undefined && <CodeBlock className="max-h-72">{preview}</CodeBlock>}
        {result ? (
          <Alert>
            <CheckCircle2Icon className="text-emerald-600 dark:text-emerald-400" />
            <AlertDescription>
              {result.migration ? `Saved ${result.migration}. Apply it from the Migrations tab.` : "Done. The database was changed directly."}
            </AlertDescription>
          </Alert>
        ) : (
          <Field>
            <FieldLabel htmlFor="ddl-description">Migration name</FieldLabel>
            <Input id="ddl-description" value={description} onChange={(e) => setDescription(e.target.value)} />
            <FieldDescription>Used for the file name when saving as a migration, e.g. V4__add_email_to_users.sql.</FieldDescription>
          </Field>
        )}
        <ErrorAlert message={error} />
        <DialogFooter>
          {result ? (
            <Button onClick={onClose}>Close</Button>
          ) : (
            <>
              <Button variant="outline" disabled={!!busy} onClick={onClose}>
                Cancel
              </Button>
              <Button variant="outline" disabled={!!busy || preview === undefined || description.trim() === ""} onClick={() => apply("migration")}>
                {busy === "migration" ? <Spinner /> : <FileCodeIcon />}
                Save as migration
              </Button>
              <Button variant={destructive ? "destructive" : "default"} disabled={!!busy || preview === undefined} onClick={() => apply("execute")}>
                {busy === "execute" ? <Spinner /> : <PlayIcon />}
                Run now
              </Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
