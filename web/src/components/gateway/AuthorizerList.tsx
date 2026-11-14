import { ShieldIcon } from "lucide-react";
import { CodeBlock, ErrorAlert } from "@/components/common";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api } from "@/api";
import { useLoad } from "@/hooks";

export function AuthorizerList({ app }: { app: string }) {
  const authorizers = useLoad(() => api.gatewayAuthorizers(app), [app]);
  return (
    <Card>
      <CardHeader>
        <CardTitle>Custom authorizers</CardTitle>
        <CardDescription>Authorizers pushed to this app, each run as its own container behind the gateway.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <ErrorAlert message={authorizers.error} />
        {authorizers.data === undefined && !authorizers.error && <Skeleton className="h-24" />}
        {authorizers.data?.length === 0 && (
          <Empty className="py-8">
            <EmptyHeader>
              <EmptyMedia variant="icon">
                <ShieldIcon />
              </EmptyMedia>
              <EmptyTitle>No custom authorizers</EmptyTitle>
              <EmptyDescription>Scaffold one, then build and start it with push.</EmptyDescription>
            </EmptyHeader>
            <CodeBlock className="w-full max-w-xl text-left" wrap={false}>
              {`gantry authorizer init <name> --lang node|python|go\ngantry authorizer push ${app} <name> <dir>`}
            </CodeBlock>
          </Empty>
        )}
        {authorizers.data && authorizers.data.length > 0 && (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Directory</TableHead>
                <TableHead>Port</TableHead>
                <TableHead>Image</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {authorizers.data.map((a) => (
                <TableRow key={a.name}>
                  <TableCell className="font-mono text-xs font-medium">{a.name}</TableCell>
                  <TableCell className="font-mono text-xs break-all whitespace-normal">{a.dir}</TableCell>
                  <TableCell className="font-mono text-xs">{a.port}</TableCell>
                  <TableCell className="font-mono text-xs break-all whitespace-normal">{a.image}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  );
}
