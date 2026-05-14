/**
 * Output sanitization helpers — escape user-supplied strings before they're
 * inserted into innerHTML. We use textContent or templated DOM where possible;
 * this helper is only for tagged-template `escapeHtml(x)` interpolation.
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

export function escapeHtml(value: unknown): string {
  if (value === null || value === undefined) return '';
  return String(value).replace(/[&<>"'`=/]/g, (ch) => HTML_ESCAPES[ch] ?? ch);
}
