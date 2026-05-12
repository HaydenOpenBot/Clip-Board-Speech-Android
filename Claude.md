# Clipboard Speech App — Claude Code Context

## What This App Does
An Android app (Kotlin, View Binding, no Compose) that:
- Reads clipboard text and converts it to speech (TTS)
- Sends clipboard content to a backend AI API to generate Obsidian knowledge base notes

## Current App State
All features are implemented and live:
- **ClipBoard Speech screen** — paste, speak, pause/resume, word highlighting, save, AI summary
- **📝 筆記 button** — sends clipboard to `https://claude-pm.gynlhmc.com/api/process-note` via WorkManager (survives app close)
- **筆記設定 tab** — configures the endpoint URL, shows live submission log with status
- **AI Config tab** — configures OpenAI-compatible API for AI summaries
- **History tab** — browsing and exporting past TTS entries

## Architecture
```
HomeActivity          → bottom nav host (Home / History / AI Config / 筆記設定)
MainActivity          → ClipBoard Speech screen (TTS + 筆記 button)
ProcessNoteWorker     → WorkManager background job for process-note API call
AppDatabase (v3)      → Room DB: history + process_note_log tables
ProcessNoteConfigFragment → endpoint config + live submission log
AiConfigFragment      → AI summary provider config
```

## Key Files
| File | Purpose |
|---|---|
| `MainActivity.kt` | TTS screen, 筆記 button, WorkManager enqueue |
| `ProcessNoteWorker.kt` | Background HTTP call, DB status update |
| `ProcessNoteConfigFragment.kt` | Config UI + submission log |
| `data/ProcessNoteEntry.kt` | Room entity: id, contentPreview, status, serverMessage, inboxFile, createdAt, completedAt |
| `data/AppDatabase.kt` | DB version 3, migrations 1→2→3 |
| `~/.cloudflared/config.yml` | Cloudflare examvault tunnel — includes claude-pm.gynlhmc.com ingress |

## Workflow Rules

### Before touching any code
- Read the relevant file(s) first — never edit from memory
- For bug fixes: identify root cause before proposing a fix
- For enhancements: state what file will change and why, then wait for approval
- Exception: user says "go ahead" or "implement now"

### Bug Fixes
- Fix only what is broken — do not refactor surrounding code
- Do not touch TTS logic (`speakSentence`, `buildSentences`, `utteranceProgressListener`) unless the bug is explicitly in TTS
- Do not touch existing clipboard/speech functionality when working on Process Note, and vice versa

### Enhancements
- Match existing UI style (Material 3, View Binding, Traditional Chinese for all user-facing text)
- Follow existing patterns: LiveData observation, lifecycleScope for coroutines, Room for persistence
- If a new screen is needed: add a Fragment + layout + nav item (follow HomeActivity pattern)
- If a new background task is needed: use WorkManager (follow ProcessNoteWorker pattern)
- If DB schema changes: increment version, write a Migration, never use `fallbackToDestructiveMigration`

### After every change
1. Build: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`
2. Copy APK: `cp app/build/outputs/apk/debug/app-debug.apk ~/Downloads/Uploads/ClipBoardSpeech-debug.apk`
3. Commit with conventional message: `feat:` / `fix:` / `refactor:`
4. Push to `main` on `HaydenOpenBot/Clip-Board-Speech-Android`

## Cloudflare / Backend
- Backend runs via Docker at `~/Documents/claude-pm/` on port 4101
- Exposed publicly via examvault tunnel (`~/.cloudflared/config.yml`) at `claude-pm.gynlhmc.com`
- If tunnel stops: `launchctl start com.cloudflare.examvault`
- macmini-api tunnel (`config-macmini-api.yml`) is currently unused — do not delete
