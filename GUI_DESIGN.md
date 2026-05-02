# ClipBoard Speech — GUI Design Document

Source: Claude Design prototype (`ClipBoard Speech.html`)

---

## Design Language

**Material You (Material 3)** — Google's adaptive design system.  
**Typography:** Google Sans (headings/buttons), Google Sans Text (body/labels).

---

## Color Tokens

| Token | Hex | Usage |
|---|---|---|
| `primary` | `#1b6ef3` | Speak button, active state, word highlight, slider accent |
| `primaryContainer` | `#d8e3ff` | Pause button background, selected voice row |
| `onPrimaryContainer` | `#001257` | Pause button text/icon |
| `surface` | `#faf8ff` | App background, top bar, controls panel |
| `surfaceVariant` | `#e2e1ec` | Read-along view background, voice selector button |
| `onSurface` | `#1b1b21` | Primary text |
| `onSurfaceVariant` | `#44445a` | Labels, secondary text, slider labels |
| `outline` | `#757589` | Borders (unfocused) |
| `outlineVariant` | `#c6c5d0` | Dividers, textarea border (unfocused), controls top border |
| `error` | `#ba1a1a` | Stop button icon, Clear button text |
| `secondaryContainer` | `#e4dfff` | Paste button, Save button background |
| `onSecondaryContainer` | `#1b0062` | Paste/Save button text and icon |
| `scrim` | `rgba(0,0,0,0.32)` | Voice bottom sheet backdrop |
| `errorContainer` | `#fce8e6` | Stop button background, Clear button background |
| `toast` | `#2d2d35` | Toast notification background |

---

## Screen Layout

```
┌─────────────────────────────┐
│  Status Bar (40dp)          │
├─────────────────────────────┤
│  Top App Bar (64dp)         │
├─────────────────────────────┤
│                             │
│  Text Area / Read-Along     │
│  (flex, fills remaining)    │
│                             │
│  Char count + Save/Clear    │
├─────────────────────────────┤
│  Controls Panel             │
│  - Speed & Pitch sliders    │
│  - Voice selector           │
│  - Speak / Pause+Stop       │
└─────────────────────────────┘
```

---

## Components

### 1. Top App Bar
- Height: `64dp`
- Background: `surface`
- Bottom border: `1px outlineVariant`
- **Title:** "ClipBoard Speech" — Google Sans, 22sp, weight 600, `onSurface`, letterSpacing `-0.3px`
- **Subtitle:** "Offline · Google TTS Engine" — 11sp, `onSurfaceVariant`, marginTop 1dp
- **Paste button** (right side):
  - Shape: Circle, 40×40dp
  - Background: `secondaryContainer`
  - Icon: Clipboard/Paste SVG, 20dp, `onSecondaryContainer`

---

### 2. Text Area (idle state)
- Padding: `12dp` top/bottom, `16dp` left/right (within content area)
- **EditText:**
  - Border: `1.5dp`, `outlineVariant` (focus → `primary`)
  - Border radius: `16dp`
  - Padding (inner): `14dp` top/bottom, `16dp` left/right
  - Font: Google Sans Text, 16sp, lineHeight 1.65
  - Text color: `onSurface`
  - Caret: `primary`
  - Hint text: `#9494a8`
  - Hint copy: *"Type or paste your text here… ClipBoard Speech reads aloud using the on-device Google TTS engine — no internet required."*

---

### 3. Read-Along View (speaking state)
Replaces the EditText when speech is active.
- Background: `surfaceVariant`
- Border radius: `16dp`
- Padding: `14dp` top/bottom, `16dp` left/right
- Font: 17sp, lineHeight 1.7, `onSurface`
- **Word highlight:** current word → background `primary`, text `#ffffff`, borderRadius `3dp`
- Word highlight transition: `0.1s`

---

### 4. Character Count Bar
- Displayed below textarea, padding `6dp` top, `4dp` bottom, `4dp` left/right
- Left: `{n} characters` — 12sp, `onSurfaceVariant`
- Right (visible only when text is non-empty AND not speaking):
  - **Save button:** `secondaryContainer` bg, radius `20dp`, padding `6dp`×`12dp`, 12sp bold, `onSecondaryContainer` — icon (save) 14dp + "Save" label
  - **Clear button:** `#fce8e6` bg, radius `20dp`, padding `6dp`×`12dp`, 12sp bold, `error` color — icon (X) 14dp + "Clear" label

---

### 5. Controls Panel
- Background: `surface`
- Top border: `1px outlineVariant`
- Padding: `10dp` top, `16dp` left/right, `12dp` bottom

#### Speed & Pitch Sliders (side by side, gap 16dp)
Each slider:
- Label row: label left (12sp, `onSurfaceVariant`, weight 500) + value right (12sp, `primary`, weight 600)
- Slider: full width, `accentColor: primary`, height `4dp`
- **Speed:** range 0.5–2.0, step 0.1, default 1.0, display as `1.0×`
- **Pitch:** range 0.5–2.0, step 0.1, default 1.0, display as `1.0`

#### Voice Selector Button
- Full width, padding `10dp`×`14dp`, radius `12dp`
- Border: `1dp outlineVariant`, background `surfaceVariant`
- Top label: "Voice" — 11sp, `onSurfaceVariant`
- Bottom label: `{voice.name} ({voice.lang})` or "Default" — 14sp, weight 500, `onSurface`
- Right: dropdown chevron icon, 20dp, `onSurfaceVariant`
- MarginBottom: `14dp`
- Tap → opens Voice Bottom Sheet

#### Speak Button (idle)
- Full width, height `56dp`, radius `16dp`
- Background: `primary`, shadow `0 2dp 8dp rgba(27,110,243,0.35)`
- Icon: volume/speaker SVG, 24dp, white — label "Speak" — Google Sans, 16sp, weight 600, white
- Layout: icon + label centered with `10dp` gap

#### Pause + Stop Buttons (speaking)
Displayed side by side, `10dp` gap:

**Pause/Resume** (flex):
- Height `52dp`, radius `14dp`
- Paused state: background `primary`, text/icon white
- Playing state: background `primaryContainer`, text/icon `onPrimaryContainer`
- Label: "Pause" or "Resume" with matching icon

**Stop** (fixed 52×52dp):
- Background: `#fce8e6`, radius `14dp`
- Icon: stop square, 22dp, `error`

---

### 6. Voice Bottom Sheet
- Triggered by Voice Selector button
- Backdrop: `scrim` overlay, tap to dismiss
- Sheet: slides up from bottom, radius `28dp` top corners, background `surface`, maxHeight `70%`, scrollable
- **Header:** padding `20dp` top/left/right, `10dp` bottom
  - Title: "Select Voice" — Google Sans, 17sp, weight 600
  - Close (X) icon button right
- **Voice rows:** each voice listed as a button
  - Padding `12dp`×`20dp`, full width
  - Selected row: background `primaryContainer`
  - Primary text: `{voice.name}` — 14sp, weight 500, `onSurface`
  - Secondary text: `{voice.lang} · On-device` or `· Network` — 12sp, `onSurfaceVariant`
  - Selected indicator: checkmark icon 20dp, `primary`, right side
- **Empty state:** "No voices found. Check your device TTS settings." — 14sp, `onSurfaceVariant`, centered

---

### 7. Toast Notification
- Position: centered horizontally, `100dp` above bottom
- Background: `#2d2d35`, text `#ffffff`
- Padding: `10dp`×`18dp`, radius `24dp`
- Font: 13sp, weight 500
- Duration: 2200ms
- Animation: fade in + slide up 8dp (`0.2s ease`)
- Triggers: paste success, empty-text warning, save confirmation

---

## Interactions & States

| Trigger | Behavior |
|---|---|
| Type/paste text | Updates character count |
| Tap Paste button | Reads Android clipboard → fills textarea |
| Tap Speak | Starts TTS, switches to read-along view + Pause/Stop controls |
| Tap Pause | Pauses TTS, button switches to Resume state |
| Tap Resume | Resumes TTS |
| Tap Stop | Cancels TTS, returns to idle (textarea visible) |
| Tap Save | Exports text as `YYYYMMDD-HHMMSS.txt` |
| Tap Clear | Clears textarea, stops any active speech |
| Tap Voice selector | Opens voice bottom sheet |
| Select voice | Closes sheet, updates selected voice |
| Word boundary event | Highlights current word in read-along view |
| Speech end/error | Returns to idle state, clears word highlight |

---

## Android Implementation Notes

- TTS engine: `android.speech.tts.TextToSpeech` (no internet required)
- Initialize in `onCreate`, release in `onDestroy`
- `setOnUtteranceProgressListener` for word-boundary highlighting (`onRangeStart` API 26+)
- Voice list: `TextToSpeech.getVoices()` filtered and displayed in bottom sheet (`BottomSheetDialogFragment`)
- `EditText` with `inputType="textMultiLine"` for the text area
- Speed/Pitch: `TextToSpeech.setSpeechRate()` / `TextToSpeech.setPitch()`
- Clipboard: `ClipboardManager.getPrimaryClip()`
- Save: `FileOutputStream` to `getExternalFilesDir()` or `Downloads` via `MediaStore`
- Snackbar or custom Toast view for notifications
