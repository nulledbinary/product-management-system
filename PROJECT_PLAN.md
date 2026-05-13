# Hope, Inc. Product Management System (HopePMS)
## Development & Implementation Plan (6-Week Capstone)

This document outlines the 6-week execution strategy for the **HopePMS** project. It incorporates the revised requirements including soft-deletes, multi-tier rights management, and Google OAuth integration.

---

## 1. Project Overview
The **HopePMS** is a secure, role-aware web application for managing product catalogs and pricing history. All data operations are governed by the **Rights Management** schema and restricted by **Supabase Row Level Security (RLS)**.

### Core Mandates
- 🔒 **Zero Hard Deletes:** No `DELETE` statements allowed. All removals use `record_status = 'INACTIVE'`.
- 🔒 **User Isolation:** `INACTIVE` records are invisible to standard `USER` accounts.
- 🔒 **SUPERADMIN Protection:** `ADMIN` accounts cannot modify or deactivate `SUPERADMIN` records.
- 🔒 **Audit Trail:** Every write operation must generate a stamp: `[ACTION] [USERID] [YYYY-MM-DD] [HH:MM]`.

---

## 2. Technology Stack
| Layer | Technology | Purpose |
| :--- | :--- | :--- |
| **Frontend** | React 18 + Vite | Component-based UI and build tool. |
| **Styling** | Tailwind CSS | Utility-first responsive design. |
| **Backend/DB** | Supabase (PostgreSQL) | Database, Auth, RLS Policies, and Triggers. |
| **Auth** | Supabase Auth | Email/Password and Google OAuth 2.0. |
| **State** | React Context API | Global Auth and Rights management. |
| **Deployment** | Vercel / Netlify | Continuous Deployment. |

---

## 3. Sprint Breakdown

### Sprint 1: Setup, Database & Authentication (Weeks 1–2)
**Goal:** Initialize environments, establish the schema, and secure the login/registration pipeline.

- [ ] **Infrastructure:** Scaffold Vite/React project, configure Tailwind CSS, and set up GitHub branching (main/dev).
- [ ] **Database Setup:** Initialize Supabase with `HopeDB` and `Rights Management` tables.
- [ ] **Seed Data:** Manually seed the `SUPERADMIN` account (`jcesperanza@neu.edu.ph`).
- [ ] **Authentication:**
    - Implement Email/Password signup with email confirmation.
    - Configure Google OAuth in Google Cloud Console and Supabase.
    - Implement `/auth/callback` route for OAuth redirects.
- [ ] **Backend Triggers:** Deploy the `provision_new_user()` PostgreSQL function to auto-assign `USER` rights and `INACTIVE` status to new signups.
- [ ] **Login Guard:** Implement logic to block `INACTIVE` accounts from accessing the dashboard.

### Sprint 2: CRUD, Rights & Soft-Delete Enforcement (Weeks 3–4)
**Goal:** Build the core Product management UI with strict visibility and rights gating.

- [ ] **Product Module:** Build Product List, Add Product Modal, and Edit Product Modal.
- [ ] **Visibility Rules:** Ensure standard `USER` accounts only query `record_status = 'ACTIVE'`.
- [ ] **Soft-Delete Implementation:**
    - "Delete" button updates `record_status` to `INACTIVE`.
    - Create `Deleted Items` panel (ADMIN/SUPERADMIN only) for record recovery.
- [ ] **Rights Gating:** - Create `UserRightsContext` and `useRights()` hook.
    - Hide `Add/Edit/Delete` buttons based on `UserModule_Rights`.
    - Hide `Stamp` columns from `USER` type accounts.
- [ ] **Security:** Apply RLS policies to the `product` and `priceHist` tables to enforce visibility at the database level.

### Sprint 3: Reports, Admin & Deployment (Weeks 5–6)
**Goal:** Implement analytics, user management, and go live.

- [ ] **Reports Module:** - `REP_001`: Product Listing with current price.
    - `REP_002`: Top Selling Products (JOIN query).
- [ ] **Admin Module:** - Build User Management table.
    - Implement Activate/Deactivate functionality.
    - **SUPERADMIN Guard:** Ensure all action buttons are disabled for `SUPERADMIN` rows.
- [ ] **Testing:** Execute the 18-case rights matrix. Verify no `DELETE` keywords exist in the codebase.
- [ ] **Deployment:** Deploy to Vercel/Netlify with environment variables.
- [ ] **Finalization:** Generate User Manual and Sprint Log.

---

## 4. Rights Matrix Summary
| Feature | SUPERADMIN | ADMIN | USER |
| :--- | :---: | :---: | :---: |
| **Add Product** | YES | YES | YES |
| **Edit Product** | YES | YES | YES |
| **Soft Delete** | YES | NO | NO |
| **Product Report** | YES | YES | YES |
| **Top Selling Report** | YES | NO | NO |
| **Manage Users** | YES | NO | NO |
| **View Stamps** | YES | YES* | NO |
*\*ADMIN can only see stamps on product/priceHist tables.*

---

## 5. Definition of Done
1. [ ] Supabase RLS policies active and verified.
2. [ ] Google OAuth and Email signup both trigger auto-provisioning.
3. [ ] `INACTIVE` records are unreachable by standard `USER` accounts.
4. [ ] `ADMIN` cannot modify `SUPERADMIN` accounts (Frontend + DB layers).
5. [ ] Stamp format: `ACTION USERID YYYY-MM-DD HH:MM`.
6. [ ] Live URL accessible with all three user roles functional.
