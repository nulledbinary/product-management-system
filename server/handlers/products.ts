/**
 * /api/products and /api/products/{prodCode}
 *
 * Methods:
 *   GET    /products                     → list (USER sees ACTIVE only)
 *   POST   /products                     → create (requires PRD_ADD)
 *   GET    /products/{code}              → single
 *   PATCH  /products/{code}              → update fields OR record_status
 *   GET    /products/{code}/price-history
 *
 * NOTE: There is no DELETE method on this endpoint — by design. Soft delete
 * is a PATCH that sets record_status='INACTIVE'.
 */
import type { APIGatewayProxyEventV2, APIGatewayProxyStructuredResultV2 } from 'aws-lambda';
import { fail, ok, parseJson } from '../lib/http';
import { verifyAuthHeader } from '../lib/auth';
import { loadAppUser, requireRight, isAdmin } from '../lib/rights';
import { query, withTx } from '../db/pool';
import {
  assertSafeString,
  pattern,
  PATTERNS,
  positiveMoney,
  safeText,
  ValidationError,
} from '../lib/sanitize';
import { makeStamp } from '../lib/stamp';

interface ProductRow {
  prodCode: string;
  description: string;
  unit: string;
  record_status: string;
  stamp: string | null;
  unitPrice: string | null;
  effDate: string | null;
}

export async function handler(event: APIGatewayProxyEventV2): Promise<APIGatewayProxyStructuredResultV2> {
  try {
    const auth = await verifyAuthHeader(event.headers?.authorization ?? event.headers?.Authorization);
    const user = await loadAppUser(auth.sub);
    if (!user || user.record_status !== 'ACTIVE') {
      return fail(event, 403, 'not_activated', 'Account is not active');
    }

    const method = event.requestContext.http.method;
    const rawPath = event.requestContext.http.path;
    assertSafeString(rawPath, 'path');

    // /products
    if (/^\/api\/products\/?$/.test(rawPath)) {
      if (method === 'GET') return await list(event, user);
      if (method === 'POST') return await create(event, user);
      return fail(event, 405, 'method_not_allowed', method);
    }

    // /products/:code/price-history
    const phMatch = rawPath.match(/^\/api\/products\/([A-Z0-9]{2,6})\/price-history\/?$/);
    if (phMatch) return await history(event, user, phMatch[1]);

    // /products/:code
    const oneMatch = rawPath.match(/^\/api\/products\/([A-Z0-9]{2,6})\/?$/);
    if (oneMatch) {
      const code = oneMatch[1];
      if (method === 'GET')   return await getOne(event, user, code);
      if (method === 'PATCH') return await patch(event, user, code);
      return fail(event, 405, 'method_not_allowed', method);
    }

    return fail(event, 404, 'not_found', `Unknown route: ${rawPath}`);
  } catch (err) {
    if (err instanceof ValidationError) return fail(event, 400, 'validation', err.message);
    const status = (err as { status?: number }).status;
    if (status) return fail(event, status, (err as { code?: string }).code ?? 'error', (err as Error).message);
    console.error('[products] error', err);
    return fail(event, 500, 'server_error', 'Unexpected error');
  }
}

async function list(event: APIGatewayProxyEventV2, user: Awaited<ReturnType<typeof loadAppUser>>): Promise<APIGatewayProxyStructuredResultV2> {
  if (!user) return fail(event, 401, 'unauthenticated', 'No user');
  const includeInactive = event.queryStringParameters?.include === 'inactive';
  const seeAll = isAdmin(user);
  const where = !seeAll || !includeInactive ? `WHERE record_status = 'ACTIVE'` : '';

  const rows = await query<ProductRow>(
    `SET search_path = hopedb, public;
     SELECT p."prodCode", p.description, p.unit, p.record_status, p.stamp,
            ph."unitPrice", ph."effDate"
     FROM product p
     LEFT JOIN LATERAL (
       SELECT "unitPrice", "effDate" FROM "priceHist"
       WHERE "prodCode" = p."prodCode" ORDER BY "effDate" DESC LIMIT 1
     ) ph ON true
     ${where}
     ORDER BY p."prodCode"`,
  );

  return ok(event, rows.map((r) => sanitiseRow(r, user!.user_type)));
}

async function getOne(event: APIGatewayProxyEventV2, user: Awaited<ReturnType<typeof loadAppUser>>, code: string): Promise<APIGatewayProxyStructuredResultV2> {
  pattern(code, PATTERNS.prodCode, 'prodCode');
  const rows = await query<ProductRow>(
    `SET search_path = hopedb, public;
     SELECT p."prodCode", p.description, p.unit, p.record_status, p.stamp,
            ph."unitPrice", ph."effDate"
     FROM product p
     LEFT JOIN LATERAL (
       SELECT "unitPrice", "effDate" FROM "priceHist"
       WHERE "prodCode" = p."prodCode" ORDER BY "effDate" DESC LIMIT 1
     ) ph ON true
     WHERE p."prodCode" = $1`,
    [code],
  );
  if (!rows.length) return fail(event, 404, 'not_found', `No product ${code}`);
  const row = rows[0];
  if (user!.user_type === 'USER' && row.record_status !== 'ACTIVE') {
    return fail(event, 404, 'not_found', `No product ${code}`);
  }
  return ok(event, sanitiseRow(row, user!.user_type));
}

async function create(event: APIGatewayProxyEventV2, user: Awaited<ReturnType<typeof loadAppUser>>): Promise<APIGatewayProxyStructuredResultV2> {
  requireRight(user!, 'PRD_ADD');
  const body = parseJson<{ prodCode: string; description: string; unit: string; unitPrice: number }>(event);
  const prodCode = pattern(body.prodCode, PATTERNS.prodCode, 'prodCode');
  const description = safeText(body.description, 'description', 30);
  const unit = pattern(body.unit, PATTERNS.unit, 'unit');
  const unitPrice = positiveMoney(body.unitPrice, 'unitPrice');
  const stamp = makeStamp('ADDED', user!.userId);

  await withTx(async (q) => {
    await q(`SET LOCAL search_path = hopedb, public`);
    await q(`SET LOCAL hopepms.caller_userid = $1`, [user!.userId]);
    await q(
      `INSERT INTO product ("prodCode", description, unit, record_status, stamp)
       VALUES ($1, $2, $3, 'ACTIVE', $4)`,
      [prodCode, description, unit, stamp],
    );
    await q(
      `INSERT INTO "priceHist" ("effDate", "prodCode", "unitPrice", stamp)
       VALUES (CURRENT_DATE, $1, $2, $3)
       ON CONFLICT ("effDate", "prodCode") DO UPDATE SET "unitPrice" = EXCLUDED."unitPrice", stamp = EXCLUDED.stamp`,
      [prodCode, unitPrice, makeStamp('PRICE_SET', user!.userId)],
    );
  });

  return ok(event, { prodCode, description, unit, record_status: 'ACTIVE', stamp, currentPrice: unitPrice, effDate: new Date().toISOString().slice(0, 10) }, 201);
}

async function patch(event: APIGatewayProxyEventV2, user: Awaited<ReturnType<typeof loadAppUser>>, code: string): Promise<APIGatewayProxyStructuredResultV2> {
  pattern(code, PATTERNS.prodCode, 'prodCode');
  const body = parseJson<Partial<{ description: string; unit: string; unitPrice: number; record_status: 'ACTIVE' | 'INACTIVE' }>>(event);

  // Status change path → soft delete / recover
  if (typeof body.record_status === 'string') {
    const next = pattern(body.record_status, PATTERNS.recordStatus, 'record_status');
    if (next === 'INACTIVE') requireRight(user!, 'PRD_DEL');
    if (next === 'ACTIVE') {
      if (!isAdmin(user!)) {
        return fail(event, 403, 'forbidden', 'Only ADMIN/SUPERADMIN can recover');
      }
    }
    const stamp = makeStamp(next === 'INACTIVE' ? 'DEACTIVATED' : 'REACTIVATED', user!.userId);
    await withTx(async (q) => {
      await q(`SET LOCAL search_path = hopedb, public`);
      await q(`SET LOCAL hopepms.caller_userid = $1`, [user!.userId]);
      const updated = await q<{ prodCode: string }>(
        `UPDATE product SET record_status = $1, stamp = $2
         WHERE "prodCode" = $3
         RETURNING "prodCode"`,
        [next, stamp, code],
      );
      if (!updated.length) throw Object.assign(new Error('not_found'), { status: 404, code: 'not_found' });
    });
    return ok(event, { ok: true });
  }

  // Field edit path
  requireRight(user!, 'PRD_EDIT');
  const description = body.description !== undefined ? safeText(body.description, 'description', 30) : null;
  const unit = body.unit !== undefined ? pattern(body.unit, PATTERNS.unit, 'unit') : null;
  const unitPrice = body.unitPrice !== undefined ? positiveMoney(body.unitPrice, 'unitPrice') : null;
  const stamp = makeStamp('EDITED', user!.userId);

  await withTx(async (q) => {
    await q(`SET LOCAL search_path = hopedb, public`);
    await q(`SET LOCAL hopepms.caller_userid = $1`, [user!.userId]);
    if (description !== null || unit !== null) {
      await q(
        `UPDATE product SET
            description = COALESCE($2, description),
            unit        = COALESCE($3, unit),
            stamp       = $4
         WHERE "prodCode" = $1`,
        [code, description, unit, stamp],
      );
    }
    if (unitPrice !== null) {
      await q(
        `INSERT INTO "priceHist" ("effDate", "prodCode", "unitPrice", stamp)
         VALUES (CURRENT_DATE, $1, $2, $3)
         ON CONFLICT ("effDate", "prodCode") DO UPDATE SET "unitPrice" = EXCLUDED."unitPrice", stamp = EXCLUDED.stamp`,
        [code, unitPrice, makeStamp('PRICE_SET', user!.userId)],
      );
    }
  });

  return ok(event, { ok: true });
}

async function history(event: APIGatewayProxyEventV2, user: Awaited<ReturnType<typeof loadAppUser>>, code: string): Promise<APIGatewayProxyStructuredResultV2> {
  pattern(code, PATTERNS.prodCode, 'prodCode');
  const rows = await query<{ effDate: string; prodCode: string; unitPrice: string; stamp: string | null }>(
    `SET search_path = hopedb, public;
     SELECT "effDate", "prodCode", "unitPrice", stamp
     FROM "priceHist" WHERE "prodCode" = $1 ORDER BY "effDate" DESC`,
    [code],
  );
  return ok(event, rows.map((r) => ({
    effDate: String(r.effDate).slice(0, 10),
    prodCode: r.prodCode,
    unitPrice: Number(r.unitPrice),
    ...(isAdmin(user!) ? { stamp: r.stamp ?? undefined } : {}),
  })));
}

function sanitiseRow(r: ProductRow, userType: 'SUPERADMIN' | 'ADMIN' | 'USER') {
  const out: Record<string, unknown> = {
    prodCode: r.prodCode,
    description: r.description,
    unit: r.unit,
    record_status: r.record_status,
    currentPrice: r.unitPrice == null ? null : Number(r.unitPrice),
    effDate: r.effDate ? String(r.effDate).slice(0, 10) : null,
  };
  if (userType !== 'USER') out.stamp = r.stamp;
  return out;
}
