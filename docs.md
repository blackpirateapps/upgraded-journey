# post-to-status

Dashboard and API for posting status updates and notes. Content is stored as Markdown files in a GitHub repository via the GitHub API.

## Environment Variables

Set these in your deployment environment (e.g. Vercel):

| Variable | Description |
|---|---|
| `GITHUB_TOKEN` | GitHub PAT with `repo` scope |
| `GITHUB_REPO_OWNER` | Repository owner |
| `GITHUB_REPO_NAME` | Repository name |
| `GITHUB_REPO_PATH` | Folder path for posts |
| `GITHUB_REPO_BRANCH` | Target branch |
| `POST_PASSWORD` | Password for authenticated endpoints |
| `GUESTBOOK_POST_PATH` | *(optional)* Path for guestbook entries. Default: `content/guestbook` |

---

## API Reference

All endpoints accept/return `application/json`. Errors always return `{ "error": "..." }`.

---

### `POST /api/quick-post` ⭐ Recommended for external apps

Single unified endpoint — handles image upload + post creation in one call.

**Body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `password` | string | ✅ | `POST_PASSWORD` value |
| `content` | string | ✅ | Note body (Markdown) |
| `title` | string | ❌ | Note title |
| `tags` | string[] | ❌ | e.g. `["website", "quotes"]` |
| `imageData` | string | ❌ | Base64 data URL (`data:image/jpeg;base64,...`) |
| `imageName` | string | ❌ | Original filename (required if `imageData` sent) |
| `imagePath` | string | ❌ | Repo upload path. Default: `assets/img` |
| `shortcodeTemplate` | string | ❌ | Template with `IMAGE_NAME` token. Default: `{{< img src="/img/IMAGE_NAME" >}}` |

**Response `201`:** `{ "message": "File created successfully!", "path": "<github_url>" }`

**Example — text only:**
```bash
curl -X POST https://your-domain.vercel.app/api/quick-post \
  -H "Content-Type: application/json" \
  -d '{"password":"secret","title":"Hello","content":"My first note","tags":["test"]}'
```

**Example — with image:**
```bash
curl -X POST https://your-domain.vercel.app/api/quick-post \
  -H "Content-Type: application/json" \
  -d '{"password":"secret","content":"Check this!","imageData":"data:image/png;base64,iVBO...","imageName":"photo.png"}'
```

**Android (Kotlin/OkHttp):**
```kotlin
val json = JSONObject().apply {
    put("password", "secret")
    put("title", "Note from Android")
    put("content", "Hello world!")
    put("tags", JSONArray(listOf("mobile")))
}
val body = json.toString().toRequestBody("application/json".toMediaType())
val request = Request.Builder()
    .url("https://your-domain.vercel.app/api/quick-post")
    .post(body)
    .build()
client.newCall(request).enqueue(/* callback */)
```

---

### `POST /api/create-post`

Creates or updates a markdown post directly.

**Body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `password` | string | ✅ | `POST_PASSWORD` value |
| `content` | string | ✅ | Note body (Markdown) |
| `title` | string | ❌ | Note title |
| `tags` | string[] | ❌ | Array of tag strings |
| `client_iso_date` | string | ❌ | ISO date string (used when updating) |
| `sha` | string | ❌ | File SHA — include to **update** an existing file |
| `path` | string | ❌ | File path in repo — include to **update** an existing file |
| `lastmod` | string | ❌ | ISO date for `lastmod` frontmatter field |

**Response `201`:** `{ "message": "File created successfully!", "path": "<github_url>" }`  
**Response `200`:** `{ "message": "File updated successfully!", "path": "<github_url>" }` (when updating)

---

### `POST /api/upload-image`

Uploads an image to the GitHub repo.

**Body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `password` | string | ✅ | `POST_PASSWORD` value |
| `imageData` | string | ✅ | Base64 data URL |
| `imageName` | string | ✅ | Original filename |
| `imagePath` | string | ✅ | Target folder in repo (e.g. `assets/img`) |
| `uniqueImageName` | string | ❌ | Override auto-generated filename |

**Response `201`:** `{ "message": "Image uploaded successfully!", "uniqueImageName": "1234-photo.png" }`

---

### `GET /api/get-posts`

Lists all markdown posts in the configured `GITHUB_REPO_PATH`.

**No body required.** No authentication required.

**Response `200`:**
```json
{
  "posts": [
    { "name": "2026-01-01-hello.md", "path": "content/thoughts/2026-01-01-hello.md", "url": "https://github.com/..." }
  ]
}
```

---

### `GET /api/get-post-content`

Fetches the raw content and SHA of a single post (needed to edit it).

**Query params:** `?path=content/thoughts/2026-01-01-hello.md`

**Response `200`:**
```json
{ "content": "---\ntitle: \"Hello\"\n---\n\nBody here", "sha": "abc123..." }
```

---

### `POST /api/guestbook-entry`

Public endpoint for visitors to submit guestbook entries. Includes bot protection.

**Body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `content` | string | ✅ | Entry text |
| `num1` | number | ✅ | First number for math verification |
| `num2` | number | ✅ | Second number for math verification |
| `verification` | string | ✅ | Answer to `num1 + num2` |
| `name` | string | ❌ | Submitter's name |
| `website` | string | ❌ | Submitter's website |
| `imageData` | string | ❌ | Base64 data URL of an optional image |
| `imageName` | string | ❌ | Image filename |

**Response `201`:** `{ "message": "Thank you! Your entry has been submitted." }`