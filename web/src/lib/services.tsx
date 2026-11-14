import { BoxIcon } from "lucide-react";
import { LOGOS } from "@/lib/logos";
import type { Tool } from "@/lib/router";
import { cn } from "@/lib/utils";

/** Presentation for catalog services; unknown ids (new catalog entries) fall back to a generic box. */
const SERVICES: Record<string, { label: string; tool?: Tool; uiName?: string }> = {
  postgres: { label: "PostgreSQL", tool: "postgres" },
  mysql: { label: "MySQL", tool: "mysql" },
  valkey: { label: "Valkey", uiName: "RedisInsight" },
  elasticmq: { label: "ElasticMQ", tool: "queue" },
  temporal: { label: "Temporal", uiName: "Temporal Web UI" },
  gateway: { label: "API Gateway", tool: "gateway" },
  kafka: { label: "Kafka", uiName: "Kafka UI" },
  dynamodb: { label: "DynamoDB", uiName: "DynamoDB Admin" },
};

export function serviceLabel(id: string): string {
  return SERVICES[id]?.label ?? id;
}

/** The service's brand logo, or a generic box for services without one. */
export function ServiceIcon({ id, className }: { id: string; className?: string }) {
  const logo = LOGOS[id];
  if (!logo) return <BoxIcon className={className} />;
  return (
    <svg
      viewBox={logo.viewBox}
      role="img"
      aria-label={serviceLabel(id)}
      className={cn("shrink-0", logo.className, className)}
      // The markup is a constant from lib/logos.ts, never user input.
      dangerouslySetInnerHTML={{ __html: logo.body }}
    />
  );
}

/** What a service's own web UI is called (RedisInsight for valkey), for services that link out. */
export function serviceUiName(id: string): string | undefined {
  return SERVICES[id]?.uiName;
}

/** The built-in explorer page of a service, if it has one. */
export function serviceTool(id: string): Tool | undefined {
  return SERVICES[id]?.tool;
}

export const TOOL_LABELS: Record<Tool, string> = { postgres: "PostgreSQL", mysql: "MySQL", queue: "Queues", gateway: "API Gateway" };
