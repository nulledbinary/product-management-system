/**
 * Count-up animation for KPI / stat figures.
 *
 * Eases a number from its current on-screen value to a target with
 * requestAnimationFrame, so a refreshed dashboard *animates* to the new
 * figure instead of snapping. Honours `prefers-reduced-motion` (jumps
 * straight to the value) and is re-entrant: calling it again on the same
 * element cancels the in-flight tween so rapid refreshes never fight.
 */

export interface CountUpOptions {
  /** Tween length in ms. Default 900. */
  duration?: number;
  /** Decimal places when no `format` is supplied. Default 0. */
  decimals?: number;
  /** Custom formatter (currency, locale grouping, …); wins over `decimals`. */
  format?: (value: number) => string;
}

interface Animatable extends HTMLElement {
  __countUpRaf?: number;
}

const prefersReducedMotion = (): boolean =>
  typeof window !== 'undefined' &&
  typeof window.matchMedia === 'function' &&
  window.matchMedia('(prefers-reduced-motion: reduce)').matches;

/** easeOutCubic — fast start, gentle settle. */
const ease = (t: number): number => 1 - Math.pow(1 - t, 3);

function fmt(value: number, opts: CountUpOptions): string {
  if (opts.format) return opts.format(value);
  return value.toFixed(opts.decimals ?? 0);
}

/**
 * Animate `el` to `to`. Non-finite targets render an em dash; a null/missing
 * element is a safe no-op so call sites can pass `querySelector(...)` result
 * directly.
 */
export function countUp(
  el: Element | null,
  to: number,
  opts: CountUpOptions = {},
): void {
  if (!el) return;
  const node = el as Animatable;

  if (node.__countUpRaf) {
    cancelAnimationFrame(node.__countUpRaf);
    node.__countUpRaf = undefined;
  }

  if (!Number.isFinite(to)) {
    node.textContent = '—';
    return;
  }

  const from = parseFloat((node.textContent ?? '').replace(/[^0-9.-]/g, ''));
  const start = Number.isFinite(from) ? from : 0;

  if (start === to || prefersReducedMotion()) {
    node.textContent = fmt(to, opts);
    return;
  }

  const duration = Math.max(1, opts.duration ?? 900);
  const t0 = performance.now();
  node.classList.add('stat-counting');

  const step = (now: number): void => {
    const p = Math.min(1, (now - t0) / duration);
    const value = start + (to - start) * ease(p);
    node.textContent = fmt(value, opts);
    if (p < 1) {
      node.__countUpRaf = requestAnimationFrame(step);
    } else {
      node.textContent = fmt(to, opts);
      node.classList.remove('stat-counting');
      node.__countUpRaf = undefined;
    }
  };

  node.__countUpRaf = requestAnimationFrame(step);
}
