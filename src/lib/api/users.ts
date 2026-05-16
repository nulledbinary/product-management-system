import { request } from './client';
import { matchOrThrow, PATTERNS } from '@lib/security/pathGuard';

export type UserType = 'SUPERADMIN' | 'ADMIN' | 'USER';

export interface UserRow {
  userId: string;
  username: string;
  firstName: string;
  lastName: string;
  userType: UserType;
  recordStatus: 'ACTIVE' | 'INACTIVE';
  stamp?: string;
  email?: string;
}

export interface NewUserInput {
  username: string;
  firstName: string;
  lastName: string;
  email: string;
  userType: UserType;
}

const USER_ID_RE = /^[A-Za-z0-9|_.-]{1,64}$/;

export async function listUsers() {
  return request<UserRow[]>('/admin/users');
}

export async function createUser(input: NewUserInput) {
  matchOrThrow(input.username, PATTERNS.username, 'username');
  matchOrThrow(input.firstName, PATTERNS.name, 'firstName');
  matchOrThrow(input.lastName, PATTERNS.name, 'lastName');
  matchOrThrow(input.email, PATTERNS.email, 'email');
  matchOrThrow(input.userType, PATTERNS.userType, 'userType');
  return request<{ ok: true; userId: string; userType: UserType }>('/admin/users', {
    method: 'POST',
    body: input,
  });
}

export async function activateUser(userId: string) {
  matchOrThrow(userId, USER_ID_RE, 'userId');
  return request<{ ok: true }>(`/admin/users/${encodeURIComponent(userId)}/activate`, {
    method: 'POST',
  });
}

export async function deactivateUser(userId: string) {
  matchOrThrow(userId, USER_ID_RE, 'userId');
  return request<{ ok: true }>(`/admin/users/${encodeURIComponent(userId)}/deactivate`, {
    method: 'POST',
  });
}

export async function promoteUser(userId: string) {
  matchOrThrow(userId, USER_ID_RE, 'userId');
  return request<{ ok: true; userType: UserType }>(
    `/admin/users/${encodeURIComponent(userId)}/promote`,
    { method: 'POST' },
  );
}

export async function demoteUser(userId: string) {
  matchOrThrow(userId, USER_ID_RE, 'userId');
  return request<{ ok: true; userType: UserType }>(
    `/admin/users/${encodeURIComponent(userId)}/demote`,
    { method: 'POST' },
  );
}

/** Hard delete — off-boarding. Stamps are scrubbed server-side. */
export async function eradicateUser(userId: string) {
  matchOrThrow(userId, USER_ID_RE, 'userId');
  return request<{ ok: true; eradicated: true }>(
    `/admin/users/${encodeURIComponent(userId)}`,
    { method: 'DELETE' },
  );
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
