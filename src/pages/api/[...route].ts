/**
 * Dev-time proxy that runs the Lambda handlers in the Astro Node SSR server.
 *
 * In production, API Gateway maps each route to its own Lambda. Locally we
 * shape the inbound Astro request into the API Gateway v2 event envelope
 * and dispatch to the matching handler — this lets `npm run dev` exercise
 * the *real* handler code, including all auth + RLS + validation paths.
 */
import type { APIRoute } from 'astro';
import type { APIGatewayProxyEventV2, APIGatewayProxyStructuredResultV2 } from 'aws-lambda';

import { handler as meHandler } from '../../../server/handlers/me';
import { handler as productsHandler } from '../../../server/handlers/products';
import { handler as adminUsersHandler } from '../../../server/handlers/admin-users';
import { handler as reportsHandler } from '../../../server/handlers/reports';

export const prerender = false;

type HandlerFn = (e: APIGatewayProxyEventV2) => Promise<APIGatewayProxyStructuredResultV2>;

function pickHandler(path: string): HandlerFn | null {
  if (path === '/api/me') return meHandler;
  if (path === '/api/admin/users' || /^\/api\/admin\/users\/[^/]+\/(activate|deactivate)\/?$/.test(path)) {
    return adminUsersHandler;
  }
  if (/^\/api\/reports\//.test(path)) return reportsHandler;
  if (/^\/api\/products(\/|$)/.test(path)) return productsHandler;
  return null;
}

async function toEvent(request: Request, url: URL): Promise<APIGatewayProxyEventV2> {
  const body = ['GET', 'HEAD'].includes(request.method) ? undefined : await request.text();
  const headers: Record<string, string> = {};
  request.headers.forEach((v, k) => { headers[k.toLowerCase()] = v; });
  const qs: Record<string, string> = {};
  url.searchParams.forEach((v, k) => { qs[k] = v; });

  return {
    version: '2.0',
    routeKey: '$default',
    rawPath: url.pathname,
    rawQueryString: url.searchParams.toString(),
    headers,
    queryStringParameters: Object.keys(qs).length ? qs : undefined,
    requestContext: {
      accountId: 'local',
      apiId: 'local',
      domainName: url.hostname,
      domainPrefix: 'local',
      http: {
        method: request.method,
        path: url.pathname,
        protocol: 'HTTP/1.1',
        sourceIp: '127.0.0.1',
        userAgent: headers['user-agent'] ?? '',
      },
      requestId: crypto.randomUUID(),
      routeKey: '$default',
      stage: '$default',
      time: new Date().toUTCString(),
      timeEpoch: Date.now(),
    },
    body,
    isBase64Encoded: false,
  };
}

const all: APIRoute = async ({ request }) => {
  const url = new URL(request.url);
  const handler = pickHandler(url.pathname);
  if (!handler) {
    return new Response(JSON.stringify({ code: 'not_found', message: `No handler for ${url.pathname}` }), {
      status: 404,
      headers: { 'Content-Type': 'application/json' },
    });
  }
  const event = await toEvent(request, url);
  const result = await handler(event);
  const headers = new Headers(result.headers as Record<string, string> | undefined);
  return new Response(result.body ?? '', { status: result.statusCode ?? 200, headers });
};

export const GET = all;
export const POST = all;
export const PATCH = all;
export const DELETE = all;
export const OPTIONS = all;
