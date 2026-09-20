/**
 * The tablet's remote API (RemoteServer.kt in the app): three calls, JSON both
 * ways, CORS open. Failures come back as Error(message) with the server's own
 * wording when it gave one.
 */

export const FEELINGS = ["idle", "surprised", "sad", "happy", "angry", "passionate", "annoyed", "excited"] as const;
export type Feeling = (typeof FEELINGS)[number];

export type Entry = { id: number; text: string; feeling: Feeling };

export type State = {
  state: "booting" | "idle" | "listening" | "thinking" | "speaking";
  /** The remote line being spoken; null during a wake-word reply, or when quiet. */
  line: Entry | null;
  queue: Entry[];
  error: { id: number; message: string } | null;
};

export const DEFAULT_ADDRESS = "http://localhost:8765";

const TIMEOUT_MS = 5000;

/** What the user typed, as a base URL: scheme added, trailing slashes dropped, empty means the default. */
export function normalize(address: string): string {
  let a = address.trim().replace(/\/+$/, "");
  if (a && !/^https?:\/\//i.test(a)) a = `http://${a}`;
  return a || DEFAULT_ADDRESS;
}

async function call<T>(base: string, path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    res = await fetch(base + path, { ...init, signal: AbortSignal.timeout(TIMEOUT_MS) });
  } catch {
    throw new Error(`can't reach ${base}`);
  }
  const text = await res.text();
  let data: unknown = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    // not JSON; the status code is the message then
  }
  if (!res.ok) {
    const error = (data as { error?: string } | null)?.error;
    throw new Error(error ?? `HTTP ${res.status}`);
  }
  return data as T;
}

export function say(base: string, text: string, feeling: Feeling, now: boolean) {
  return call<{ id: number; queued: number }>(base, "/say", {
    method: "POST",
    headers: { "Content-Type": "application/json; charset=utf-8" },
    body: JSON.stringify({ text, feeling, now }),
  });
}

export function stop(base: string) {
  return call<{ ok: boolean }>(base, "/stop", { method: "POST" });
}

export function state(base: string) {
  return call<State>(base, "/state");
}
