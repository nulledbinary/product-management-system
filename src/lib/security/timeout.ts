/**
 * Inactivity watchdog. Resets on user activity; on timeout, fires the supplied
 * logout callback. The server-side Redis TTL (set by VolatileSessionStore) is
 * the real safety net — this purely provides a snappier UX so we don't sit on
 * an idle page until the cookie expires.
 */

let timer: ReturnType<typeof setTimeout> | null = null;
let bound = false;
let lastTouch = 0;

export function sessionTimeoutMs(): number {
  const raw = Number(import.meta.env.PUBLIC_SESSION_TIMEOUT_MIN ?? '30');
  const minutes = Number.isFinite(raw) && raw > 0 ? raw : 30;
  return minutes * 60 * 1000;
}

export function lastTouchedAt(): number {
  return lastTouch;
}

export function touch(): void {
  lastTouch = Date.now();
}

export function startInactivityWatchdog(onTimeout: () => void): () => void {
  if (typeof window === 'undefined') return () => {};

  const ms = sessionTimeoutMs();

  const reset = () => {
    touch();
    if (timer) clearTimeout(timer);
    timer = setTimeout(onTimeout, ms);
  };

  if (!bound) {
    const events: Array<keyof DocumentEventMap> = [
      'mousemove',
      'mousedown',
      'keydown',
      'touchstart',
      'scroll',
      'wheel',
    ];
    for (const e of events) {
      document.addEventListener(e, reset, { passive: true });
    }
    bound = true;
  }

  reset();

  return () => {
    if (timer) {
      clearTimeout(timer);
      timer = null;
    }
  };
}
