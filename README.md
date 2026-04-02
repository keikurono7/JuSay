# JuSay

Voice-first Android controller that listens to spoken commands, reads the current screen through Accessibility APIs, asks an LLM to plan the next UI action, and executes that action on-device.

JuSay is an Android app built with Jetpack Compose, AccessibilityService, and SpeechRecognizer.

## What It Does

- Captures voice commands from the user.
- Scrapes a compact UI tree from the active screen.
- Uses Groq LLM planning to decide the next action.
- Executes supported actions through Accessibility APIs:
	- `click`
	- `type`
	- `scroll`
	- `wait`
	- `launch`
	- `home`
	- `back`
- Includes practical safety controls:
	- Blocked system packages (for example Settings/System UI)
	- Blocked risky keywords (for example delete/uninstall/pay)
	- Sensitive target filtering for secret-like values

## Repository Layout

```text
.
|- JuSay/               # Android project
|  |- app/
|  |- gradle/
|  |- gradlew(.bat)
|- LICENSE
|- README.md
```

## Architecture

1. User taps floating mic or "Test Voice Command".
2. Android SpeechRecognizer captures spoken intent.
3. Accessibility service scrapes current UI nodes.
4. JuSay runs a two-stage planner call:
	 - Select most relevant UI section
	 - Generate next action JSON for that section
5. JuSay executes action and optionally continues for up to 5 planning steps.
6. Status is shown via toast + notification.

All planning calls are made directly from the app to Groq over HTTPS.

## Android App Setup

Project path: `JuSay/`

### Requirements

- Android Studio (recent stable)
- JDK 11+
- Android SDK with API 36 platform installed
- Device/emulator running Android 7.0+ (minSdk 24)

### Build and Run

From repository root:

```bash
cd JuSay
./gradlew assembleDebug
```

On Windows PowerShell:

```powershell
cd JuSay
.\gradlew.bat assembleDebug
```

Install debug APK (example):

```bash
./gradlew installDebug
```

### First-Time App Configuration

After launching the app:

1. Grant microphone permission.
2. Enable Accessibility service for JuSay.
3. (Optional) Grant overlay permission and enable floating mic overlay.
4. Paste your Groq API key in the app and tap Save.
5. Tap "Test Voice Command" to verify voice capture + action flow.

## Runtime Model Output

The planner is expected to return one JSON action object with this shape:

```json
{
	"action": "click|type|scroll|wait|launch|home|back",
	"target_id": "view_id_or_text_or_app_name",
	"input_text": "text_to_type_if_any"
}
```

## Permissions Used (Android)

- `INTERNET`: call Groq API
- `RECORD_AUDIO`: voice capture
- `SYSTEM_ALERT_WINDOW`: floating mic button
- `POST_NOTIFICATIONS`: status updates on Android 13+
- Accessibility service: read UI tree + perform actions

## Safety and Guardrails

- Blocks selected sensitive packages and risky keywords.
- Rejects secret-looking target IDs.
- Limits autonomous planning depth (`MAX_PLAN_STEPS = 5`).
- Stops loops when action/target repeats.
- Uses a compact, section-based UI payload to reduce token usage.

## Troubleshooting

- "Service not active": enable JuSay accessibility service in system settings.
- "Microphone permission missing": grant RECORD_AUDIO permission.
- "Speech recognition unavailable": try a physical device with Google voice services enabled.
- "Groq error" or empty response:
	- verify API key
	- check internet connectivity
	- check model availability/limits in your Groq account
- Overlay not showing:
	- grant overlay permission
	- toggle floating mic off/on in app controls

## Tech Stack

- Android: Kotlin, Jetpack Compose, AccessibilityService, SpeechRecognizer, OkHttp
- LLM provider: Groq Chat Completions API

## License

MIT. See `LICENSE`.