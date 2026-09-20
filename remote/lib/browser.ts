/**
 * Two things the page needs from the browser that the server cannot know: the
 * remembered tablet address, and whether the keyboard has a ⌘ key. Both are
 * read through useSyncExternalStore, so the server renders without them and
 * the client fills them in on hydration -- no state is set from an effect.
 */

import { useSyncExternalStore } from "react";
import { DEFAULT_ADDRESS } from "./acmo";

const ADDRESS_KEY = "acmo.address";
const listeners = new Set<() => void>();
let current: string | null = null; // read from storage once; then the live value, storage or not

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function load(): string {
  if (current === null) {
    let saved: string | null = null;
    try {
      saved = window.localStorage.getItem(ADDRESS_KEY);
    } catch {
      // no storage here (a locked-down browser): remembered for this page only
    }
    current = saved ?? DEFAULT_ADDRESS;
  }
  return current;
}

/** The tablet address, remembered per browser; null while the server renders. */
export function useStoredAddress(): string | null {
  return useSyncExternalStore(subscribe, load, () => null);
}

/** Remembers [address] and re-renders whoever is showing it. */
export function storeAddress(address: string) {
  current = address;
  try {
    window.localStorage.setItem(ADDRESS_KEY, address);
  } catch {
    // remembered for this page only
  }
  listeners.forEach((listener) => listener());
}

const never = () => () => {};

/** Whether the shortcut hint should say ⌘ or Ctrl; ⌘ while the server renders. */
export function useIsMac(): boolean {
  return useSyncExternalStore(never, () => /Mac|iPhone|iPad/.test(navigator.platform), () => true);
}
