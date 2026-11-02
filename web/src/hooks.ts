import { useCallback, useEffect, useState } from "react";

export interface Loadable<T> {
  data: T | undefined;
  error: string | undefined;
  loading: boolean;
  reload: () => void;
}

/** Loads on mount and whenever `deps` change; optionally re-polls every `pollMs`. */
export function useLoad<T>(load: () => Promise<T>, deps: unknown[], pollMs?: number): Loadable<T> {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);
  const reload = useCallback(() => setTick((t) => t + 1), []);

  useEffect(() => {
    let cancelled = false;
    load()
      .then((d) => !cancelled && (setData(d), setError(undefined)))
      .catch((e: Error) => !cancelled && setError(e.message))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);

  useEffect(() => {
    if (!pollMs) return;
    const id = setInterval(reload, pollMs);
    return () => clearInterval(id);
  }, [pollMs, reload]);

  return { data, error, loading, reload };
}

/** Runs an async action, exposing busy/error state so buttons can disable and show failures. */
export function useAction() {
  const [busy, setBusy] = useState<string>();
  const [error, setError] = useState<string>();

  const run = useCallback(async (label: string, action: () => Promise<unknown>): Promise<boolean> => {
    setBusy(label);
    setError(undefined);
    try {
      await action();
      return true;
    } catch (e) {
      setError((e as Error).message);
      return false;
    } finally {
      setBusy(undefined);
    }
  }, []);

  return { busy, error, run, clearError: () => setError(undefined) };
}
