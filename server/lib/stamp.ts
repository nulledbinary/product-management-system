/** Server-side mirror of src/lib/utils/stamp.ts — same format. */
export type StampAction =
  | 'ADDED'
  | 'EDITED'
  | 'DEACTIVATED'
  | 'REACTIVATED'
  | 'REGISTERED'
  | 'PRICE_SET';

export function makeStamp(action: StampAction, userId: string): string {
  const d = new Date();
  const yyyy = d.getUTCFullYear();
  const mm = String(d.getUTCMonth() + 1).padStart(2, '0');
  const dd = String(d.getUTCDate()).padStart(2, '0');
  const hh = String(d.getUTCHours()).padStart(2, '0');
  const mi = String(d.getUTCMinutes()).padStart(2, '0');
  return `${action} ${userId} ${yyyy}-${mm}-${dd} ${hh}:${mi}`;
}
