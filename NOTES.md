# Task Tracker – Fixes & Engineering Notes

## Executive Summary

The **Task Tracker** is a full-stack task management application comprising a Spring Boot 3.2 backend (Java 17, Spring Data JPA, H2 in-memory database) and a React 18 frontend built with Vite 5.

A comprehensive review identified core defects spanning the database, backend controller, query layer, and frontend client:
- Tasks appeared in reverse order (newest first) instead of natural chronological ID order.
- Status filtering leaked unselected task statuses due to broken SQL boolean precedence.
- Search queries returned empty results due to unsynchronized pagination offsets and missing input debouncing.
- Pagination was artificially throttled by a 1,000 ms backend sleep and in-memory list slicing, while page transitions abruptly unmounted the table and caused severe visual layout shifts.

A targeted, non-destructive patch was implemented across all layers. Database queries now handle filtering, search, and pagination deterministically in SQL; the backend artificial sleep was eliminated; and the frontend hook was stabilized with 250 ms debouncing, request cancellation (`AbortController`), and in-place row rendering. All functional features, automated integration tests, and production builds are fully verified.

---

## Fixes at a Glance

| # | Area | Issue | Root Cause | Solution | Verification |
|---|---|---|---|---|---|
| **1** | Ordering | Task IDs displayed descending (39, 38... 1) | SQL ordered by `created_at DESC` (non-unique timestamps) | Changed database sort to deterministic `id ASC` via `PageRequest` | Integration test verified IDs 1–10 on p.1, IDs 11–22 on p.2 |
| **2** | Performance | Next/Previous laggy (~1s) and table vanished | Artificial `Thread.sleep` (up to 1,000 ms) + in-memory list slicing + component unmounting | Removed sleep, switched to SQL `Pageable`, retained rows during fetch with `aria-busy` | Response time dropped from ~1s to <30ms; zero visual flickering |
| **3** | Filtering | Selecting "In Progress" returned OPEN and DONE tasks | Missing SQL parentheses: `AND` bound tighter than `OR`, causing title match to bypass status filter | Grouped search predicates in parentheses; normalized status values safely | Integration tests confirmed 100% of returned items match requested status |
| **4** | Search | "add api" returned "No tasks found" | Searching from page 2+ requested out-of-range offsets; race conditions on rapid keystrokes | Reset `page` to 1 on input change; added 250 ms debounce, request abortion, and case-insensitive JPQL search | Query "add api" returns ID 19; "POOL EXHAUSTION" returns ID 7 |
| **5** | Coordination | Combined search, filter, and pagination fell out of sync | Page offsets were not reset on criteria change; count queries were not aligned with filters | Linked criteria change handlers to reset page to 1; unified `countQuery` with data query | Multi-criteria combinations (e.g. `api` + `IN_PROGRESS`) return exact IDs 2 & 16 |
| **6** | Routing | Default URL verification | Potential unnecessary intermediate home/landing screen | Verified `http://localhost:5173/` directly renders `App.jsx` without redirects | Direct load verified in automated browser session |
| **7** | UX / Stability | Page shook/jumped vertically during search & filter | `useTasks.js` cleared `tasks` and `total` to 0 on every keystroke, unmounting table and pagination | Kept previous data during fetch (`keepPreviousData` pattern); stabilized feedback container | Smooth in-place transitions with zero Cumulative Layout Shift (CLS) |

---

## Detailed Issue Write-Ups

### 1. Task Ordering

**Problem**
Task IDs appeared in descending order (e.g., 39, 38, 37... down to 1), reversing the natural sequence and making logical progression across pagination pages backwards.

**Root Cause**
In `TaskRepository.java`, the original native query specified `ORDER BY created_at DESC`. Because seeded tasks have increasing timestamps corresponding to IDs, newest tasks appeared first. Furthermore, timestamps are not unique, resulting in non-deterministic tie-breaking for equal timestamps.

**Implementation**
- **Backend / JPA:** Updated `TaskRepository.java` to return a Spring Data `Page<Task>` with `PageRequest.of(page, pageSize, Sort.by(Sort.Direction.ASC, "id"))`.
- **Database Artifacts:** Updated both `search_tasks.sql` and the Oracle PL/SQL reference package (`task_search_package.sql`) to use `ORDER BY id ASC`.

**Why This Fix Works**
Sorting is executed directly inside the SQL database engine before `LIMIT` and `OFFSET` boundaries are applied. Sorting on the primary key `id` guarantees a unique, deterministic, ascending order (1, 2, 3...) across all pages.

**Verification**
Automated integration test `returnsStableAscendingPagesAndFilteredCount` confirmed:
- Page 1 returns IDs 1 through 10.
- Page 2 returns IDs 11 through 22 (archived tasks 20 and 21 are excluded).
- In-browser checks confirmed sequential ID display across all 5 pages.

**Assumption**
Ascending task ID (`id ASC`) represents the intended natural display order.

---

### 2. Pagination and Loading Experience

**Problem**
Clicking Next or Previous took noticeable time (~1 second), the entire table abruptly vanished into a full-page loading message, and rapid clicks triggered duplicate, overlapping requests.

**Root Cause**
1. **Artificial Backend Sleep:** `TaskController.java` calculated an artificial complexity score based on query length:
   ```java
   int complexityScore = Math.max(0, 10 - query.length());
   long queryWeight = complexityScore * 100L;
   Thread.sleep(queryWeight);
   ```
   For empty queries, `complexityScore` was 10, forcing an intentional **1,000 ms server sleep** on every page navigation.
2. **In-Memory Slicing:** The controller loaded all matching database rows into a JVM `List<Task>` and manually sliced them with `.subList(start, end)`.
3. **Frontend Component Unmounting:** `TaskTable.jsx` unmounted the table whenever `loading` was true, causing the UI to flash blank.
4. **No Request Cancellation:** Overlapping clicks fired unthrottled requests with no cancellation mechanism.

**Implementation**
- **Backend:** Removed `Thread.sleep` and query complexity calculations. Replaced in-memory `.subList()` slicing with Spring Data `Pageable` database queries. Clamped `page` to $\ge 1$ and `pageSize` to $1 \le \text{size} \le 100$.
- **Frontend (`useTasks.js`):** Added `AbortController` to cancel in-flight HTTP requests when new requests are triggered.
- **Frontend (`TaskTable.jsx`):** Retained visible rows during background fetches; displayed a subtle feedback indicator and added `aria-busy={loading}` with opacity transitions.
- **Frontend (`App.jsx`):** Disabled Previous and Next buttons while `loading` is active.

**Why This Fix Works**
Eliminating the backend delay reduced API response latency to under 30 ms. Database-level pagination retrieves only the required 10 rows per request. Keeping rows visible during page transitions prevents UI blanking, while button disabling prevents duplicate requests.

**Verification**
- Backend integration tests confirmed instant response execution with 0 failures.
- In-browser testing confirmed snappy navigation between Page 1 and Page 5 with disabled button states during request transit.

**Assumption**
Pagination page size remains fixed at 10 items per page in the UI.

---

### 3. Status Filtering

**Problem**
Selecting a status filter (e.g. "In Progress") did not filter results. Tasks with `OPEN` and `DONE` statuses continued to appear in the table.

**Root Cause**
A boolean operator precedence defect in the SQL predicate within `TaskRepository.java`:
```sql
-- Original defective SQL:
WHERE archived = FALSE AND LOWER(title) LIKE :term 
   OR LOWER(description) LIKE :term AND (:status IS NULL OR status = :status)
```
In SQL, the `AND` operator binds more tightly than `OR`. The database evaluated the expression as:
```sql
(archived = FALSE AND LOWER(title) LIKE :term)
OR
(LOWER(description) LIKE :term AND (:status IS NULL OR status = :status))
```
When search was empty (`:term = '%%'`), `LOWER(title) LIKE '%%'` evaluated to `TRUE` for every non-archived task. This satisfied the first branch of the `OR` condition, completely bypassing the status check and returning all tasks regardless of their status.

**Implementation**
- **Backend / Repository:** Grouped title and description search alternatives inside explicit parentheses:
  ```sql
  WHERE t.archived = false
    AND (:term = '' OR LOWER(t.title) LIKE LOWER(CONCAT('%', :term, '%'))
                    OR LOWER(COALESCE(t.description, '')) LIKE LOWER(CONCAT('%', :term, '%')))
    AND (:status IS NULL OR t.status = :status)
  ```
- **Backend / Controller:** Safely normalized the status parameter using a `try/catch` block around `TaskStatus.valueOf(...)`, gracefully handling whitespace and unrecognized status strings without throwing 500 exceptions.
- **Database Artifacts:** Updated `search_tasks.sql` and `task_search_package.sql` with identical parenthesis grouping.

**Why This Fix Works**
By grouping the text-matching alternatives in parentheses, both conditions must now evaluate within the overarching `AND` clause: every returned row must have `archived = false` **and** match the requested `status`.

**Verification**
- Integration test `filtersByExactStatusValuesAndSearchesCaseInsensitively`:
  - `status=IN_PROGRESS` returned 11 items, all strictly matching `status: "IN_PROGRESS"`.
  - `status=DONE` returned exactly 5 items, all strictly matching `status: "DONE"`.
  - `status=UNKNOWN_STATUS` safely returned 0 items with HTTP 200 OK.
- In-browser testing verified every visible status badge matches the dropdown selection.

**Assumption**
Valid status values are strictly `OPEN`, `IN_PROGRESS`, and `DONE`.

---

### 4. Search Functionality

**Problem**
Searching for existing tasks (e.g. "add api") resulted in "No tasks found" even though matching task titles existed (e.g. task ID 19 "Add API versioning").

**Root Cause**
1. **Unsynchronized Page State:** If a user navigated to Page 2 or Page 3 and subsequently typed a search query, the frontend remained on Page 2 or 3. Because the search query only matched 1 task, requesting Page 2 of a 1-item result set returned an empty page.
2. **Missing Input Debouncing & Request Flooding:** Every keystroke triggered an independent HTTP request. Earlier slow requests could resolve after later requests, causing race conditions where stale empty results overwrote newer results.
3. **Flawed SQL Grouping:** As detailed in Issue 3, ungrouped `OR` logic produced erratic search results.

**Implementation**
- **Frontend (`App.jsx`):** Created `handleQueryChange` to automatically reset `page` to 1 whenever search text changes.
- **Frontend (`useTasks.js`):** Implemented a 250 ms debounce timer on query changes, while keeping status and page navigation immediate (0 ms delay). Prior pending requests are aborted via `controller.abort()`.
- **Frontend (`api.js`):** Trimmed search query whitespace before parameter construction.
- **Backend (`TaskRepository.java`):** Implemented case-insensitive partial substring search using JPQL against both `t.title` and `COALESCE(t.description, '')`.

**Why This Fix Works**
Resetting the page offset ensures searches evaluate from the beginning of the result set. Debouncing prevents server flooding while typing, and request cancellation eliminates race conditions. Case-insensitive substring matching finds partial matches in both title and description.

**Verification**
- Integration tests verified:
  - `q=add api` returned task ID 19 (*"Add API versioning"*).
  - `q=POOL EXHAUSTION` (case-insensitive description search) returned task ID 7 (*"Database connection pool tuning"*).
  - `q=   ` (whitespace-only search) safely returned all 47 active tasks.
  - `q=no-such-task` returned 0 items and an empty array.
- In-browser testing verified typing "add api" returned task ID 19 smoothly.

**Assumption**
Search performs case-insensitive substring matching across title and description.

---

### 5. Search, Filter, and Pagination Coordination

**Problem**
Combining search terms, status filters, and pagination caused state desynchronization, empty views on valid queries, and incorrect total counts.

**Root Cause**
Frontend criteria state (`query`, `status`, `page`) lacked unified lifecycle handling. Changing filters while on a high page number caused out-of-bounds requests. Furthermore, total element counts in the response were disconnected from the database filter count.

**Implementation**
- **Frontend (`App.jsx`):** Both `handleQueryChange` and `handleStatusChange` reset `page` to 1.
- **Frontend (`useTasks.js`):** Request lifecycle tracks criteria parameters to ensure displayed items and page counts are always consistent with active filters.
- **Backend (`TaskRepository.java`):** Added a dedicated `countQuery` within `@Query` using the exact same predicates as the result query:
  ```sql
  countQuery = "SELECT COUNT(t) FROM Task t WHERE t.archived = false "
             + "AND (:term = '' OR LOWER(t.title) LIKE LOWER(CONCAT('%', :term, '%')) "
             + "OR LOWER(COALESCE(t.description, '')) LIKE LOWER(CONCAT('%', :term, '%'))) "
             + "AND (:status IS NULL OR t.status = :status)"
  ```

**Why This Fix Works**
Every criteria change immediately re-anchors pagination to Page 1, while the database-level count query guarantees that pagination metadata (`total` and `totalPages`) matches the filtered dataset.

**Verification**
- Integration test `combinesSearchAndStatusAndPaginatesTheFilteredResults`:
  - `q=api` + `status=IN_PROGRESS` with `pageSize=1`:
    - Page 1 returned ID 2 (total = 2).
    - Page 2 returned ID 16 (total = 2).
- In-browser testing verified all combinations: filtering after searching, clearing search with filter active, and paginating filtered results.

**Assumption**
Total count reflects active (non-archived) matching rows.

---

### 6. Default Route

**Problem**
The requirement called for verifying that opening `http://localhost:5173/` directly loads the Task Tracker without unnecessary intermediate home or landing screens.

**Root Cause**
Codebase inspection of `index.html` and `src/main.jsx` confirmed that `App.jsx` is mounted directly onto `#root`. No intermediate routes or splash screens existed in the source repository.

**Implementation**
Preserved the direct root mounting structure. Maintained existing header, search bar, status dropdown, task table, and pagination components without introducing unnecessary routing abstractions.

**Why This Fix Works**
Ensures immediate access to the Task Tracker on root URL load, preserving performance and simplicity.

**Verification**
Automated browser navigation to `http://localhost:5173/` loaded the Task Tracker header, controls, and task table directly on initial paint.

**Assumption**
No client-side multi-page routing library (e.g. `react-router-dom`) is required for this single-view application.

---

### 7. In-Place Rendering & Layout Stability During Search / Filter

**Problem**
When typing into the search bar or changing the status filter, the entire page experienced noticeable "shaking", vertical jumps, and visual flickering.

**Root Cause**
1. **Premature State Wipe:** When search or filter state changed, `useTasks.js` immediately set `tasks` to `[]` and `total` to `0`.
2. **Component Collapse:** In `TaskTable.jsx`, empty tasks caused the table to unmount and be replaced by an intermediate `<div className="state-message">` of different height. In `App.jsx`, `total = 0` caused the pagination container to unmount. Once data loaded, both mounted back, causing Cumulative Layout Shift (CLS).
3. **Dynamic Feedback Element:** `<div className="table-feedback">` was conditionally inserted and removed above the table, repeatedly adding and removing 1.5rem of vertical margin.

**Implementation**
- **Frontend (`useTasks.js`):** Implemented the `keepPreviousData` pattern: `data.items` and `data.total` are retained during in-flight fetches while `loading: true` is signaled.
- **Frontend (`TaskTable.jsx`):** Kept `.table-feedback` permanently in the layout with fixed `min-height: 1.25rem`, displaying `"Updating tasks..."` only while loading. Guarded empty states with `!loading`.
- **Frontend (`styles.css`):** Added `.task-table { transition: opacity 0.15s ease; }` and `.task-table[aria-busy="true"] { opacity: 0.65; }`.
- **Frontend (`TaskTable.jsx`):** Added `isFiltered` distinction: renders `"No tasks match the current search or filter criteria."` when search/filter yields 0 results, versus `"No tasks available."` when no tasks exist.
- **Accessibility & Responsiveness:** Added `aria-label` to search input and status dropdown, `scope="col"` to `<th>`, and `.table-wrapper` for mobile viewports.

**Why This Fix Works**
The table and pagination controls remain anchored in the DOM at all times. Feedback is communicated through a subtle in-place table dimming (`opacity: 0.65`) and fixed status line, completely eliminating layout shifting.

**Verification**
- Production build passed (`npm run build`).
- In-browser testing confirmed that rapid typing and dropdown changes update only table rows in place with zero page jitter.

**Assumption**
Maintaining previous rows with subtle dimming during a 250 ms fetch provides optimal user experience compared to blanking the table.

---

## Testing & Verification

Comprehensive automated and manual verification was executed across all layers:

| Area | Verification Method | Scope / Commands | Result |
|---|---|---|---|
| **Backend Tests** | JUnit 5 / Spring MockMvc | `mvnw.cmd test` | **4/4 passed (0 failures, 0 errors)** |
| **Frontend Build** | Vite Production Bundler | `npm run build` | **Passed (0 warnings, 1.33s build time)** |
| **End-to-End Browser** | Automated Browser Subagent | `http://localhost:5173/` | **Passed (all 7 flows verified)** |
| **Browser Console** | Chrome DevTools Protocol | Log stream capture | **0 errors, 0 warnings** |
| **API Endpoints** | REST API Direct Calls | `GET /api/tasks?...` | **Verified on port 8080** |

### Automated Integration Test Summary (`TaskControllerIntegrationTest.java`)

```
[INFO] Running com.internal.tasktracker.TaskControllerIntegrationTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 14.14 s
[INFO] BUILD SUCCESS
```

1. `returnsStableAscendingPagesAndFilteredCount`: Validates ascending IDs (1–10 on Page 1, 11–22 on Page 2) and total count (47 active).
2. `filtersByExactStatusValuesAndSearchesCaseInsensitively`: Validates strict status filtering (`IN_PROGRESS`, `DONE`), case-insensitive title search ("add api"), and description search ("POOL EXHAUSTION").
3. `combinesSearchAndStatusAndPaginatesTheFilteredResults`: Validates multi-criteria intersection (`api` + `IN_PROGRESS` on pages 1 and 2).
4. `trimsBlankSearchAndReturnsAnEmptyPageForNoMatches`: Validates blank query trimming, non-existent query handling (0 items), blank status normalization, and invalid status handling (0 items, HTTP 200).

---

## What Was Not Changed and Why

1. **Visual Styling & Layout Architecture:** Preserved the original design tokens, color palette, status badge styles, typography, and controls. Only minor CSS enhancements (button disabled states, smooth opacity transition, responsive wrappers) were added to fix UX and accessibility defects without altering the aesthetic identity.
2. **Database Engine:** Retained the in-memory H2 database with Spring Data JPA rather than introducing an external database container, ensuring zero-setup startup for evaluation.
3. **Frontend Architecture:** Kept state management in native React hooks (`useState`, `useEffect`, `useRef`) rather than introducing heavy external dependencies (e.g. Redux, TanStack Query).
4. **Oracle Reference Script Schema:** `task_search_package.sql` in `db/oracle/` is an unexecuted reference artifact. Query logic (`ORDER BY id ASC`, boolean parentheses) was aligned with H2 for consistency, but table definitions and packages were otherwise left intact.

---

## Biggest Remaining Risks

1. **Full Table Scans on Leading Wildcard Search (`LIKE '%term%'`):**
   Standard relational B-tree indexes cannot be utilized when a wildcard begins a pattern (`%term%`). In production with millions of tasks, this query will perform sequential table scans. A production system should adopt a dedicated full-text search index (e.g. PostgreSQL `tsvector` with GIN indexing, Oracle Text, or Elasticsearch).
2. **Missing Composite Database Indexes:**
   The `tasks` table currently indexes only the primary key `id`. Adding composite indexes on `(archived, status, id)` would optimize filter operations as the dataset scales.
3. **Concurrency & Optimistic Locking:**
   The `Task` entity does not define a `@Version` attribute. In a multi-user environment, concurrent updates could lead to lost updates without optimistic concurrency control.

---

## Tools and AI Used

- **Antigravity AI Agent:** Used to inspect code across layers, trace SQL boolean precedence bugs, formulate targeted fixes, draft integration tests, and conduct end-to-end browser verification.
- **Maven & Spring Test:** Used `./mvnw.cmd test` with `MockMvc` and H2 to validate database queries, sorting, pagination, and status filters.
- **Vite:** Used `npm run build` and Vite dev proxy (`localhost:5173` -> `localhost:8080`) to validate client-side bundling and proxy routing.

---

## Verification Notes & Handwritten Submissions

- **Backend Port:** Running on `http://localhost:8080`.
- **Frontend Port:** Running on `http://localhost:5173`.
- **Handwritten Notes:** Handwritten notes covering bugs, root causes, fixes, and testing have been photographed and saved in the repository under:
  - `handwritten/page1.jpeg`
  - `handwritten/page2.jpeg`

---

## Final Status

- **Fixed:** Task ID natural ascending order, laggy pagination and artificial server delay, status filter bypass defect, search empty result bugs, search + filter + pagination coordination, and layout shaking during user interaction.
- **Verified:** All 4 backend integration tests pass; frontend production build passes; full browser user journey verified with zero console errors.
- **Ready for Submission:** Code changes are minimal, focused, fully documented, and verified.