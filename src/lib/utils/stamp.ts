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
  const m = stamp.match(/^(\w+)\s+(\S+)\s+(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2})$/);
  if (!m) return null;
  return { action: m[1], userId: m[2], when: m[3] };
}
