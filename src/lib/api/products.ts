import { request } from './client';
import {
  matchOrThrow,
  PATTERNS,
  safeText,
} from '@lib/security/pathGuard';

export interface Product {
  prodCode: string;
  description: string;
  unit: 'pc' | 'ea' | 'mtr' | 'pkg' | 'ltr';
  record_status: 'ACTIVE' | 'INACTIVE';
  stamp?: string;
  currentPrice?: number;
  effDate?: string;
}

export interface PriceHistoryEntry {
  effDate: string;
  prodCode: string;
  unitPrice: number;
  stamp?: string;
}

export interface ProductInput {
  prodCode: string;
  description: string;
  unit: Product['unit'];
  unitPrice: number;
}

function validateInput(input: ProductInput): ProductInput {
  return {
    prodCode: matchOrThrow(input.prodCode, PATTERNS.prodCode, 'prodCode'),
    description: safeText(input.description, 'description', 30),
    unit: matchOrThrow(input.unit, PATTERNS.unit, 'unit') as Product['unit'],
    unitPrice: validatePrice(input.unitPrice),
  };
}

function validatePrice(value: unknown): number {
  const n = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(n) || n <= 0 || n > 9_999_999.99) {
    throw new Error('unitPrice must be a positive number ≤ 9,999,999.99');
  }
  return Math.round(n * 100) / 100;
}

export async function listProducts(opts: { includeInactive?: boolean } = {}) {
  const qs = opts.includeInactive ? '?include=inactive' : '';
  return request<Product[]>(`/products${qs}`);
}

export async function getProduct(prodCode: string) {
  matchOrThrow(prodCode, PATTERNS.prodCode, 'prodCode');
  return request<Product>(`/products/${encodeURIComponent(prodCode)}`);
}

export async function getPriceHistory(prodCode: string) {
  matchOrThrow(prodCode, PATTERNS.prodCode, 'prodCode');
  return request<PriceHistoryEntry[]>(
    `/products/${encodeURIComponent(prodCode)}/price-history`,
  );
}

export async function createProduct(input: ProductInput) {
  return request<Product>('/products', { method: 'POST', body: validateInput(input) });
}

export async function updateProduct(input: ProductInput) {
  const v = validateInput(input);
  return request<Product>(`/products/${encodeURIComponent(v.prodCode)}`, {
    method: 'PATCH',
    body: v,
  });
}

export async function softDeleteProduct(prodCode: string) {
  matchOrThrow(prodCode, PATTERNS.prodCode, 'prodCode');
  // Backend PatchRequest binds `recordStatus` (camelCase); sending
  // `record_status` left it null and silently fell through to the edit path.
  return request<{ ok: true }>(`/products/${encodeURIComponent(prodCode)}`, {
    method: 'PATCH',
    body: { recordStatus: 'INACTIVE' },
  });
}

export async function recoverProduct(prodCode: string) {
  matchOrThrow(prodCode, PATTERNS.prodCode, 'prodCode');
  return request<{ ok: true }>(`/products/${encodeURIComponent(prodCode)}`, {
    method: 'PATCH',
    body: { recordStatus: 'ACTIVE' },
  });
}
