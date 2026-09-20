"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  FEELINGS,
  type Feeling,
  type State,
  TAG_WORDS,
  normalize,
  say,
  state as fetchState,
  stop,
} from "@/lib/acmo";
import { storeAddress, useIsMac, useStoredAddress } from "@/lib/browser";

/** How often the tablet is asked what it is doing. */
const POLL_MS = 500;

function describe(s: State): string {
  switch (s.state) {
    case "listening":
    case "thinking":
      return "talking to someone";
    case "speaking":
      return s.line ? `speaking · ${s.face ?? s.line.feeling}` : "speaking (not a remote line)";
    default:
      return s.state;
  }
}

export default function Page() {
  const address = useStoredAddress();
  const [feeling, setFeeling] = useState<Feeling>("happy");
  const [text, setText] = useState("");
  const [status, setStatus] = useState<State | null>(null);
  const [reachable, setReachable] = useState<boolean | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [whyNot, setWhyNot] = useState<string | null>(null);
  const box = useRef<HTMLTextAreaElement>(null);
  const polling = useRef(false);
  const sending = useRef(false);
  const mac = useIsMac();
  const base = address === null ? null : normalize(address);

  useEffect(() => {
    box.current?.focus();
  }, []);

  // Ask the tablet what it is doing, twice a second; a slow answer is not asked over, and a poll
  // for an address that has since been edited is abandoned rather than allowed to block the next.
  useEffect(() => {
    if (base === null) return;
    let alive = true;
    const aborter = new AbortController();
    const tick = async () => {
      if (polling.current) return;
      polling.current = true;
      try {
        const s = await fetchState(base, aborter.signal);
        if (alive) {
          setStatus(s);
          setReachable(true);
          setWhyNot(null);
        }
      } catch (e) {
        if (alive) {
          setStatus(null);
          setReachable(false);
          setWhyNot((e as Error).message);
        }
      } finally {
        polling.current = false;
      }
    };
    void tick();
    const timer = window.setInterval(() => void tick(), POLL_MS);
    return () => {
      alive = false;
      aborter.abort();
      polling.current = false;
      window.clearInterval(timer);
    };
  }, [base]);

  const send = useCallback(
    async (now: boolean) => {
      const line = text.trim();
      if (!line || base === null || sending.current) return;
      sending.current = true;
      try {
        await say(base, line, feeling, now);
        setText("");
        setProblem(null);
        if (box.current) box.current.style.height = "auto";
      } catch (e) {
        setProblem((e as Error).message);
      } finally {
        sending.current = false;
      }
      box.current?.focus();
    },
    [base, feeling, text],
  );

  const hush = useCallback(async () => {
    if (base === null) return;
    try {
      await stop(base);
      setProblem(null);
    } catch (e) {
      setProblem((e as Error).message);
    }
  }, [base]);

  // A word from the row under the faces, dropped into the line as a tag, at the cursor.
  const insert = useCallback(
    (word: string) => {
      const el = box.current;
      const tag = `[${word}] `;
      const start = el?.selectionStart ?? text.length;
      const end = el?.selectionEnd ?? start;
      setText(text.slice(0, start) + tag + text.slice(end));
      // The caret goes after the tag once React has rendered the new value.
      requestAnimationFrame(() => {
        if (!el) return;
        el.focus();
        el.setSelectionRange(start + tag.length, start + tag.length);
        el.style.height = "auto";
        el.style.height = `${el.scrollHeight}px`;
      });
    },
    [text],
  );

  const onKey = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.nativeEvent.isComposing) return; // Enter that commits an IME candidate is not a send
    if (e.key === "Escape") {
      e.preventDefault();
      void hush();
    } else if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      void send(e.metaKey || e.ctrlKey);
    }
  };

  const busy =
    status !== null &&
    (status.state === "listening" || status.state === "thinking" || status.state === "speaking" || status.queue.length > 0);
  const empty = text.trim() === "";
  const mod = mac ? "⌘" : "Ctrl";

  return (
    <main className="console">
      <header className="bar">
        <h1>ACMO remote</h1>
        <label className="address">
          <span
            className={`dot ${reachable === null ? "" : reachable ? "on" : "off"}`}
            title={reachable === null ? "connecting" : reachable ? "connected" : "not connected"}
          />
          <input
            value={address ?? ""}
            onChange={(e) => storeAddress(e.target.value)}
            spellCheck={false}
            aria-label="tablet address"
          />
        </label>
      </header>

      {reachable === false && whyNot !== null && !whyNot.startsWith("can't reach") && (
        <p className="hint">The tablet answers, but: {whyNot}.</p>
      )}
      {reachable === false && (whyNot === null || whyNot.startsWith("can't reach")) && (
        <p className="hint">
          Can&apos;t reach {base}. Run <code>adb forward tcp:8765 tcp:8765</code>, or enter the address from ACMO&apos;s
          settings card (five taps in the top-left corner of the face). On a LAN address, macOS may also need the
          browser allowed under System Settings → Privacy &amp; Security → Local Network.
        </p>
      )}

      <section className="compose">
        <div className="faces" role="radiogroup" aria-label="face">
          {FEELINGS.map((f) => (
            <button
              key={f}
              type="button"
              role="radio"
              aria-checked={f === feeling}
              className={`pill ${f === feeling ? "selected" : ""}`}
              onClick={() => setFeeling(f)}
            >
              {f}
            </button>
          ))}
        </div>
        <div className="words">
          {TAG_WORDS[feeling].map((w) => (
            <button key={w} type="button" className="word" onClick={() => insert(w)} title="insert at the cursor">
              [{w}]
            </button>
          ))}
          <span className="hint">
            In the line these change the voice and the face; any other [tag] the voice only. A tagged line starts
            about a second later.
          </span>
        </div>
        <textarea
          ref={box}
          value={text}
          rows={2}
          placeholder="What should ACMO say? A [tag] changes the tone; a face word changes the face too."
          aria-label="the line"
          onChange={(e) => {
            setText(e.target.value);
            e.target.style.height = "auto";
            e.target.style.height = `${e.target.scrollHeight}px`;
          }}
          onKeyDown={onKey}
        />
        <div className="actions">
          <button type="button" className="pill selected" onClick={() => void send(false)} disabled={empty}>
            Queue <kbd>⏎</kbd>
          </button>
          <button type="button" className="pill" onClick={() => void send(true)} disabled={empty}>
            Say now <kbd>{mod}⏎</kbd>
          </button>
          <button type="button" className="pill" onClick={() => void hush()} disabled={!busy}>
            Stop <kbd>esc</kbd>
          </button>
        </div>
        {problem && <p className="problem">{problem}</p>}
      </section>

      <section className="status" aria-live="polite">
        {status ? (
          <>
            <p className="now">
              <span className="eyebrow">{describe(status)}</span>
              {status.line && <span>“{status.line.text}”</span>}
            </p>
            {status.queue.length > 0 && (
              <ol className="queue">
                {status.queue.map((e, i) => (
                  <li key={e.id}>
                    <span className="eyebrow">
                      {i + 1} {e.feeling}
                    </span>
                    <span>“{e.text}”</span>
                  </li>
                ))}
              </ol>
            )}
            {status.error && (
              <p className="problem">
                line {status.error.id}: {status.error.message}
              </p>
            )}
          </>
        ) : (
          <p className="eyebrow">{reachable === null ? "connecting…" : "not connected"}</p>
        )}
      </section>
    </main>
  );
}
