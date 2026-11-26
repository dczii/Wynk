# Wynk Grok Chat: plan

Wynk becomes a chat with Grok that lives on the glasses. You add a message by speaking or by taking
a photo, and Grok's reply shows on the display. The phone keeps the full conversation and can also
send typed messages.

This replaces the earlier practice-mode plan. The photo capture built in phase 1 stays as it is.

## How it works

The phone runs everything. The glasses provide a camera, a microphone and a display.

1. **Speak.**
   - Tap **Speak** on the glasses. Wynk routes the glasses mic to the phone over Bluetooth HFP and
     records.
   - Tap **Send** to stop. Wynk sends the audio to xAI speech-to-text, then sends the text to Grok.
2. **Photo.**
   - Tap **Photo**. Wynk takes a photo with the phase 1 flow: remove the display, capture, stop the
     camera, then add the display again.
   - Then pick one of two buttons:
     - **Ask**: say a question about the photo.
     - **Send**: use the default prompt, "What's in this photo?".
3. **Reply.** Grok's reply streams in. The glasses show it as it arrives, a page at a time.

## Grok API

Wynk calls the xAI REST API at `https://api.x.ai/v1` with OkHttp and kotlinx.serialization. There
is no official Kotlin SDK.

**Chat.**
- `POST /v1/chat/completions` with `stream: true` (server-sent events).
- Images go in as `image_url` parts with a base64 `data:image/jpeg` URL. The limit is 20 MiB, and the
  accepted types are JPEG and PNG. Wynk scales photos down so the long side is at most 1568 px.
- xAI calls Chat Completions "legacy" and puts new features in the Responses API first, but Chat
  Completions still supports images and streaming. Move to `/v1/responses` if a feature Wynk needs
  only exists there.
- **Model.** A vision-capable Grok 4.x model. Confirm the exact id against `GET /v1/models` in
  phase 2.

**Conversation history.**
- The phone keeps the history and sends it with each request.
- Only the newest photo is sent as an image. Earlier photos become the text `[photo]`, which keeps
  each request small.
- **New chat** clears the history.

**System prompt.** Replies need to fit a 600×600 display:
- Plain text, with no markdown, tables or emoji.
- About 60 words or fewer unless the user asks for more.
- Code only when asked, with short lines.

**Speech-to-text.**
- `POST /v1/stt`, multipart, with `model=grok-voice-transcribe-2.0` and the recording as `file`.
  The option fields must come before the file field.
- Batch is enough here. The streaming WebSocket (`wss://api.x.ai/v1/stt`) could show words live
  later.

**API key.**
- The key goes in `local.properties` as `xai_api_key` and reaches the app through `BuildConfig`.
- `local.properties` is gitignored, so the key never goes into git.
- The key ends up inside the APK, which is acceptable only because the app is personal. Don't share
  the APK.

## Microphone

The glasses mic reaches the phone as a Bluetooth HFP device, 8 kHz mono. Meta's Android sample uses
this flow:

1. **Route.** `AudioManager.setCommunicationDevice(<the glasses' Bluetooth SCO device>)`.
2. **Record.** `AudioRecord` with source `VOICE_COMMUNICATION`, mono 16-bit PCM.
3. **Wrap.** Wrap the PCM in a WAV header and upload it.
4. **Release.** `clearCommunicationDevice()` so the glasses go back to normal audio.

**Rules.**
- `RECORD_AUDIO` runtime permission on the phone.
- Recording stops on **Send**, on **Cancel**, or after 60 s.
- Never record and capture a photo at the same time. Meta's docs warn that the audio route can fail
  silently if the camera starts first.

## Screens on the glasses (600×600)

Each `sendContent` replaces the whole screen, so every screen is drawn from the phone's chat state.

| Screen | Content | Buttons |
|---|---|---|
| Chat | your last message (one line) + Grok's reply, page p/N | **Speak** · **Photo** · (**More** when the reply has more pages) |
| Listening | "Listening…" + seconds elapsed | **Send** · **Cancel** |
| Photo taken | "Photo ready" | **Ask** · **Send** |
| Thinking | "Thinking…", then the reply as it streams | **Cancel** |
| Error | short reason | **Retry** · **Back** |

**Pages.** About 8 lines of reply per page. **More** steps through the pages and wraps back to the
first.

**Streaming updates.** Screen updates are limited to one per second while a reply streams, so the
display isn't flooded.

**New chat.** On the phone, plus a long-press on the glasses if the display API allows it.

## Phone screen

- **Transcript.** The full conversation as chat bubbles, including photo thumbnails.
- **Composer.** A text field for typed messages, plus **Speak** and **Photo** buttons that do the
  same as on the glasses.
- **Controls.** The existing connect, session and simulator controls, moved into a settings section.

## Build phases (each ends with a test)

1. **Capture.** Done (`563b781`). Still needs its first real-glasses test.
2. **Grok text and photo, on the phone.**
   - xAI client, chat state in the ViewModel, phone transcript and composer.
   - Send a captured photo with a prompt.
   - **Pick from gallery**, for testing without the glasses.
3. **Glasses chat screens.**
   - Chat, Thinking and Photo taken screens, with paging and throttled streaming.
   - Test in the simulator, then on the glasses.
4. **Voice.**
   - HFP routing, recording, WAV, xAI STT.
   - Listening screen with **Send** / **Cancel**, and **Ask** after a photo.
5. **Polish.**
   - New chat.
   - Error and Retry screens.
   - Save the conversation across app restarts.
   - Re-send the screen to keep it awake while a reply streams.

## Known risks

- **HFP mic quality.** 8 kHz audio is phone-call quality. STT should cope, but noisy places may
  need a retry.
- **Audio routing.** Taking over the Bluetooth SCO route can fail while Meta AI or a call holds it.
  Wynk shows an error and doesn't retry in a loop.
- **Beta photo capture and firmware.** These are the same as before: standalone photo is beta in
  DAT 1.1, and the glasses need a reboot after a firmware update before the camera works.
- **Latency.** Expect 3–6 s for a photo, about 1–3 s for speech-to-text, then Grok's first words
  in about 1–3 s.
