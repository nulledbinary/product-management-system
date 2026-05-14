/**
 * /api/reports/product-listing  (REP_001)
 * /api/reports/top-selling      (REP_002)
 *
 * Both endpoints re-check the right at the Lambda layer.
 */
import type { APIGatewayProxyEventV2, APIGatewayProxyStructuredResultV2 } from 'aws-lambda';
import { fail, ok } from '../lib/http';
import { verifyAuthHeader } from '../lib/auth';
import { loadAppUser, requireRight } from '../lib/rights';
import { query } from '../db/pool';
import { assertSafeString } from '../lib/sanitize';

export async function handler(event: APIGatewayProxyEventV2): Promise<APIGatewayProxyStructuredResultV2> {
  try {
    const auth = await verifyAuthHeader(event.headers?.authorization ?? event.headers?.Authorization);
    const user = await loadAppUser(auth.sub);
    if (!user || user.record_status !== 'ACTIVE') {
      return fail(event, 403, 'not_activated', 'Account is not active');
    }

    const path = event.requestContext.http.path;
    assertSafeString(path, 'path');

    if (/^\/api\/reports\/product-listing\/?$/.test(path)) {
      requireRight(user, 'REP_001');
      const rows = await query<{
        prodCode: string; description: string; unit: string;
        unitPrice: string; effDate: string;
      }>(
        `SET search_path = hopedb, public;
         SELECT "prodCode", description, unit, "unitPrice", "effDate"
         FROM v_product_current_price
         WHERE record_status = 'ACTIVE'
         ORDER BY "prodCode"`,
      );
      return ok(event, rows.map((r) => ({
        prodCode: r.prodCode,
        description: r.description,
        unit: r.unit,
        unitPrice: Number(r.unitPrice),
        effDate: String(r.effDate).slice(0, 10),
      })));
    }

    if (/^\/api\/reports\/top-selling\/?$/.test(path)) {
      requireRight(user, 'REP_002');
      const limit = clampInt(event.queryStringParameters?.limit, 1, 100, 10);
      const rows = await query<{ prodCode: string; description: string; totalQty: string }>(
        `SET search_path = hopedb, public;
         SELECT "prodCode", description, "totalQty"
         FROM v_top_selling LIMIT $1`,
        [limit],
      );
      return ok(event, rows.map((r) => ({
        prodCode: r.prodCode,
        description: r.description,
        totalQty: Number(r.totalQty),
      })));
    }

    return fail(event, 404, 'not_found', `Unknown route ${path}`);
  } catch (err) {
    const status = (err as { status?: number }).status;
    if (status) return fail(event, status, (err as { code?: string }).code ?? 'error', (err as Error).message);
    console.error('[reports] error', err);
    return fail(event, 500, 'server_error', 'Unexpected error');
  }
}

function clampInt(v: string | undefined, lo: number, hi: number, dflt: number): number {
  const n = Number(v);
  if (!Number.isFinite(n)) return dflt;
  return Math.max(lo, Math.min(hi, Math.trunc(n)));
}
