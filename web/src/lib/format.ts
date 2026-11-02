/** Pretty-prints a JSON object or array; undefined when the text isn't one (so callers show it as-is). */
export function prettyJson(value: string): string | undefined {
  const trimmed = value.trim();
  if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return undefined;
  try {
    return JSON.stringify(JSON.parse(trimmed), null, 2);
  } catch {
    return undefined;
  }
}
