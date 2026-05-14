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
| **Frontend** | Astro & AWS Amplify | High-performance UI with Server-Side Rendering (SSR). Hosted on AWS Amplify. |
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

## **✅ Definition of Done**

* \[ \] No DELETE statements exist in the codebase.  
* \[ \] Soft-deleted records are invisible to standard **USER** accounts.  
* \[ \] **SUPERADMIN** protection is enforced at both UI and API levels.  
* \[ \] Audit stamps are correctly generated for every write action.  
* \[ \] Live production URL is functional and secure.

---
**Prepared by:** Boris Gamaliel D. Duque & Yna Solitario

**Instructor:** Jeremias C. Esperanza

**Institution:** New Era University – College of Computer Studies

**Course:** Software Engineering 2