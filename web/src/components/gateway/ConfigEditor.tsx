import { useCallback, useEffect, useMemo, useState, type ReactNode, type SetStateAction } from "react";
import { CheckIcon, FileCodeIcon, ListIcon, SaveIcon } from "lucide-react";
import { api, ApiError } from "@/api";
import { ErrorAlert } from "@/components/common";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import { useLoad } from "@/hooks";
import { ConfigForm } from "./ConfigForm";
import { canonical, fromConfig, toConfig, type FormState } from "./gatewayForm";

type Mode = "form" | "yaml";

/**
 * The Config tab: gateway.yml as a form, or as text behind "Edit as YAML". Switching reloads from the server, so
 * each view starts from the saved file; unsaved edits are confirmed away first.
 */
export function ConfigEditor({ app }: { app: string }) {
  const [mode, setMode] = useState<Mode>("form");
  const [dirty, setDirty] = useState(false);
  const [asking, setAsking] = useState<Mode>();
  const other: Mode = mode === "form" ? "yaml" : "form";

  const switchTo = (next: Mode) => {
    setDirty(false);
    setAsking(undefined);
    setMode(next);
  };
  const toggle = (
    <Button variant="outline" size="sm" onClick={() => (dirty ? setAsking(other) : switchTo(other))}>
      {mode === "form" ? <FileCodeIcon /> : <ListIcon />}
      {mode === "form" ? "Edit as YAML" : "Edit as form"}
    </Button>
  );

  return (
    <>
      {mode === "form" ? (
        <FormEditor key="form" app={app} toggle={toggle} onDirty={setDirty} />
      ) : (
        <YamlEditor key="yaml" app={app} toggle={toggle} onDirty={setDirty} />
      )}
      <AlertDialog open={asking !== undefined} onOpenChange={(open) => !open && setAsking(undefined)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Discard unsaved changes?</AlertDialogTitle>
            <AlertDialogDescription>
              {asking === "yaml" ? "The YAML editor" : "The form"} opens with gateway.yml as it is saved now. Your unsaved edits here will be lost.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Keep editing</AlertDialogCancel>
            <AlertDialogAction variant="destructive" onClick={() => asking && switchTo(asking)}>
              Discard and switch
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}

interface EditorProps {
  app: string;
  /** The "Edit as YAML" / "Edit as form" button. */
  toggle: ReactNode;
  onDirty: (dirty: boolean) => void;
}

function Saved() {
  return (
    <span className="flex items-center gap-1 text-xs text-emerald-600 dark:text-emerald-400">
      <CheckIcon className="size-3.5" />
      Saved — applies without restart
    </span>
  );
}

/** The problems a save found; a long list scrolls, so it never fills the screen from the sticky bar. */
function Problems({ problems, onDismiss }: { problems: string[]; onDismiss: () => void }) {
  if (problems.length === 0) return null;
  return (
    <ErrorAlert
      title="gateway.yml has problems"
      message={problems.map((p) => `• ${p}`).join("\n")}
      onDismiss={onDismiss}
      className="max-h-40 overflow-y-auto"
    />
  );
}

/** Asks the browser to confirm closing or reloading the page while there are unsaved edits. */
function useLeaveWarning(dirty: boolean) {
  useEffect(() => {
    if (!dirty) return;
    const warn = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      // Older browsers only ask when this is set.
      e.returnValue = "";
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);
}

function FormEditor({ app, toggle, onDirty }: EditorProps) {
  const loaded = useLoad(() => api.gatewayConfigForm(app), [app]);
  const [form, setForm] = useState<FormState>();
  /** The last saved config, as JSON, to tell whether the form has changed. */
  const [baseline, setBaseline] = useState<string>();
  const [comments, setComments] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [problems, setProblems] = useState<string[]>([]);
  const [confirmed, setConfirmed] = useState(false);

  // The loaded file seeds the form once; a later reload must not overwrite edits.
  useEffect(() => {
    if (loaded.data && form === undefined) {
      const state = fromConfig(loaded.data.config);
      setForm(state);
      // The baseline is the server's config itself, so anything the form would change on its own shows as unsaved.
      setBaseline(canonical(loaded.data.config));
      setComments(loaded.data.comments);
    }
  }, [loaded.data, form]);

  // The form only edits once it is loaded, so its setter never sees undefined.
  const update = useCallback(
    (change: SetStateAction<FormState>) => setForm((prev) => (prev === undefined ? prev : typeof change === "function" ? change(prev) : change)),
    [],
  );
  const converted = useMemo(() => (form ? toConfig(form) : undefined), [form]);
  const current = useMemo(() => (converted ? canonical(converted.config) : undefined), [converted]);
  const dirty = current !== undefined && current !== baseline;
  useEffect(() => onDirty(dirty), [dirty, onDirty]);
  useLeaveWarning(dirty);
  useEffect(() => {
    if (dirty) setConfirmed(false);
  }, [dirty, current]);

  const save = async () => {
    if (!converted || current === undefined) return;
    setError(undefined);
    setConfirmed(false);
    if (converted.problems.length > 0) {
      setProblems(converted.problems);
      return;
    }
    setProblems([]);
    setBusy(true);
    try {
      await api.saveGatewayConfigForm(app, converted.config);
      setBaseline(current);
      setComments(false);
      setConfirmed(true);
    } catch (e) {
      if (e instanceof ApiError && Array.isArray(e.body.problems)) setProblems(e.body.problems as string[]);
      else setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex flex-col gap-4">
      <Card size="sm" className="sticky top-0 z-10">
        <CardHeader>
          <CardTitle>gateway.yml</CardTitle>
          <CardDescription>Routes, logins and headers for this gateway. Saved changes apply without a restart.</CardDescription>
          <CardAction className="flex flex-wrap items-center justify-end gap-3">
            {confirmed && !dirty && <Saved />}
            {comments && <span className="text-xs text-muted-foreground">Saving from the form rewrites gateway.yml and removes its comments.</span>}
            {toggle}
            <Button size="sm" disabled={!dirty || busy} onClick={save}>
              {busy ? <Spinner /> : <SaveIcon />}
              Save
            </Button>
          </CardAction>
        </CardHeader>
        {(loaded.error || error || problems.length > 0) && (
          <CardContent className="flex flex-col gap-3">
            {loaded.error && (
              <ErrorAlert title="gateway.yml can't be shown as a form" message={`${loaded.error}\nFix it with Edit as YAML.`} />
            )}
            <ErrorAlert message={error} />
            <Problems problems={problems} onDismiss={() => setProblems([])} />
          </CardContent>
        )}
      </Card>
      {form === undefined ? !loaded.error && <Skeleton className="h-96" /> : <ConfigForm app={app} form={form} setForm={update} />}
    </div>
  );
}

function YamlEditor({ app, toggle, onDirty }: EditorProps) {
  const config = useLoad(() => api.gatewayConfig(app), [app]);
  const [text, setText] = useState<string>();
  const [saved, setSaved] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [problems, setProblems] = useState<string[]>([]);
  const [confirmed, setConfirmed] = useState(false);

  // The loaded file seeds the editor once; later reloads must not overwrite edits.
  useEffect(() => {
    if (config.data && saved === undefined) {
      setText(config.data.text);
      setSaved(config.data.text);
    }
  }, [config.data, saved]);

  const dirty = text !== undefined && text !== saved;
  useEffect(() => onDirty(dirty), [dirty, onDirty]);
  useLeaveWarning(dirty);

  const save = async () => {
    if (text === undefined) return;
    setBusy(true);
    setError(undefined);
    setProblems([]);
    setConfirmed(false);
    try {
      await api.saveGatewayConfig(app, text);
      setSaved(text);
      setConfirmed(true);
    } catch (e) {
      if (e instanceof ApiError && Array.isArray(e.body.problems)) setProblems(e.body.problems as string[]);
      else setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>gateway.yml</CardTitle>
        <CardDescription>Routes, auth and logging for this gateway. Saved changes apply without a restart.</CardDescription>
        <CardAction className="flex items-center gap-3">
          {confirmed && !dirty && <Saved />}
          {toggle}
          <Button size="sm" disabled={!dirty || busy} onClick={save}>
            {busy ? <Spinner /> : <SaveIcon />}
            Save
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <ErrorAlert message={config.error ?? error} />
        <Problems problems={problems} onDismiss={() => setProblems([])} />
        {text === undefined && !config.error ? (
          <Skeleton className="h-96" />
        ) : (
          <Textarea
            value={text ?? ""}
            onChange={(e) => {
              setText(e.target.value);
              setConfirmed(false);
            }}
            spellCheck={false}
            aria-label="gateway.yml"
            className="min-h-96 font-mono text-xs leading-relaxed"
          />
        )}
      </CardContent>
    </Card>
  );
}
