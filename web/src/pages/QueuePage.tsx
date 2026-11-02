import { useEffect, useState } from "react";
import { EyeIcon, InboxIcon, InfoIcon, RefreshCwIcon, SendIcon, SkullIcon, Trash2Icon, Undo2Icon } from "lucide-react";
import { toast } from "sonner";
import { api } from "@/api";
import { CodeBlock, ConfirmButton, ErrorAlert, PageHeader } from "@/components/common";
import { prettyJson } from "@/lib/format";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { useAction, useLoad } from "@/hooks";
import { HeaderSlot } from "@/lib/header";
import { ServiceIcon } from "@/lib/services";
import { cn } from "@/lib/utils";
import type { QueueMessage, QueueSummary } from "@/types";

const QUEUE_POLL_MS = 3000;

export function QueuePage({ app }: { app: string }) {
  const queues = useLoad(() => api.queues(app), [app], QUEUE_POLL_MS);
  const [selected, setSelected] = useState<string>();
  // Dead-letter queues are reached from their source queue's detail, not listed on their own.
  const byName = new Map((queues.data ?? []).map((q) => [q.name, q]));
  const deadLetterQueues = new Set((queues.data ?? []).flatMap((q) => parseRedrive(q)?.deadLetterQueue ?? []));
  const listed = queues.data?.filter((q) => !deadLetterQueues.has(q.name));
  const current = byName.get(selected ?? "");
  const deadLetterOf = (q: QueueSummary | undefined) => byName.get(parseRedrive(q)?.deadLetterQueue ?? "");

  return (
    <>
      <HeaderSlot slot="crumb">
        <ServiceIcon id="elasticmq" className="size-4 shrink-0" />
        <span>Queues</span>
        <span className="text-muted-foreground">(ElasticMQ)</span>
      </HeaderSlot>
      <PageHeader
        icon={<ServiceIcon id="elasticmq" className="size-5" />}
        title="Queues"
        description="SQS-compatible queues (ElasticMQ) declared by the app's queues option."
      />
      <div className="grid items-start gap-6 lg:grid-cols-[18rem_minmax(0,1fr)]">
        <Card size="sm">
          <CardHeader>
            <CardTitle>Queues</CardTitle>
            <CardDescription>Visible · in flight, refreshed every few seconds.</CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-2">
            <ErrorAlert message={queues.error} />
            {queues.data === undefined && !queues.error && [0, 1, 2].map((i) => <Skeleton key={i} className="h-10" />)}
            {listed?.length === 0 && (
              <p className="py-4 text-center text-sm text-muted-foreground">
                No queues declared. Add some with the elasticmq queues option.
              </p>
            )}
            <ul className="flex flex-col gap-0.5">
              {listed?.map((q) => (
                <li key={q.name}>
                  <QueueListItem queue={q} deadLetter={deadLetterOf(q)} active={selected === q.name} onSelect={() => setSelected(q.name)} />
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
        {selected ? (
          <QueueDetail
            key={selected}
            app={app}
            queue={selected}
            redrive={parseRedrive(current)}
            deadLetter={deadLetterOf(current)}
            onChanged={queues.reload}
          />
        ) : (
          <Card>
            <CardContent className="flex flex-1 items-center justify-center">
              <Empty className="py-8">
                <EmptyHeader>
                  <EmptyMedia variant="icon">
                    <InboxIcon />
                  </EmptyMedia>
                  <EmptyTitle>No queue selected</EmptyTitle>
                  <EmptyDescription>Pick a queue to peek at its messages, send one, or purge it.</EmptyDescription>
                </EmptyHeader>
              </Empty>
            </CardContent>
          </Card>
        )}
      </div>
    </>
  );
}

function QueueListItem({
  queue,
  deadLetter,
  active,
  onSelect,
}: {
  queue: QueueSummary;
  deadLetter?: QueueSummary;
  active: boolean;
  onSelect: () => void;
}) {
  const visible = Number(queue.visible);
  const deadLettered = Number(deadLetter?.visible ?? 0);
  return (
    <button
      type="button"
      onClick={onSelect}
      className={cn(
        "flex w-full flex-col gap-1 rounded-md px-2 py-1.5 text-left transition-colors hover:bg-muted",
        active && "bg-muted ring-1 ring-border",
      )}
    >
      <span className="flex items-center gap-2">
        <span className="truncate font-mono text-xs">{queue.name}</span>
      </span>
      <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
        <Badge variant={visible > 0 ? "secondary" : "outline"} className="h-4.5 px-1.5 text-[0.7rem] tabular-nums">
          {queue.visible}
        </Badge>
        visible
        <Badge variant="outline" className="h-4.5 px-1.5 text-[0.7rem] tabular-nums">
          {queue.inFlight}
        </Badge>
        in flight
        {deadLettered > 0 && (
          <>
            <Badge variant="destructive" className="h-4.5 px-1.5 text-[0.7rem] tabular-nums">
              {deadLetter?.visible}
            </Badge>
            dead
          </>
        )}
      </span>
    </button>
  );
}

interface Redrive {
  deadLetterQueue: string;
  maxReceiveCount: number;
}

function parseRedrive(queue: QueueSummary | undefined): Redrive | undefined {
  if (!queue?.redrive) return undefined;
  try {
    const policy = JSON.parse(queue.redrive) as {
      deadLetterTargetArn?: string;
      maxReceiveCount?: number | string;
    };
    return {
      deadLetterQueue: policy.deadLetterTargetArn?.split(":").pop() ?? "its dead-letter queue",
      maxReceiveCount: Number(policy.maxReceiveCount),
    };
  } catch {
    return undefined;
  }
}

/**
 * SQS has no true peek: every read counts as a receive. Queues without a redrive policy are peeked
 * automatically; on a queue with one, each peek spends part of the message's receive budget, so it
 * only happens when asked.
 */
function QueueDetail({
  app,
  queue,
  redrive,
  deadLetter,
  onChanged,
}: {
  app: string;
  queue: string;
  redrive?: Redrive;
  deadLetter?: QueueSummary;
  onChanged: () => void;
}) {
  const [messages, setMessages] = useState<QueueMessage[]>();
  const [deadLettersOpen, setDeadLettersOpen] = useState(false);
  const peekAction = useAction();
  const deadLettered = Number(deadLetter?.visible ?? 0);

  const peek = () => peekAction.run("peek", async () => setMessages(await api.peek(app, queue)));
  const autoPeek = !redrive;
  useEffect(() => {
    if (autoPeek) void peek();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [app, queue, autoPeek]);

  const refresh = () => {
    if (autoPeek) void peek();
    else setMessages(undefined);
    onChanged();
  };

  const purge = async () => {
    try {
      await api.purge(app, queue);
      toast.success(`Purged ${queue}`);
      refresh();
    } catch (e) {
      toast.error((e as Error).message);
    }
  };

  return (
    <div className="flex min-w-0 flex-col gap-6">
      <Card>
        <CardHeader>
          <CardTitle className="font-mono text-sm">{queue}</CardTitle>
          <CardDescription>
            {redrive ? `Dead-letters to ${redrive.deadLetterQueue} after ${redrive.maxReceiveCount} receives.` : "No dead-letter policy."}
          </CardDescription>
          <CardAction className="flex gap-1">
            {redrive ? (
              <Button variant="outline" size="sm" disabled={!!peekAction.busy} onClick={peek}>
                {peekAction.busy ? <Spinner /> : <EyeIcon />}
                Peek messages
              </Button>
            ) : (
              <Button variant="outline" size="sm" disabled={!!peekAction.busy} onClick={refresh}>
                {peekAction.busy ? <Spinner /> : <RefreshCwIcon />}
                Refresh
              </Button>
            )}
            {redrive && (
              <Button variant="outline" size="sm" onClick={() => setDeadLettersOpen(true)}>
                <SkullIcon />
                Dead letters
                {deadLettered > 0 && (
                  <Badge variant="destructive" className="h-4.5 px-1.5 text-[0.7rem] tabular-nums">
                    {deadLetter?.visible}
                  </Badge>
                )}
              </Button>
            )}
            {redrive && (
              <RedriveButton app={app} source={queue} deadLetterQueue={redrive.deadLetterQueue} disabled={deadLettered === 0} onDone={refresh} />
            )}
            <ConfirmButton
              destructive
              size="sm"
              title={`Purge ${queue}?`}
              description="Every message in the queue is deleted."
              confirmLabel="Purge"
              onConfirm={purge}
            >
              <Trash2Icon />
              Purge
            </ConfirmButton>
          </CardAction>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          {redrive ? (
            <Alert>
              <InfoIcon />
              <AlertDescription>
                Peeking shows up to 10 messages but counts as a receive: after {redrive.maxReceiveCount} receives (by consumers or peeks) a
                message moves to <span className="font-mono">{redrive.deadLetterQueue}</span>.
              </AlertDescription>
            </Alert>
          ) : (
            <p className="text-xs text-muted-foreground">Peeking shows up to 10 messages without hiding them from consumers.</p>
          )}
          <ErrorAlert message={peekAction.error} />
          <MessageList messages={messages} redrive={redrive} />
        </CardContent>
      </Card>
      <SendMessage app={app} queue={queue} onSent={refresh} />
      {deadLettersOpen && redrive && (
        <DeadLetterDialog
          app={app}
          source={queue}
          redrive={redrive}
          onChanged={onChanged}
          onClose={() => setDeadLettersOpen(false)}
        />
      )}
    </div>
  );
}

/** A dead-letter queue has no redrive policy of its own, so it is peeked on open without spending a receive budget. */
function DeadLetterDialog({
  app,
  source,
  redrive,
  onChanged,
  onClose,
}: {
  app: string;
  source: string;
  redrive: Redrive;
  onChanged: () => void;
  onClose: () => void;
}) {
  const queue = redrive.deadLetterQueue;
  const [messages, setMessages] = useState<QueueMessage[]>();
  const peekAction = useAction();
  const peek = () => peekAction.run("peek", async () => setMessages(await api.peek(app, queue)));
  useEffect(() => {
    void peek();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [app, queue]);

  const purge = async () => {
    try {
      await api.purge(app, queue);
      toast.success(`Purged ${queue}`);
      void peek();
      onChanged();
    } catch (e) {
      toast.error((e as Error).message);
    }
  };

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-4xl">
        <DialogHeader>
          <DialogTitle className="font-mono text-sm">{queue}</DialogTitle>
          <DialogDescription>
            Messages from <span className="font-mono">{source}</span> that were received {redrive.maxReceiveCount} times without being
            deleted. Peeking here shows up to 10 without hiding them.
          </DialogDescription>
        </DialogHeader>
        <ErrorAlert message={peekAction.error} />
        <div className="flex max-h-[60vh] flex-col gap-3 overflow-y-auto">
          {messages === undefined && !peekAction.error && [0, 1].map((i) => <Skeleton key={i} className="h-16" />)}
          <MessageList messages={messages} emptyText="No dead letters." />
        </div>
        <div className="flex justify-end gap-1">
          <Button variant="outline" size="sm" disabled={!!peekAction.busy} onClick={peek}>
            {peekAction.busy ? <Spinner /> : <RefreshCwIcon />}
            Refresh
          </Button>
          <RedriveButton
            app={app}
            source={source}
            deadLetterQueue={queue}
            disabled={messages?.length === 0}
            onDone={() => {
              void peek();
              onChanged();
            }}
          />
          <ConfirmButton
            destructive
            size="sm"
            title={`Purge ${queue}?`}
            description="Every dead-lettered message is deleted."
            confirmLabel="Purge"
            onConfirm={purge}
          >
            <Trash2Icon />
            Purge
          </ConfirmButton>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function RedriveButton({
  app,
  source,
  deadLetterQueue,
  disabled,
  onDone,
}: {
  app: string;
  source: string;
  deadLetterQueue: string;
  disabled?: boolean;
  onDone: () => void;
}) {
  const redrive = async () => {
    try {
      const { moved } = await api.redrive(app, source);
      toast.success(`Moved ${moved} ${moved === 1 ? "message" : "messages"} back to ${source}`);
      onDone();
    } catch (e) {
      toast.error((e as Error).message);
    }
  };
  return (
    <ConfirmButton
      size="sm"
      disabled={disabled}
      title={`Redrive ${deadLetterQueue}?`}
      description={`Every dead-lettered message is moved back to ${source}, where consumers receive it again with a fresh receive count.`}
      confirmLabel="Redrive"
      onConfirm={redrive}
    >
      <Undo2Icon />
      Redrive
    </ConfirmButton>
  );
}

function MessageList({
  messages,
  redrive,
  emptyText = "Queue is empty.",
}: {
  messages?: QueueMessage[];
  redrive?: Redrive;
  emptyText?: string;
}) {
  const [raw, setRaw] = useState(false);
  if (!messages) return null;
  if (messages.length === 0) return <p className="py-4 text-center text-sm text-muted-foreground">{emptyText}</p>;
  return (
    <>
      <label className="flex items-center gap-2 self-end text-xs text-muted-foreground">
        <Switch size="sm" checked={raw} onCheckedChange={setRaw} />
        Raw bodies
      </label>
      <div className="flex flex-col gap-3">
        {messages.map((m) => (
          <div key={m.id} className="flex flex-col gap-1">
            <CodeBlock>{raw ? m.body : (prettyJson(m.body) ?? m.body)}</CodeBlock>
            <div className="flex flex-wrap gap-3 text-xs text-muted-foreground">
              <span className="font-mono" title="Message id">
                {m.id}
              </span>
              {redrive && (
                <span className={cn(m.receiveCount >= redrive.maxReceiveCount && "text-destructive")}>
                  received {m.receiveCount} of {redrive.maxReceiveCount}
                </span>
              )}
            </div>
          </div>
        ))}
      </div>
    </>
  );
}

function SendMessage({ app, queue, onSent }: { app: string; queue: string; onSent: () => void }) {
  const [body, setBody] = useState("");
  const [busy, setBusy] = useState(false);
  const send = async () => {
    setBusy(true);
    try {
      await api.send(app, queue, body);
      toast.success(`Sent to ${queue}`);
      setBody("");
      onSent();
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Card size="sm">
      <CardHeader>
        <CardTitle>Send message</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-2">
        <Textarea
          className="font-mono text-xs"
          value={body}
          rows={4}
          spellCheck={false}
          aria-label="Message body"
          placeholder='{"hello": "world"}'
          onChange={(e) => setBody(e.target.value)}
          onKeyDown={(e) => (e.metaKey || e.ctrlKey) && e.key === "Enter" && body !== "" && void send()}
        />
        <div>
          <Button size="sm" disabled={busy || body === ""} onClick={send}>
            {busy ? <Spinner /> : <SendIcon />}
            Send
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
