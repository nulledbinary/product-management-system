/**
 * Standard API Gateway → Lambda HTTP envelope helpers.
 * - Consistent JSON responses with security headers.
 * - Locks down CORS to the configured frontend origin only.
 */
import type { APIGatewayProxyEventV2, APIGatewayProxyStructuredResultV2 } from 'aws-lambda';

const SECURITY_HEADERS = {
  'Cache-Control': 'no-store',
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'strict-origin-when-cross-origin',
  'Strict-Transport-Security': 'max-age=63072000; includeSubDomains; preload',
};

function cors(event: APIGatewayProxyEventV2): Record<string, string> {
  const allowed = (process.env.CORS_ALLOWED_ORIGIN ?? '').split(',').map((s) => s.trim()).filter(Boolean);
  const origin = event.headers?.origin ?? event.headers?.Origin ?? '';
  const ok = allowed.includes(origin);
  return {
    'Access-Control-Allow-Origin': ok ? origin : 'null',
    'Access-Control-Allow-Methods': 'GET,POST,PATCH,OPTIONS',
    'Access-Control-Allow-Headers': 'Authorization,Content-Type',
    'Access-Control-Max-Age': '600',
    Vary: 'Origin',
  };
}

export function ok<T>(event: APIGatewayProxyEventV2, body: T, status = 200): APIGatewayProxyStructuredResultV2 {
  return {
    statusCode: status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', ...SECURITY_HEADERS, ...cors(event) },
    body: JSON.stringify(body),
  };
}

export function fail(
  event: APIGatewayProxyEventV2,
  status: number,
  code: string,
  message: string,
): APIGatewayProxyStructuredResultV2 {
  return {
    statusCode: status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', ...SECURITY_HEADERS, ...cors(event) },
    body: JSON.stringify({ code, message }),
  };
}

export function parseJson<T>(event: APIGatewayProxyEventV2): T {
  if (!event.body) return {} as T;
  const raw = event.isBase64Encoded
    ? Buffer.from(event.body, 'base64').toString('utf8')
    : event.body;
  try {
    return JSON.parse(raw) as T;
  } catch {
    throw new HttpError(400, 'invalid_json', 'Request body is not valid JSON');
  }
}

export class HttpError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}
