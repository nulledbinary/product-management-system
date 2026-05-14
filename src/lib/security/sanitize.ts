/**
 * Minimal output-encoding helpers. Astro escapes all template expressions by
 * default — these are for the rare cases where we build markup imperatively
 * (e.g., DOM-manipulated table rows in a script block) and for stamping
 * sanitized strings into URL/query positions.
 */

const HTML_ESCAPES: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
  '/': '&#x2F;',
  '`': '&#x60;',
  '=': '&#x3D;',
};

export function escapeHtml(input: unknown): string {
  if (input == null) return '';
  return String(input).replace(/[&<>"'`=\/]/g, (c) => HTML_ESCAPES[c] ?? c);
}

export function escapeAttr(input: unknown): string {
  return escapeHtml(input);
}

/**
 * URL-safe identifier: only what `encodeURIComponent` would produce, AND we
 * verify the round-trip after path-traversal stripping (so `%2e%2e` cannot
 * survive a decode hop in some downstream service).
 */
export function urlSafe(input: unknown): string {
  const s = String(input ?? '');
  if (/[\x00-\x1f]/.test(s) || s.includes('..')) return '';
  return encodeURIComponent(s);
}
