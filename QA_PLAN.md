# ClipBoard Speech — QA Plan

## QA Sub-Agent

**Agent type:** `Evidence Collector`  
**Role:** Independent QA specialist who validates the built Android app against the GUI design spec and project requirements. Requires visual/log proof for every pass or fail — no assumptions.

---

## Scope

Test that the app correctly implements:
1. All UI components from `GUI_DESIGN.md`
2. All TTS behaviours (speak, pause, resume, stop, word highlight)
3. Offline operation — no internet dependency
4. Clipboard paste, Save, Clear actions
5. Speed, Pitch, and Voice controls

---

## Test Cases

### TC-01 · App Launch
| Step | Expected |
|---|---|
| Install APK and open app | Single screen loads, no crash |
| Observe top bar | Title "ClipBoard Speech", subtitle "Offline · Google TTS Engine" visible |
| Observe textarea | Placeholder hint text visible, empty |
| Observe controls | Speed/Pitch sliders, Voice selector, Speak button all visible |
| **Pass criteria** | Screen matches `GUI_DESIGN.md` layout |

---

### TC-02 · Text Input
| Step | Expected |
|---|---|
| Tap textarea and type text | Characters appear, character count updates |
| Type 0 characters | Save and Clear buttons are hidden |
| Type ≥ 1 character | Save and Clear buttons appear below textarea |
| Textarea border on focus | Border color changes to `primary` (#1b6ef3) |
| Textarea border on blur | Border returns to `outlineVariant` (#c6c5d0) |

---

### TC-03 · Clipboard Paste
| Step | Expected |
|---|---|
| Copy text in another app | Text is in system clipboard |
| Tap the paste icon in top bar | Textarea fills with clipboard text |
| Toast message | "Pasted from clipboard" toast appears and auto-dismisses |
| Clipboard is empty | Toast "Paste not available — type or use long-press" shown |

---

### TC-04 · Speak (Offline TTS)
| Step | Expected |
|---|---|
| Enable airplane mode | Device has no internet |
| Enter text, tap Speak | Audio plays through speaker |
| Observe screen | Textarea replaced by read-along view (surfaceVariant bg) |
| Observe controls | Speak button replaced by Pause + Stop buttons |
| **Pass criteria** | Speech plays without internet — confirms Google TTS on-device |

---

### TC-05 · Word Highlight (Read-Along)
| Step | Expected |
|---|---|
| Tap Speak with multi-word text | Each word highlights in `primary` (#1b6ef3) as it is spoken |
| Highlight timing | Highlight moves word-by-word, not sentence-by-sentence |
| After speech ends | All highlights clear, textarea returns |

---

### TC-06 · Pause & Resume
| Step | Expected |
|---|---|
| While speaking, tap Pause | Speech pauses, button shows "Resume" with play icon, primary bg |
| Tap Resume | Speech continues from where it paused |
| Word highlight during resume | Highlight continues correctly from paused word |

---

### TC-07 · Stop
| Step | Expected |
|---|---|
| While speaking, tap Stop | Speech stops immediately |
| UI state | Returns to idle: textarea visible, Speak button restored |
| Character count | Remains unchanged |

---

### TC-08 · Speed Control
| Step | Expected |
|---|---|
| Move Speed slider to 2.0× | Slider label shows "2.0×" |
| Tap Speak | Speech noticeably faster than default 1.0× |
| Move Speed slider to 0.5× | Speech noticeably slower |

---

### TC-09 · Pitch Control
| Step | Expected |
|---|---|
| Move Pitch slider to 2.0 | Slider label shows "2.0" |
| Tap Speak | Speech pitch noticeably higher |
| Move Pitch slider to 0.5 | Speech pitch noticeably lower |

---

### TC-10 · Voice Selector
| Step | Expected |
|---|---|
| Tap Voice selector | Bottom sheet slides up, lists available on-device voices |
| Observe voice rows | Each row shows voice name, language, "On-device" or "Network" tag |
| Tap a different voice | Sheet closes, voice selector button shows selected voice name |
| Tap Speak | Speech uses the newly selected voice |
| Tap sheet backdrop | Sheet dismisses without changing voice |

---

### TC-11 · Save
| Step | Expected |
|---|---|
| Enter text, tap Save | File download or save dialog appears |
| Check filename | Format `YYYYMMDD-HHMMSS.txt` |
| Open saved file | Contents match textarea text exactly |
| Toast | "Saved as {filename}" toast appears |

---

### TC-12 · Clear
| Step | Expected |
|---|---|
| With text in textarea, tap Clear | Textarea empties immediately |
| If speaking when Clear tapped | Speech stops and textarea clears |
| Save/Clear buttons | Disappear after clear (text length = 0) |
| Character count | Resets to "0 characters" |

---

### TC-13 · Empty Text — Speak Guard
| Step | Expected |
|---|---|
| Leave textarea empty, tap Speak | Speech does NOT start |
| Toast | "Enter some text first" appears |

---

### TC-14 · Accessibility
| Step | Expected |
|---|---|
| Enable TalkBack | App is navigable by screen reader |
| Speak button | Announces "Speak button" |
| Paste button | Announces "Paste from clipboard" or similar |
| Sliders | Announce current value on change |

---

### TC-15 · Lifecycle / Edge Cases
| Step | Expected |
|---|---|
| Rotate screen while speaking | Speech continues, UI restores correctly |
| Press Home while speaking | Speech stops (or continues based on implementation choice) |
| Return to app | UI reflects correct state |
| Open another TTS app while speaking | App handles audio focus gracefully |

---

## Defect Severity Levels

| Level | Definition |
|---|---|
| **P0 — Blocker** | App crashes, TTS silent in airplane mode, data loss on Save |
| **P1 — Critical** | Word highlight broken, Pause/Resume broken, voice selection has no effect |
| **P2 — Major** | UI deviates significantly from `GUI_DESIGN.md` colors/layout |
| **P3 — Minor** | Toast missing, character count wrong, animation missing |

---

## Evidence Requirements

The QA agent must provide for each failed test case:
- Screenshot showing the failure
- Logcat output (if crash or unexpected behaviour)
- Device model and Android API level
- Steps to reproduce

A test case is only marked **PASS** when the expected behaviour is observed and confirmed — not assumed.

---

## Agent Handoff

| Phase | Agent | Input | Output |
|---|---|---|---|
| Build | `app-developer` / `Mobile App Builder` | `GUI_DESIGN.md` | Signed APK |
| Test | `Evidence Collector` | APK + this QA plan | Pass/Fail report with screenshots |
| Review | `Test Results Analyzer` | QA report | Prioritised defect list |
| Fix | `app-developer` | Defect list | Updated APK |
| Re-test | `Evidence Collector` | Updated APK | Regression sign-off |
