# **Hope, Inc. Product Management System (HopePMS)**

### **Astro & AWS Cloud-Native Edition**

The **Hope, Inc. Product Management System (HopePMS)** is a secure, role-aware web application designed for managing product data and price histories within the HopeDB schema. This implementation leverages a modern serverless architecture to meet strict security and data integrity mandates.

## **🚀 Project Overview**

The system allows authorized users to manage product records with access dynamically controlled by a Rights Management schema. The architecture is built to ensure high performance and enterprise-grade security.

### **🔒 Core Security Mandates**

* **Zero Hard Deletes:** The application must **NEVER** issue a DELETE SQL statement.  
* **Soft-Delete Logic:** All removals are handled by setting record\_status \= 'INACTIVE'.  
* **Visibility Isolation:** INACTIVE records are strictly invisible to standard **USER** accounts.  
* **SUPERADMIN Protection:** **ADMIN** accounts are mechanically restricted from altering the rights, user type, or status of **SUPERADMIN** accounts.  
* **Audit Trail:** Every write operation generates a "stamp" string (e.g., ADDED user2 2026-05-13 20:00). Audit columns are hidden from **USER** accounts.

## **🛠️ Technology Stack**

| Layer | Technology | Purpose |
| :---- | :---- | :---- |
| **Frontend** | Astro | High-performance UI with Server-Side Rendering (SSR). |
| **Authentication** | Auth0 | Identity management, RBAC, and Google OAuth 2.0. |
| **Backend API** | AWS Lambda | Serverless business logic and CRUD operations. |
| **Database** | Amazon RDS (PostgreSQL) | Managed relational database for HopeDB. |
| **Gateway** | AWS API Gateway | Secure RESTful API entry point. |

## **👥 User Types & Rights Matrix**

| Right / Feature | SUPERADMIN | ADMIN | USER | Module |
| :---- | :---- | :---- | :---- | :---- |
| **PRD\_ADD** (Add Product) | ✔ YES | ✔ YES | ✔ YES | Prod\_Mod |
| **PRD\_EDIT** (Edit Product) | ✔ YES | ✔ YES | ✔ YES | Prod\_Mod |
| **PRD\_DEL** (Soft Delete) | ✔ YES | ✘ NO | ✘ NO | Prod\_Mod |
| **REP\_001** (Product Report) | ✔ YES | ✔ YES | ✔ YES | Report\_Mod |
| **REP\_002** (Top Selling) | ✔ YES | ✘ NO | ✘ NO | Report\_Mod |
| **ADM\_USER** (Manage Users) | ✔ YES | ✘ NO | ✘ NO | Adm\_Mod |

## **📅 6-Week Sprint Plan**

### **Sprint 1: Weeks 1–2 (Infrastructure & Identity)**

* Scaffold Astro project and configure Auth0 for Google OAuth and Email sign-in.  
* Initialize AWS RDS with HopeDB schema and seed the **SUPERADMIN** account.  
* Implement Auth0 Actions for auto-provisioning new users as USER / INACTIVE.

### **Sprint 2: Weeks 3–4 (CRUD & Visibility)**

* Develop AWS Lambda functions for Product management with soft-delete enforcement.  
* Apply visibility filters in API queries to ensure USER roles only see ACTIVE records.  
* Build the Deleted Items recovery interface (ADMIN/SUPERADMIN only).

### **Sprint 3: Weeks 5–6 (Reports & Delivery)**

* Implement Reporting Lambdas (REP\_001 and REP\_002) using PostgreSQL views.  
* Build the Admin User Management module with **SUPERADMIN** protection logic.  
* Deploy Astro frontend to AWS (S3/CloudFront) and finalize production environment.

## **✅ Definition of Done**

* \[ \] No DELETE statements exist in the codebase.  
* \[ \] Soft-deleted records are invisible to standard **USER** accounts.  
* \[ \] **SUPERADMIN** protection is enforced at both UI and API levels.  
* \[ \] Audit stamps are correctly generated for every write action.  
* \[ \] Live production URL is functional and secure.

**Prepared by:** Boris Gamaliel D. Duque & Yna Solitario

**Instructor:** Jeremias C. Esperanza

**Institution:** New Era University – College of Computer Studies

**Course:** Software Engineering 2