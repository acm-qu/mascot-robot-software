/**
 * The tablet's remote API (RemoteServer.kt in the app): three calls, JSON both
 * ways, CORS open. Failures come back as Error(message) with the server's own
 * wording when it gave one.
 */

export const FEELINGS = ["idle", "surprised", "sad", "happy", "angry", "passionate", "annoyed", "excited"] as const;
export type Feeling = (typeof FEELINGS)[number];

/**
 * The words that change the face when written in brackets -- the label first, then three more
 * ways of saying it. Mirrors Tags.WORDS in the app (remote/Tags.kt), which is the source of truth.
 * Any other [word] is a voice-only tag: ElevenLabs takes the tone, the face stays.
 */
export const TAG_WORDS: Record<Feeling, readonly string[]> = {
  idle: ["idle"],
  surprised: ["surprised", "gasps", "shocked", "amazed"],
  sad: ["sad", "crying", "gloomy", "disappointed"],
  happy: ["happy", "laughs", "giggles", "cheerful"],
  angry: ["angry", "shouting", "furious", "growls"],
  passionate: ["passionate", "loving", "romantic", "dramatic"],
  annoyed: ["annoyed", "sarcastic", "groans", "frustrated"],
  excited: ["excited", "thrilled", "enthusiastic", "energetic"],
};

export type Entry = { id: number; text: string; feeling: Feeling };

export type State = {
  state: "booting" | "idle" | "listening" | "thinking" | "speaking";
  /** The face on screen right now; during a tagged line it moves. Older tablets do not send it. */
  face?: Feeling;
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

async function call<T>(base: string, path: string, init?: RequestInit, signal?: AbortSignal): Promise<T> {
  let res: Response;
  let text: string;
  try {
    const timeout = AbortSignal.timeout(TIMEOUT_MS);
    res = await fetch(base + path, { ...init, signal: signal ? AbortSignal.any([signal, timeout]) : timeout });
    text = await res.text();
  } catch {
    throw new Error(`can't reach ${base}`);
  }
  let data: unknown = null;
  try {
    data = JSON.parse(text);
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

/** What the tablet is doing. [signal] cancels a poll that is no longer wanted. */
export function state(base: string, signal?: AbortSignal) {
  return call<State>(base, "/state", undefined, signal);
}
