/**
 * GET /api/me
 *
 * Resolves the Auth0-authenticated identity into our application user
 * record (user_type + rights). Also runs the just-in-time provisioning
 * for first-time Google OAuth callers, mirroring the docx §4.4 trigger
 * behaviour.
 *
 * 403 if the record exists but is INACTIVE — the client routes the user
 * to /login?reason=not_activated.
 */
import type { APIGatewayProxyEventV2, APIGatewayProxyStructuredResultV2 } from 'aws-lambda';
import { fail, ok } from '../lib/http';
import { verifyAuthHeader } from '../lib/auth';
import { query, withTx } from '../db/pool';
import { loadAppUser } from '../lib/rights';
import { makeStamp } from '../lib/stamp';

export async function handler(event: APIGatewayProxyEventV2): Promise<APIGatewayProxyStructuredResultV2> {
  try {
    const auth = await verifyAuthHeader(event.headers?.authorization ?? event.headers?.Authorization);
    let user = await loadAppUser(auth.sub);

    if (!user) {
      // Just-in-time provision: create USER/INACTIVE row + default rights.
      const usernameSeed = (auth.email ?? auth.sub).split('@')[0].replace(/[^a-zA-Z0-9._-]/g, '_').slice(0, 32) || 'user';
      const stamp = makeStamp('REGISTERED', auth.sub);
      await withTx(async (q) => {
        await q(`SET LOCAL search_path = hopedb, public`);
        await q(
          `INSERT INTO "user" ("userId", username, "firstName", "lastName", email, user_type, record_status, stamp)
           VALUES ($1, $2, '', '', $3, 'USER', 'INACTIVE', $4)
           ON CONFLICT ("userId") DO NOTHING`,
          [auth.sub, usernameSeed, auth.email ?? `${auth.sub}@unknown`, stamp],
        );
        await q(
          `INSERT INTO user_module (userid, "Module_ID", rights_value, record_status, stamp)
           VALUES ($1,'Prod_Mod',1,'ACTIVE','AUTO'),
                  ($1,'Report_Mod',1,'ACTIVE','AUTO'),
                  ($1,'Adm_Mod',0,'ACTIVE','AUTO')
           ON CONFLICT DO NOTHING`,
          [auth.sub],
        );
        await q(
          `INSERT INTO "UserModule_Rights" (userid, "Right_ID", "Right_value", "Record_status", "Stamp") VALUES
             ($1,'PRD_ADD',1,'ACTIVE','AUTO'),
             ($1,'PRD_EDIT',1,'ACTIVE','AUTO'),
             ($1,'PRD_DEL',0,'ACTIVE','AUTO'),
             ($1,'REP_001',1,'ACTIVE','AUTO'),
             ($1,'REP_002',0,'ACTIVE','AUTO'),
             ($1,'ADM_USER',0,'ACTIVE','AUTO')
           ON CONFLICT DO NOTHING`,
          [auth.sub],
        );
      });
      user = await loadAppUser(auth.sub);
    }

    if (!user) return fail(event, 500, 'provision_failed', 'Could not provision user');
    if (user.record_status !== 'ACTIVE') {
      return fail(event, 403, 'not_activated', 'Account is pending admin activation');
    }

    return ok(event, {
      user_id: user.userId,
      username: user.username,
      user_type: user.user_type,
      rights: user.rights,
    });
  } catch (err) {
    const msg = err instanceof Error ? err.message : 'unknown';
    if (['missing_bearer', 'malformed_token', 'bad_signature', 'expired'].includes(msg)) {
      return fail(event, 401, 'unauthenticated', msg);
    }
    console.error('[me] error', err);
    return fail(event, 500, 'server_error', 'Unexpected error');
  }
}
