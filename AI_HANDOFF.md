# AI Handoff (upgraded-journey)

## What This Repo Is

This repository is an Android app (Kotlin) named **Microblog**. It lets a user create a note (Markdown body + optional Hugo-style frontmatter + tags + optional image) and submit it to a backend API.

The backend API is expected to create a new file in a GitHub repository and commit it (and optionally upload an image), but **the backend implementation is not in this repository**. The app currently targets an externally hosted backend:

- `https://post-to-status.vercel.app`

If you are looking for the serverless function/code that actually performs the GitHub commit, it lives elsewhere.

## High-Level Architecture

- UI layer: `Fragment`s + ViewBinding.
- Networking: `OkHttp` calls in `ApiClient`.
- Offline queue: `SharedPreferences` JSON queue (`QueueStore`) storing `PendingPost` items.
- Background retry: `WorkManager` (`SyncWorker`) drains the queue when network is available.

Primary screens:

- New Note: compose + submit (or queue on failure).
- Queue: shows queued items.
- Posts: fetches and lists posts from backend.
- Info: static “about” screen.

## Core User Flow: Create Note -> Backend -> Commit

1. User composes a note in `NewNoteFragment`.
2. App builds content:
   - In "Plain Text" mode, it injects YAML frontmatter (title/tags/date/lastmod) ahead of the body.
   - In "Markdown" mode, it sends body as-is.
3. Optional image:
   - User picks an image; app reads bytes via `ContentResolver`, base64-encodes it, and sends as a `data:<mime>;base64,...` URL.
   - App also inserts a shortcode into the note body by replacing `IMAGE_NAME` in the template.
4. App sends JSON to `POST /api/quick-post`.
5. If the request fails (HTTP error, exception, or offline), the app adds the post to a local queue and schedules background sync.

## Backend API Contract (As Used By This App)

### POST `/api/quick-post`

Request JSON (fields sent by the app):

- `password` (string, required): The shared secret the backend expects (named `POST_PASSWORD` in app comments).
- `content` (string, required): Markdown content.
- `title` (string, optional): Only sent if non-blank.
- `tags` (array of strings, optional): Only sent if non-empty.
- `imageData` (string, optional): Base64 data URL string.
- `imageName` (string, optional): Original filename (required by app if `imageData` is set).
- `imagePath` (string, required by app): Path in repo where the backend should store the image (defaults to `assets/img`).
- `shortcodeTemplate` (string, required by app): Template containing `IMAGE_NAME`.

Response expectations:

- On success (2xx): JSON may include:
  - `message` (string): Displayed in debug log.
  - `path` (string): Displayed as a “Published at” value.
- On error (non-2xx): JSON may include:
  - `error` (string): Displayed to the user and triggers queueing.

### GET `/api/get-posts`

Response expectations:

- On success (2xx): JSON:
  - `posts`: array of `{ name, path, url }`

### GET `/api/get-post-content`

Used for editing existing posts (fetches the raw Markdown file content and its current Git SHA).

Query params:

- `path` (string, required): repo path to the markdown file

Response expectations:

- On success (2xx): JSON:
  - `content`: raw markdown content
  - `sha`: git blob SHA for the file

### POST `/api/create-post` (update mode)

Used for saving edits to an existing post (requires `sha` + `path`).

Request JSON (fields used by the app for updates):

- `password` (string, required)
- `content` (string, required): edited raw content
- `path` (string, required): existing repo path
- `sha` (string, required): file SHA from `/api/get-post-content`
- Note: the app updates `lastmod` inside the Markdown frontmatter client-side (only if frontmatter already exists) and does not ask the backend to regenerate frontmatter.

## Code Map (Where Things Live)

Networking:

- `app/src/main/java/microblog/blackpiratex/com/ApiClient.kt`
  - `BASE_URL` points at the current backend host.
  - `quickPost(...)` sends `POST /api/quick-post`.
  - `getPosts()` calls `GET /api/get-posts`.
  - `getPostContent(...)` calls `GET /api/get-post-content`.
  - `updatePost(...)` calls `POST /api/create-post` to update an existing file.

Compose / submit UI:

- `app/src/main/java/microblog/blackpiratex/com/NewNoteFragment.kt`
  - Builds frontmatter in "Plain Text" mode.
  - Handles image selection and base64 encoding.
  - Calls `ApiClient.quickPost`.
  - On failure, writes to `QueueStore` and schedules `WorkManager`.
  - Optional “Save password” using `SharedPreferences`.

Offline queue:

- `app/src/main/java/microblog/blackpiratex/com/data/QueueDatabase.kt`
  - `PendingPost` serialization to/from JSON.
  - `QueueStore` backed by `SharedPreferences("sync_queue")`.

Background sync:

- `app/src/main/java/microblog/blackpiratex/com/sync/SyncWorker.kt`
  - Loads `saved_password` from `SharedPreferences("microblog_prefs")`.
  - Drains queue FIFO and retries with exponential backoff on failure.

Other UI:

- `app/src/main/java/microblog/blackpiratex/com/PostsFragment.kt` (list posts)
- `app/src/main/java/microblog/blackpiratex/com/EditPostFragment.kt` (edit an existing post)
- `app/src/main/java/microblog/blackpiratex/com/QueueFragment.kt` (list queued items)
- `app/src/main/java/microblog/blackpiratex/com/InfoFragment.kt` (static info)

## Local Development

Build debug APK:

- `./gradlew assembleDebug`

Run unit tests (if/when added):

- `./gradlew testDebugUnitTest`

CI:

- `.github/workflows/android.yml` builds `assembleDebug` on pushes/PRs to `main`/`master` and uploads the debug APK artifact.

## Operational Notes / Gotchas

1. Backend is external and hardcoded.
   - `ApiClient.BASE_URL` is a constant; changing backend environments requires a code change.

2. Password storage is plaintext.
   - “Save Password” stores `saved_password` in normal `SharedPreferences`. Anyone with device access / backups could recover it.

3. Queue persistence can get large.
   - If posts include `imageData`, the base64 payload is stored in `SharedPreferences` JSON, which can bloat storage and may hit size/perf limits.

4. Queue tag parsing is naive.
   - `SyncWorker` uses `split(",")` without trimming, so `"tag1, tag2"` yields `" tag2"` with a leading space.

5. Frontmatter injection is mode-dependent.
   - In "Markdown" mode, the app does not auto-add frontmatter. In "Plain Text" mode it enforces frontmatter + body structure.

6. Editing requires file SHA.
   - The edit flow must call `/api/get-post-content` to obtain `sha` before calling `/api/create-post` to update the file.

## Security Review (Client-Side)

Threat model: This app sends a shared secret (`password`) over the network to authorize creating commits on a backend. If an attacker gets the password, they can likely create commits via the backend.

Current client-side risks:

- Secret at rest: stored in plaintext prefs if user enables “Save Password”.
- Secret in transit: relies on HTTPS; no certificate pinning.
- Debug logging: `ApiClient.quickPost` logs response bodies (could include details you do not want to show on-device).

If tightening security:

- Use `EncryptedSharedPreferences` (or Jetpack Security) for `saved_password`.
- Consider removing/limiting response-body logging in the UI debug panel.
- Consider moving the shared secret out of an end-user client entirely (backend auth via OAuth or per-user tokens) if the system is meant for multiple users.

## What’s Missing / Likely Next Work

- Bring the backend code into this repo (or link it clearly) so “commit creation” is auditable and maintainable.
- Replace hardcoded `BASE_URL` with build variants (`debug` vs `release`) or remote config.
- Replace `SharedPreferences`-based queue with:
  - Room database for metadata; and
  - file-based blob storage for images (avoid base64-in-prefs).
- Add instrumentation tests for:
  - queueing behavior on failure;
  - successful drain behavior in `SyncWorker`;
  - frontmatter builder invariants.
