/**
 * Postgres connection pool. The Lambda container reuses the pool across
 * warm invocations — do NOT close it per-handler.
 *
 * SSL is on by default; RDS rejects non-TLS connections in our prod config.
 */
import { Pool, type PoolConfig } from 'pg';

let pool: Pool | null = null;

function config(): PoolConfig {
  const ssl = String(process.env.PG_SSL ?? 'true').toLowerCase() === 'true';
  return {
    host: required('PG_HOST'),
    port: Number(process.env.PG_PORT ?? 5432),
    database: required('PG_DATABASE'),
    user: required('PG_USER'),
    password: required('PG_PASSWORD'),
    ssl: ssl ? { rejectUnauthorized: false } : undefined,
    max: 5,
    idleTimeoutMillis: 30_000,
    connectionTimeoutMillis: 5_000,
    statement_timeout: 8_000,
    query_timeout: 8_000,
  };
}

function required(name: string): string {
  const v = process.env[name];
  if (!v) throw new Error(`Missing env: ${name}`);
  return v;
}

export function getPool(): Pool {
  if (!pool) {
    pool = new Pool(config());
    pool.on('error', (err) => {
      // Logged but not fatal — pg will reconnect on next acquire.
      console.error('[pg] pool error', err);
    });
  }
  return pool;
}

/**
 * Run a callback within a transaction. The caller receives a per-query
 * helper that **always** uses parameterised statements — there is no
 * code path here that concatenates user input into SQL.
 */
export async function withTx<T>(
  fn: (q: <R>(text: string, params?: unknown[]) => Promise<R[]>) => Promise<T>,
): Promise<T> {
  const client = await getPool().connect();
  try {
    await client.query('BEGIN');
    const q = async <R>(text: string, params?: unknown[]): Promise<R[]> => {
      const r = await client.query(text, params);
      return r.rows as R[];
    };
    const result = await fn(q);
    await client.query('COMMIT');
    return result;
  } catch (err) {
    await client.query('ROLLBACK').catch(() => {});
    throw err;
  } finally {
    client.release();
  }
}

export async function query<R>(text: string, params: unknown[] = []): Promise<R[]> {
  const r = await getPool().query(text, params);
  return r.rows as R[];
}
