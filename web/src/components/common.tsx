import { useState, type ReactNode } from "react";
import { AlertCircleIcon, CheckIcon, CopyIcon, XIcon } from "lucide-react";
import { toast } from "sonner";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
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
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { cn } from "@/lib/utils";

/** A failed request, shown in place; dismissible when the error belongs to a user action. */
export function ErrorAlert({ message, title, onDismiss, className }: { message?: string; title?: string; onDismiss?: () => void; className?: string }) {
  if (!message) return null;
  return (
    <Alert variant="destructive" className={cn("relative", className)}>
      <AlertCircleIcon />
      {title && <AlertTitle>{title}</AlertTitle>}
      <AlertDescription className="whitespace-pre-wrap break-words">{message}</AlertDescription>
      {onDismiss && (
        <Button variant="ghost" size="icon-xs" className="absolute top-2 right-2" onClick={onDismiss} aria-label="Dismiss">
          <XIcon />
        </Button>
      )}
    </Alert>
  );
}

/** Container state from `docker compose ps`, e.g. running · healthy, exited, not created. */
export function StatusBadge({ state, health }: { state: string; health?: string }) {
  const healthy = state === "running" && health !== "unhealthy" && health !== "starting";
  const tone = healthy ? "bg-emerald-500" : state === "not created" ? "bg-muted-foreground/40" : "bg-amber-500";
  return (
    <Badge variant="outline" className="gap-1.5 font-normal">
      <span className={cn("size-1.5 rounded-full", tone)} />
      {health && state === "running" ? `${state} · ${health}` : state}
    </Badge>
  );
}

export function PageHeader({ title, description, actions, icon }: { title: ReactNode; description?: ReactNode; actions?: ReactNode; icon?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-4">
      <div className="flex min-w-0 items-center gap-3">
        {icon && <div className="flex size-10 shrink-0 items-center justify-center rounded-lg border bg-muted/40">{icon}</div>}
        <div className="min-w-0">
          <h1 className="truncate font-heading text-2xl font-semibold tracking-tight">{title}</h1>
          {description && <div className="text-sm text-muted-foreground">{description}</div>}
        </div>
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

/**
 * Monospace block for SQL, env files, logs and console output. A height cap (e.g. max-h-[60vh] in a dialog) goes
 * in className; it then scrolls with the app's overlay scrollbar. With wrap={false} long lines scroll sideways.
 */
export function CodeBlock({ children, className, wrap = true }: { children: ReactNode; className?: string; wrap?: boolean }) {
  return (
    <ScrollArea orientation="both" className={cn("rounded-lg border bg-muted/50", className)}>
      <pre className={cn("p-3 font-mono text-xs leading-relaxed", wrap ? "whitespace-pre-wrap break-all" : "whitespace-pre")}>{children}</pre>
    </ScrollArea>
  );
}

export function CopyButton({ text, label = "Copy", className }: { text: string; label?: string; className?: string }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      toast.error("Clipboard is not available");
    }
  };
  return (
    <Button variant="outline" size="sm" className={className} onClick={copy}>
      {copied ? <CheckIcon /> : <CopyIcon />}
      {copied ? "Copied" : label}
    </Button>
  );
}

/**
 * A button that asks before doing something hard to undo. [onConfirm] may be async; the dialog stays open
 * until it settles so a failure can be reported by the caller (usually with a toast).
 */
export function ConfirmButton({
  children,
  title,
  description,
  confirmLabel = "Continue",
  destructive = false,
  onConfirm,
  variant,
  size,
  disabled,
  className,
}: {
  children: ReactNode;
  title: string;
  description: ReactNode;
  confirmLabel?: string;
  destructive?: boolean;
  onConfirm: () => void | Promise<unknown>;
  variant?: React.ComponentProps<typeof Button>["variant"];
  size?: React.ComponentProps<typeof Button>["size"];
  disabled?: boolean;
  className?: string;
}) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const confirm = async () => {
    setBusy(true);
    try {
      await onConfirm();
      setOpen(false);
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      <Button variant={variant ?? (destructive ? "destructive" : "outline")} size={size} disabled={disabled} className={className} onClick={() => setOpen(true)}>
        {children}
      </Button>
      <AlertDialog open={open} onOpenChange={(next) => !busy && setOpen(next)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{title}</AlertDialogTitle>
            <AlertDialogDescription>{description}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={busy}>Cancel</AlertDialogCancel>
            <AlertDialogAction variant={destructive ? "destructive" : "default"} disabled={busy} onClick={confirm}>
              {busy ? "Working…" : confirmLabel}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}
