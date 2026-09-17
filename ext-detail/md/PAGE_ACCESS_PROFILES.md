# Access Profiles — which pages a tenant user can open

## Date: September 17, 2026
## Projects: `process` (Spring Boot) + `scheduler1/next` (Angular console)

---

## 🎯 What it is

Roles (`PLATFORM_ADMIN` > `TENANT_ADMIN` > `TENANT_USER`) say how much somebody may **do**.
An **access profile** says which console pages a **tenant user** may **open** at all.

A profile is a named bundle of pages owned by one workspace — "Operator", "Analyst",
"Compliance" — and each tenant user holds one. Onboarding somebody is "give them Analyst",
and changing what an Analyst sees is one edit, not one per person.

```
Tenant admin  →  Administration › Access profiles   (make the bundles, pick the default)
Tenant admin  →  Administration › Users › Edit      (put a person on a bundle)
Tenant user   →  menu shrinks, guarded routes redirect, API behind withheld pages returns 403
```

### The pages a profile can grant (fixed catalogue, `PageKey` enum)

| Key | Page | Menu section |
|---|---|---|
| `jobs` | Source Jobs | Pipelines |
| `tasks` | Source Tasks | Pipelines |
| `queue` | Queue | Pipelines |
| `reports` | Reports | Pipelines |
| `objects` | Browse files | Object Browser |
| `analytics` | Analytics Studio | Object Browser |
| `analytics-dashboards` | Saved Analyses | Object Browser |
| `tools-converter` | Document Converter | Tools |
| `tools-transcript` | Audio Transcript | Tools |
| `ai-agents` | AI Agents | Assistants |

**Never withheld:** Dashboard, Profile, Notifications. **Never listed:** Configuration and
Administration pages — the roles already gate those.

---

## 🧭 How access resolves (one rule, in `PageAccessServiceImpl.effectivePages`)

```
1. PLATFORM_ADMIN or TENANT_ADMIN            → every page
2. app_user.page_access_profile_id set,
   active, and in the user's own tenant      → that profile's pages   ┐
3. else the tenant's default profile exists  → the default's pages    ├ the baseline
4. else                                      → every page             ┘
5. then the person's exceptions (user_page_access): each allowed=true row opens a page,
   each allowed=false row withholds one
```

Rule 4 is what makes the feature opt-in: a workspace that never opened the screen behaves
exactly as before. Once a workspace has a default, anyone unassigned gets the default.

An empty profile is a real answer (dashboard only); a deactivated profile or an unknown page
key reads as "not there".

---

## 🗄️ Database (Liquibase `V40.0-page-access-profiles`)

```
page_access_profile
  page_access_profile_id  BIGINT PK (sequence page_access_profile_Seq from 1000)
  tenant_id               BIGINT NOT NULL  → tenant
  profile_name            VARCHAR(100) NOT NULL   UNIQUE (tenant_id, profile_name)
  description             VARCHAR(500)
  is_default              BOOLEAN NOT NULL        partial UNIQUE (tenant_id) WHERE is_default
  status                  VARCHAR(20)  'Active' | 'Delete'  (soft delete)
  date_created, date_updated, created_by, updated_by

page_access_profile_page
  page_access_profile_id  BIGINT → page_access_profile ON DELETE CASCADE
  page_key                VARCHAR(64)           PK (profile, key)

app_user
  + page_access_profile_id BIGINT NULL → page_access_profile ON DELETE SET NULL

user_page_access  (Liquibase V41 — per-person exceptions)
  app_user_id  BIGINT → app_user ON DELETE CASCADE
  page_key     VARCHAR(64)          PK (user, key)
  allowed      BOOLEAN              true opens the page beyond the profile, false withholds it despite the profile
  date_created, created_by
```
Only real differences are stored: setting a page to what the profile already says deletes the
row, so "reset to profile" is a delete.

Rollback is in the changeset. No platform-wide profile exists on purpose: a bundle shared
across tenants would let one workspace's admin change what another's people see.

---

## 🔌 API — `/api/v1/pageAccess.json`

| Method | Path | Who | What |
|---|---|---|---|
| GET | `/pages` | signed in | The catalogue (key, label, section, route) |
| GET | `/mine` | signed in | Caller's effective `pageKeys` + profile name |
| POST | `/requestAccess?pageKey=` | signed in | Notifies every active tenant admin (`PAGE_ACCESS_REQUESTED`) |
| GET | `/listProfiles` | TENANT_ADMIN | The workspace's profiles with holder counts and names |
| POST | `/addProfile` | TENANT_ADMIN | Body: `{profileName, description, defaultProfile, pageKeys[]}`. The first profile becomes the default |
| PUT | `/updateProfile` | TENANT_ADMIN | Same body + `pageAccessProfileId`. Changed pages notify holders (`PAGE_ACCESS_CHANGED`) |
| DELETE | `/deleteProfile?pageAccessProfileId=` | TENANT_ADMIN | Refused while anyone holds it |
| PUT | `/setDefaultProfile?pageAccessProfileId=` | TENANT_ADMIN | Moves the default |
| GET | `/listPeople[?tenantId=]` | TENANT_ADMIN | The workspace's tenant users with profile and effective `pageKeys` — the grid's rows |
| PUT | `/assignProfile?appUserId=&pageAccessProfileId=` | TENANT_ADMIN | One person onto one profile (omit the profile for the default). Notifies the person |
| PUT | `/setPageAccess?appUserId=&pageKey=&allowed=` | TENANT_ADMIN | One checkbox: open/withhold a page for a person as an exception; back to the profile's answer clears it. Notifies the person |
| DELETE | `/clearPageAccess?appUserId=` | TENANT_ADMIN | Drops every exception the person carries |

`tenantId` is how a **platform admin** names the workspace (it has none of its own); a tenant admin's
workspace is always its own, whatever id it sends.

Also changed:
- `auth.json/login` and `/refresh` now return `pageKeys[]` and `pageAccessProfileName`.
- `appUser.json/addUser` / `updateUser` accept `pageAccessProfileId` (tenant users only; validated
  against the target workspace). The user list returns `pageAccessProfileId` + `pageAccessProfileName`.
  Promoting a user out of TENANT_USER clears the pointer.

A platform admin manages any workspace by naming it (`tenantId`); the console shows it a workspace
picker. A tenant admin never sees the picker.

---

## 🛡️ Server-side enforcement (`PageAccessInterceptor`)

Hiding a menu item is courtesy, not security. Every request under a gated API group is checked
for a `TENANT_USER`; the answer is cached per person for 15 s (`PageAccessCache`) and dropped the
moment a profile or an assignment changes. Refusal is a **403** with the usual envelope:

```json
{"status":"ERROR","message":"Reports is not part of your access. Ask your workspace admin."}
```

A group is unlocked by **any** page that names it (several are shared):

| API prefix | Unlocked by |
|---|---|
| `/sourceJob.json` | jobs *(except `/myActivity`, always open — the profile screen's own stats)* |
| `/sourceTask.json` | jobs, tasks |
| `/message.json` | queue, reports |
| `/report.json` | reports |
| `/analytics*.json` (all six) | analytics, analytics-dashboards |
| `/documentConverter.json` | tools-converter |
| `/audioTranscript.json` | tools-transcript |
| `/aiAgent.json` | ai-agents, jobs, objects |
| `/fileChat.json` | objects, jobs |
| `/fileShare.json` | objects |

**Deliberately not gated:** `/storage.json` — the object browser uses it, but so do profile
pictures, task forms and analytics; a user with no Browse files page must still upload their own
picture. `/dashboard.json`, `/appUser.json`, `/notification.json` and everything admin-only are
untouched.

---

## 🖥️ Console

- `AuthService.canOpen(pageKey)` — admins always true; a session with no `pageKeys` (stored
  before this feature) reads as unrestricted until the next sign-in/refresh.
- `pageGuard` on every gated route (`data.pageKey`) → `/unauthorized?page=<key>`.
- The menu drops withheld pages, and a whole section when nothing in it is left.
- `/unauthorized` names the page and offers **Request access**.
- `Administration › Access profiles` — two views of the same facts:
  - **Profiles**: cards (pages opened / withheld, holders), New/Edit dialog with pages grouped by
    section, Make default, Delete.
  - **People × pages**: a grid — people down the side, pages across the top (grouped under their
    menu section), rows grouped under the profile they share. The profile's own row shows its
    pattern; every person's cell is a **checkbox** that means what it says: tick it and that page
    opens for that person (`setPageAccess`). A tick that differs from the profile is an exception,
    shown with an orange marker and a tinted cell; "n exceptions · reset to profile" clears them
    (`clearPageAccess`). Each row also has a profile picker (`assignProfile`) and there is a
    find-a-person filter.
- User dialog — **Access profile** picker for tenant users (shown once the workspace has profiles);
  the Users list shows the profile under the role.

---

## ✅ Tests

| Layer | Where | Covers |
|---|---|---|
| Backend | `PageAccessResolutionTest` | the four resolution rules, cross-tenant pointer, empty profile |
| Backend | `PageAccessProfileScopeTest` | first-is-default, duplicate names, unknown keys, tenant isolation, refused delete, holder notifications, access requests |
| Backend | `PageAccessInterceptorTest` | 403 shape, shared groups, always-open path, admins untouched, cache invalidation |
| Backend | `PageKeyTest` | catalogue lookups and prefix matching |
| Console | `core/auth/page-access.spec.ts` | `canOpen`, `pageGuard`, catalogue |
| Console | `features/shell/shell.spec.ts` | menu tagging and filtering |
| Console | `features/admin/access-profiles/access-profiles.spec.ts` | dialog + screen |
| Console | `features/admin/users/user-management-scope.spec.ts` | the picker |
| End to end | `e2e/access-profiles.spec.ts` | admin creates + assigns in the UI → member's menu, redirect, request, API 403, admin's bell |

Run the end-to-end one against the running stack with two accounts from one workspace:

```bash
E2E_TENANT_ADMIN=daniel.carter@carebridgehealth.demo E2E_TENANT_ADMIN_PASSWORD=… \
E2E_TENANT_USER=olivia.bennett@carebridgehealth.demo   E2E_TENANT_USER_PASSWORD=… \
npx playwright test e2e/access-profiles.spec.ts
```

The member must have a settled password (not a one-time one), or the console holds them on
`/profile`. The test restores the member's profile and deletes the one it made.

---

## 📝 Notes

- Notifications: `PAGE_ACCESS_CHANGED` to the person (profile swapped, or its pages edited);
  `PAGE_ACCESS_REQUESTED` to every active tenant admin when somebody presses Request access.
- The Angular catalogue (`core/auth/page-keys.ts`) mirrors the enum for the guard and menu; the
  profile editor fetches `/pages` so it offers exactly what the server accepts. Add a page in
  both places.
- Local demo state: CareBridge has one profile, **Operator** (jobs, tasks, queue, objects), the
  default; Olivia Bennett holds it.
