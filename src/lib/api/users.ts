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

/**
 * Permanently delete (off-board) an account. Stamps are scrubbed server-side
 * and the V9-guarded hard delete is what actually removes the row — this is
 * the call that previously surfaced "Unexpected Error" before V9.
 */
export async function deleteUser(userId: string) {
  matchOrThrow(userId, USER_ID_RE, 'userId');
  return request<{ ok: true; eradicated: true }>(
    `/admin/users/${encodeURIComponent(userId)}`,
    { method: 'DELETE' },
  );
}

/** Owner-exclusive SUPERADMIN roster (403 for everyone but the owner). */
export async function listSuperadmins() {
  return request<UserRow[]>('/admin/users/superadmins');
}

export interface AdminLogEntry {
  id: number;
  at: string;
  actorId: string | null;
  actorName: string | null;
  actorEmail: string | null;
  action: string;
  target: string | null;
  detail: string | null;
}

/** Administrative activity log — Admin section only (ADM_USER). */
export async function getAdminLogs(limit = 200) {
  const n = Math.max(1, Math.min(500, Number(limit) | 0));
  return request<AdminLogEntry[]>(`/admin/logs?limit=${n}`);
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
