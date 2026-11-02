import { useState } from "react";
import { PlayIcon } from "lucide-react";
import { api } from "@/api";
import { ErrorAlert } from "@/components/common";
import { DataGrid } from "@/components/DataGrid";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Kbd, KbdGroup } from "@/components/ui/kbd";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import { useAction } from "@/hooks";
import type { SqlService, TableResult } from "@/types";

const STARTER: Record<SqlService, string> = {
  postgres: "SELECT now();",
  mysql: "SELECT NOW();",
};

export function SqlConsole({ app, service, onRan }: { app: string; service: SqlService; onRan?: () => void }) {
  const [sql, setSql] = useState(STARTER[service]);
  const [result, setResult] = useState<TableResult>();
  const { busy, error, run, clearError } = useAction();

  const execute = async () => {
    if (await run("sql", async () => setResult(await api.db(app, service).sql(sql)))) onRan?.();
  };

  return (
    <Card className="">
      <CardHeader>
        <CardTitle>SQL console</CardTitle>
        <CardDescription>Runs one statement as the app&apos;s database user. Results are capped at 500 rows.</CardDescription>
      </CardHeader>
      {/* Editor and buttons stay fixed; the result grid takes the rest of the card and scrolls. */}
      <CardContent className="flex flex-col gap-3">
        <Textarea
          className="min-h-36 shrink-0 font-mono text-xs md:text-xs"
          value={sql}
          spellCheck={false}
          aria-label="SQL"
          onChange={(e) => setSql(e.target.value)}
          onKeyDown={(e) => {
            if ((e.metaKey || e.ctrlKey) && e.key === "Enter") {
              e.preventDefault();
              void execute();
            }
          }}
        />
        <div className="flex items-center gap-3">
          <Button disabled={!!busy || sql.trim() === ""} onClick={execute}>
            {busy ? <Spinner /> : <PlayIcon />}
            Run
          </Button>
          <KbdGroup className="text-xs text-muted-foreground">
            <Kbd>⌘</Kbd>
            <Kbd>↵</Kbd>
            <span>or Ctrl+Enter</span>
          </KbdGroup>
        </div>
        <ErrorAlert message={error} onDismiss={clearError} />
        {result && <DataGrid result={result} />}
      </CardContent>
    </Card>
  );
}
