import { useEffect, useState } from "react";

/** Explorer pages, keyed by the route segment the catalog's `ui` builtin names. */
export type Tool = "postgres" | "mysql" | "queue" | "gateway";

export type Route =
  | { page: "home" }
  | { page: "new-app" }
  | { page: "app"; app: string }
  /** [table] is the database table open in a SQL explorer: `[schema, name]`, or `[name]` where there are no schemas. */
  | { page: "tool"; app: string; tool: Tool; table?: string[] }
  | { page: "services" }
  | { page: "service"; service: string };

const TOOLS: Tool[] = ["postgres", "mysql", "queue", "gateway"];

/** Hash routes keep the dashboard a single static page the Kotlin server can serve for any path. */
export function parseRoute(hash: string): Route {
  const [section, name, tool, ...table] = hash.replace(/^#\/?/, "").split("/").filter(Boolean).map(decodeURIComponent);
  if (section === "new") return { page: "new-app" };
  if (section === "services") return name ? { page: "service", service: name } : { page: "services" };
  if (section === "apps" && name) {
    if (!tool || !TOOLS.includes(tool as Tool)) return { page: "app", app: name };
    return { page: "tool", app: name, tool: tool as Tool, ...(table.length > 0 && { table: table.slice(0, 2) }) };
  }
  return { page: "home" };
}

export const href = {
  home: () => "#/",
  newApp: () => "#/new",
  app: (app: string) => `#/apps/${encodeURIComponent(app)}`,
  tool: (app: string, tool: Tool) => `#/apps/${encodeURIComponent(app)}/${tool}`,
  table: (app: string, tool: Tool, table: string[]) => `${href.tool(app, tool)}/${table.map(encodeURIComponent).join("/")}`,
  services: () => "#/services",
  service: (id: string) => `#/services/${encodeURIComponent(id)}`,
};

/** Goes to [to]; with [replace] the current history entry is swapped, so Back skips a page that no longer exists. */
export function navigate(to: string, { replace = false } = {}) {
  if (!replace) {
    window.location.hash = to.replace(/^#/, "");
    return;
  }
  history.replaceState(history.state, "", to);
  window.dispatchEvent(new HashChangeEvent("hashchange"));
}

export function useRoute(): Route {
  const [route, setRoute] = useState(() => parseRoute(window.location.hash));
  useEffect(() => {
    const onChange = () => setRoute(parseRoute(window.location.hash));
    window.addEventListener("hashchange", onChange);
    return () => window.removeEventListener("hashchange", onChange);
  }, []);
  return route;
}
