# Wynk Practice Mode: plan

Practice mode helps you study coding problems with Wynk. Point the glasses at a problem (LeetCode on a
screen, a printout, a whiteboard) and capture a photo. Wynk sends it to Claude, then shows the answer
on the glasses one step at a time:

1. hints, one per screen
2. the full code, when you press **Show code**
3. the explanation and complexity

It's built for practice sessions, so it uses progressive hints and doesn't jump straight to the answer.

## How it works

The phone runs everything. The glasses only provide a camera and a display.

1. **Trigger.** Press **Capture problem** on the phone, or **New problem** on the glasses.
2. **Capture.**
   - Remove the display from the session and add the camera with `session.addCamera()`.
   - Call `camera.photo.capturePhoto(LARGE, HIGH)` and wait for the image on `photo.photoStream`.
   - Call `camera.stop()`.
3. **Show progress.** Add the display again and show "Reading the problem…".
4. **Analyze.**
   - Scale the JPEG down so its longest side is at most 1568 px. That keeps the upload small with no loss
     in how well Claude reads the problem.
   - Send it to Claude with a fixed output shape (structured output).
5. **Show the result.** Display Hint 1 on the glasses. The phone shows everything at once, including
   the captured photo.

Two rules from the docs drive this order:

- **Capture and display never run together.** Meta's known-issues page says not to combine capture with
  display, because updates are held back until the photo finishes transferring.
- **One photo at a time.** The SDK puts no request ID on photo results, so Wynk never starts a second
  capture before the first one arrives.

## What Claude returns

Wynk uses the Anthropic Java SDK (`com.anthropic:anthropic-java`) from Kotlin with model `claude-opus-5-5`.
It uses typed structured output via `outputConfig(Solution::class.java)`, so the reply always parses.

```kotlin
data class Solution(
  val title: String,              // "Two Sum"
  val problemSummary: String,     // 1–2 sentences, proves it read the photo right
  val hints: List<String>,        // 3 progressive hints, no code, each ≤ 200 chars
  val language: String,           // e.g. "Python"
  val code: String,               // complete solution, ≤ ~40 lines, short lines
  val explanation: List<String>,  // short bullets: approach, key insight, edge cases
  val timeComplexity: String,
  val spaceComplexity: String,
  val readable: Boolean,          // false if the photo isn't a legible coding problem
)
```

**Prompt.** The prompt asks for:
- Hints that go from a gentle nudge, to naming the technique, to outlining the approach.
- Code that fits a small screen: lines of 40 characters or fewer, and no comments, because the
  explanation covers them.
- `readable = false`, rather than a guess, when the photo is unclear.

**Request settings.** Effort `medium` (the default). Streaming, with the SDK collecting the final message.

**Fallbacks.** Server-side `fallbacks: "default"` handles a refused request. Wynk also checks
`stop_reason` before reading the reply.

**Language.** Python by default. A setting on the phone changes it.

**API key.** The key goes in `local.properties` as `anthropic_api_key` and reaches the app through
`BuildConfig`. That file is gitignored, so the key never goes into git.

The key ends up inside the APK, which is acceptable only because the app is personal. Don't share the
APK, because anyone holding it can read the key.

## Screens on the glasses (600×600)

Every screen has a page indicator and at most two buttons. Swipe to move between buttons and tap to
press one.

| Screen | Content | Buttons |
|---|---|---|
| Reading | "Reading the problem…" | Cancel |
| Hint n/3 | problem title + hint *n* | **Next hint** · **Show code** |
| Code p/N | ~12 lines of code per page | **Next** (or **Explain** on the last page) · **Back to hints** |
| Explain p/N | bullets + time/space complexity | **Code** · **New problem** |
| Can't read | "Couldn't read a problem, try again closer" | **Retry** |

**Show code** appears on every hint screen, so you can jump to the code at any point.

**Code font risk.** The display API has text styles but no monospace font. If code looks wrong as
`text`, Wynk will render each code page to a 600×N bitmap with a monospace font and send it as an
`image`.

## Build phases (each ends with a test on the real glasses)

1. **Capture.**
   - Add `mwdat-camera` and the glasses camera permission (`Wearables.checkPermissionStatus` /
     `RequestPermissionContract`).
   - Add a **Capture problem** button that shows the photo in the phone app.
   - Turn on the phone-camera mock feed, so capture can also be tested without the glasses.
2. **Analysis.**
   - Add the Claude call and the `Solution` model, with the full result shown on the phone.
   - Add **Pick from gallery**, so the prompt can be tuned on screenshots without the glasses.
3. **Glasses screens.**
   - Hint → Show code → Explain flow, with paging.
   - Text-versus-bitmap check for code.
4. **Loop and polish.**
   - **New problem** on the glasses runs the whole cycle.
   - Loading and error screens.
   - One retry on the camera's `SETUP_FAILED` error.
   - Keep the screen awake while you read (re-send it before the 20 s dim).

## Known risks

- **Beta photo capture.** Standalone photo capture is dev/beta in DAT 1.1, which is fine for a personal
  app. Camera errors mostly arrive as `CAPTURE_FAILURE`, so Wynk shows a single "Retry" for all of them.
- **Firmware update.** A ~1.6 GB glasses firmware update is pending. Per the known-issues page, the
  glasses need a reboot after an update before the camera works.
- **Latency.** Expect about 3–6 s for the photo transfer, plus about 5–15 s for Claude. The
  "Reading…" screen covers that wait.
