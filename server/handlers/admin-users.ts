/**
 * /api/admin/users
 *   GET                            → list all users (ADM_USER required)
 *   PATCH /admin/users/{id}/activate
 *   PATCH /admin/users/{id}/deactivate
 *
 * SUPERADMIN protection is enforced THREE TIMES:
 *   1. This handler refuses to operate on rows where target.user_type='SUPERADMIN'
 *      unless caller is also SUPERADMIN.
 *   2. The hopepms.caller_userid GUC is set so the trg_protect_superadmin
 *      Postgres trigger re-checks at the database layer.
 *   3. The frontend disables the buttons for those rows.
 */
import type { APIGatewayProxyEventV2, APIGatewayProxyStructuredResultV2 } from 'aws-lambda';
import { fail, ok } from '../lib/http';
import { verifyAuthHeader } from '../lib/auth';
import { loadAppUser, requireRight } from '../lib/rights';
import { query, withTx } from '../db/pool';
import { assertSafeString, pattern, PATTERNS, ValidationError } from '../lib/sanitize';
import { makeStamp } from '../lib/stamp';

export async function handler(event: APIGatewayProxyEventV2): Promise<APIGatewayProxyStructuredResultV2> {
  try {
    const auth = await verifyAuthHeader(event.headers?.authorization ?? event.headers?.Authorization);
    const caller = await loadAppUser(auth.sub);
    if (!caller || caller.record_status !== 'ACTIVE') {
      return fail(event, 403, 'not_activated', 'Account is not active');
    }
    requireRight(caller, 'ADM_USER');

    const method = event.requestContext.http.method;
    const path = event.requestContext.http.path;
    assertSafeString(path, 'path');

    if (/^\/api\/admin\/users\/?$/.test(path) && method === 'GET') {
      const rows = await query<{
        userId: string; username: string; firstName: string; lastName: string;
        email: string; user_type: 'SUPERADMIN' | 'ADMIN' | 'USER';
        record_status: 'ACTIVE' | 'INACTIVE'; stamp: string | null;
      }>(
        `SET search_path = hopedb, public;
         SELECT "userId", username, "firstName", "lastName", email, user_type, record_status, stamp
         FROM "user" ORDER BY user_type DESC, username`,
      );
      return ok(event, rows);
    }

    const m = path.match(/^\/api\/admin\/users\/([A-Za-z0-9|_-]{1,64})\/(activate|deactivate)\/?$/);
    if (m && method === 'PATCH') {
      const targetId = pattern(m[1], PATTERNS.userId, 'userId');
      const action = m[2] as 'activate' | 'deactivate';

      const target = await query<{ user_type: string }>(
        `SET search_path = hopedb, public;
         SELECT user_type FROM "user" WHERE "userId" = $1`,
        [targetId],
      );
      if (!target.length) return fail(event, 404, 'not_found', 'User does not exist');

      if (target[0].user_type === 'SUPERADMIN' && caller.user_type !== 'SUPERADMIN') {
        return fail(event, 403, 'superadmin_protected', 'SUPERADMIN accounts cannot be modified');
      }

      const next = action === 'activate' ? 'ACTIVE' : 'INACTIVE';
      const stamp = makeStamp(action === 'activate' ? 'REACTIVATED' : 'DEACTIVATED', caller.userId);

      await withTx(async (q) => {
        await q(`SET LOCAL search_path = hopedb, public`);
        await q(`SET LOCAL hopepms.caller_userid = $1`, [caller.userId]);
        await q(
          `UPDATE "user" SET record_status = $1, stamp = $2 WHERE "userId" = $3`,
          [next, stamp, targetId],
        );
      });

      return ok(event, { ok: true });
    }

    return fail(event, 404, 'not_found', `Unknown route ${path}`);
  } catch (err) {
    if (err instanceof ValidationError) return fail(event, 400, 'validation', err.message);
    const status = (err as { status?: number }).status;
    if (status) return fail(event, status, (err as { code?: string }).code ?? 'error', (err as Error).message);
    console.error('[admin-users] error', err);
    return fail(event, 500, 'server_error', 'Unexpected error');
  }
}
