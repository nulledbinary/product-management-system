/**
 * Inactivity watchdog. Resets on user activity; on timeout, fires the supplied
 * logout callback. Used together with the visibilitychange handler in
 * session.ts to cover both foreground idleness and background-tab idleness.
 */
import { clearSession, sessionTimeoutMs, touch } from './session';

let timer: ReturnType<typeof setTimeout> | null = null;
let bound = false;

export function startInactivityWatchdog(onTimeout: () => void): () => void {
  if (typeof window === 'undefined') return () => {};

  const ms = sessionTimeoutMs();

  const fire = () => {
    clearSession();
    onTimeout();
  };

  const reset = () => {
    touch();
    if (timer) clearTimeout(timer);
    timer = setTimeout(fire, ms);
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
