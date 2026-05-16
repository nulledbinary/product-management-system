/**
 * Audit stamp utilities. Matches the format in HopePMS docx §9.1:
 *   ACTION USERID YYYY-MM-DD HH:MM
 * Example: ADDED user2 2026-05-13 20:00
 */

export type StampAction =
  | 'ADDED'
  | 'EDITED'
  | 'DEACTIVATED'
  | 'REACTIVATED'
  | 'REGISTERED'
  | 'PRICE_SET';

export function makeStamp(action: StampAction, userId: string): string {
  const d = new Date();
  const yyyy = d.getFullYear();
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  const hh = String(d.getHours()).padStart(2, '0');
  const mi = String(d.getMinutes()).padStart(2, '0');
  return `${action} ${userId} ${yyyy}-${mm}-${dd} ${hh}:${mi}`;
}

/** Parse a stamp into its components — never trust this server-side. */
export function parseStamp(stamp: string | undefined | null): {
  action: string;
  userId: string;
  when: string;
} | null {
  if (!stamp) return null;
  // The actor token can be a multi-word full name (post-eradication scrub),
  // so anchor on the trailing "YYYY-MM-DD HH:MM" and take everything between.
  const m = stamp.match(/^(\w+)\s+(.+?)\s+(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2})$/);
  if (!m) return null;
  return { action: m[1], userId: m[2], when: m[3] };
}

const ACTION_VERB: Record<string, string> = {
  ADDED: 'Added',
  CREATED: 'Created',
  EDITED: 'Edited',
  DEACTIVATED: 'Deactivated',
  REACTIVATED: 'Reactivated',
  ACTIVATED: 'Activated',
  REGISTERED: 'Registered',
  PRICE_SET: 'Price updated',
  PROMOTED: 'Promoted',
  DEMOTED: 'Demoted',
};

/** Auth0 subs (auth0|…, google-oauth2|…) and our seed ids are not human. */
function looksOpaque(id: string): boolean {
  return /[|]/.test(id) || /^[0-9a-f]{16,}$/i.test(id) || id.length > 36;
}

/**
 * Plain-language audit line: "Added by Jane Cruz · 16 May 2026, 8:00 PM".
 * `nameOf` resolves a userId to a display name when the caller has the
 * directory loaded (the admin page does); unknown/opaque ids degrade to
 * "a teammate" so we never surface a raw sub or username.
 */
export function humanizeStamp(
  stamp: string | undefined | null,
  nameOf?: (userId: string) => string | undefined,
): string {
  const p = parseStamp(stamp);
  if (!p) return stamp ? String(stamp) : '—';
  const verb = ACTION_VERB[p.action] ?? p.action.charAt(0) + p.action.slice(1).toLowerCase();

  const resolved = nameOf?.(p.userId);
  const who = resolved && resolved.trim()
    ? resolved.trim()
    : looksOpaque(p.userId) ? 'a teammate' : p.userId;

  let when = p.when;
  const d = new Date(p.when.replace(' ', 'T'));
  if (!Number.isNaN(d.getTime())) {
    when = d.toLocaleString(undefined, {
      day: 'numeric', month: 'short', year: 'numeric',
      hour: 'numeric', minute: '2-digit',
    });
  }
  return `${verb} by ${who} · ${when}`;
}
