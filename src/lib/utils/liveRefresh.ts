/**
 * Keeps a data view from going stale.
 *
 * A list that loads once and never re-reads is "stagnant": when one session
 * soft-deletes a product, every other open list keeps showing it until a
 * manual refresh. This is the listener that fixes that — it re-runs the
 * caller's refresh function:
 *
 *   - on an interval (only while the tab is visible — a hidden tab does not
 *     poll, and catches up the moment it is shown again),
 *   - when the tab regains focus / becomes visible,
 *   - immediately when any tab calls `ping()` on the same channel
 *     (BroadcastChannel), so a mutation here updates every other open view
 *     of the same data at once.
 *
 * Returns `{ stop, ping }`. Call `ping()` right after a successful create /
 * update / soft-delete / recover so sibling tabs reconcile instantly instead
 * of waiting for their next interval tick.
 */

export interface LiveRefreshOptions {
  /** Poll cadence in ms (floored at 5s). Default 15s. */
  intervalMs?: number;
  /** BroadcastChannel name shared by every view of the same data. */
  channel?: string;
}

export interface LiveRefreshHandle {
  stop: () => void;
  /** Tell this tab and all sibling tabs to refresh now. */
  ping: () => void;
}

export function startLiveRefresh(
  refresh: () => unknown | Promise<unknown>,
  options: LiveRefreshOptions = {},
): LiveRefreshHandle {
  if (typeof window === 'undefined') {
    return { stop: () => {}, ping: () => {} };
  }

  const intervalMs = Math.max(5000, options.intervalMs ?? 15000);
  let stopped = false;
  let inFlight = false;

  async function run(): Promise<void> {
    if (stopped || inFlight) return;
    if (document.visibilityState !== 'visible') return;
    inFlight = true;
    try {
      await refresh();
    } catch {
      /* a failed refresh is non-fatal — the next trigger retries */
    } finally {
      inFlight = false;
    }
  }

  const timer = window.setInterval(run, intervalMs);

  const onVisible = () => { if (document.visibilityState === 'visible') void run(); };
  const onFocus = () => { void run(); };
  document.addEventListener('visibilitychange', onVisible);
  window.addEventListener('focus', onFocus);

  let bc: BroadcastChannel | null = null;
  if (options.channel) {
    try {
      bc = new BroadcastChannel(options.channel);
      bc.onmessage = (ev) => {
        if (ev.data?.type === 'refresh') void run();
      };
    } catch {
      bc = null; // unsupported — interval + focus still cover it
    }
  }

  return {
    stop() {
      stopped = true;
      clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
      window.removeEventListener('focus', onFocus);
      try { bc?.close(); } catch {}
    },
    ping() {
      void run();
      try { bc?.postMessage({ type: 'refresh' }); } catch {}
    },
  };
}
