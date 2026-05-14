import { request } from './client';
import { matchOrThrow, PATTERNS } from '@lib/security/pathGuard';

export interface UserRow {
  userId: string;
  username: string;
  firstName: string;
  lastName: string;
  user_type: 'SUPERADMIN' | 'ADMIN' | 'USER';
  record_status: 'ACTIVE' | 'INACTIVE';
  stamp?: string;
  email?: string;
}

export async function listUsers() {
  return request<UserRow[]>('/admin/users');
}

export async function activateUser(userId: string) {
  matchOrThrow(userId, /^[A-Za-z0-9|_-]{1,64}$/, 'userId');
  return request<{ ok: true }>(`/admin/users/${encodeURIComponent(userId)}/activate`, {
    method: 'POST',
  });
}

export async function deactivateUser(userId: string) {
  matchOrThrow(userId, /^[A-Za-z0-9|_-]{1,64}$/, 'userId');
  return request<{ ok: true }>(`/admin/users/${encodeURIComponent(userId)}/deactivate`, {
    method: 'POST',
  });
}

export async function getTopSelling(limit = 10) {
  const n = Math.max(1, Math.min(100, Number(limit) | 0));
  return request<Array<{ prodCode: string; description: string; totalQty: number }>>(
    `/reports/top-selling?limit=${n}`,
  );
}

export async function getProductReport() {
  return request<
    Array<{ prodCode: string; description: string; unit: string; unitPrice: number; effDate: string }>
  >('/reports/product-listing');
}
