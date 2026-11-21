# ⏰ Daily 9 PM Accountability Alarm - Android Application

A production-ready Android application built with **Kotlin**, **Jetpack Compose**, **AlarmManager**, **Android Speech APIs (TTS & STT)**, and **Google Sheets API v4 (Service Account)**.

---

## 🎯 Features

1. **Exact 9:00 PM Daily Alarm (`AlarmManager`)**:
   - Computes exact local 21:00:00 trigger time using `setAlarmClock()`.
   - Guaranteed wake up from Android Doze mode and App Standby.
   - Automatically re-arms for the next night upon completion.
   - Restores scheduled alarms across device reboots (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`).

2. **Full-Screen Wake-up Experience (`FullScreenPromptActivity`)**:
   - Posts a high-priority notification with `fullScreenIntent`.
   - Wakes up the screen directly over the Android Lock Screen using `setShowWhenLocked(true)`, `setTurnScreenOn(true)`, and `FLAG_KEEP_SCREEN_ON`.

3. **Turn-by-Turn Voice Dialogue (Option B)**:
   - **Step 1**: Text-To-Speech (TTS) asks: *"Did you complete soaking your socks?"* → Speech-To-Text (STT) listens → Parses Yes/No.
   - **Step 2**: TTS asks: *"Did you complete hanging the towel?"* → STT listens → Parses Yes/No.
   - **Step 3**: TTS asks: *"Did you complete packing your books?"* → STT listens → Parses Yes/No.
   - **Manual Touch Fallback**: Instant `[YES]` and `[NO]` buttons for noisy environments.

4. **Google Sheets Integration (Option A: Service Account)**:
   - Uses Google Cloud Service Account credentials (`service_account.json`).
   - Authenticates autonomously with `https://www.googleapis.com/auth/spreadsheets` scope — **no interactive user login required at 9 PM**.
   - Directly appends a new row:
     `[Timestamp, Soaking Socks (YES/NO), Hanging Towel (YES/NO), Packing Books (YES/NO), Spoken Transcript, Status]`

5. **Offline Resilience (Room DB + WorkManager)**:
   - If the device is in airplane mode or offline at 9 PM, the response is safely stored in a local SQLite Room DB.
   - Enqueues `WorkManager` with `NetworkType.CONNECTED` constraint to auto-sync rows when internet returns.

---

## 🏗️ Project Architecture

```
daily_accountability_alarm/
├── app/
│   ├── build.gradle.kts
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── assets/
│   │   │   │   └── service_account_template.json
│   │   │   ├── java/com/example/accountability/
│   │   │   │   ├── AccountabilityApplication.kt
│   │   │   │   ├── data/
│   │   │   │   │   ├── local/          # Room DB (Entities, DAO, AppDatabase)
│   │   │   │   │   ├── remote/         # GoogleSheetsService (OAuth2 & REST v4)
│   │   │   │   │   └── repository/     # AccountabilityRepository
│   │   │   │   ├── model/              # TaskItem, TaskStatus, AccountabilityRecord
│   │   │   │   ├── receiver/           # AlarmReceiver, BootReceiver
│   │   │   │   ├── scheduler/          # AlarmScheduler (21:00 exact calculations)
│   │   │   │   ├── ui/                 # MainActivity & FullScreenPromptActivity (Compose)
│   │   │   │   ├── voice/              # TurnByTurnVoiceManager & TranscriptParser
│   │   │   │   └── worker/             # SyncWorker (WorkManager background sync)
│   │   │   └── res/                    # Drawables, themes, colors, strings
│   │   └── test/                       # Unit tests (TranscriptParserTest, AlarmSchedulerTest)
├── verify_core_logic.py                # Standalone test runner for core NLP & Sheets payload
├── build.gradle.kts
└── settings.gradle.kts
```

---

## 🚀 Getting Started

### 1. Open in Android Studio
1. Launch **Android Studio** (Hedgehog, Iguana, or Koala+).
2. Select **Open** and choose the `daily_accountability_alarm` directory.
3. Allow Gradle to sync dependencies.

### 2. Configure Google Sheets Service Account
1. Go to the [Google Cloud Console](https://console.cloud.google.com/).
2. Create a project and enable the **Google Sheets API**.
3. Navigate to **IAM & Admin > Service Accounts** and create a Service Account.
4. Click on the service account > **Keys** > **Add Key** > **Create new key (JSON)**.
5. Open your Google Sheet, click **Share**, and paste the Service Account email address (e.g., `accountability@your-project.iam.gserviceaccount.com`) as **Editor**.
6. In the app:
   - Copy the Spreadsheet ID from the sheet URL: `https://docs.google.com/spreadsheets/d/<SPREADSHEET_ID>/edit`.
   - Paste the Spreadsheet ID into the app's settings field.
   - Tap **"Paste Service Account JSON"** and paste the downloaded JSON, or place the file directly as `app/src/main/assets/service_account.json`.

---

## 🧪 Testing the Experience

### Instant Lock-Screen Verification:
You do not need to wait until 9 PM to verify the full-screen lockscreen intent:
1. Open the app on your Android device or emulator.
2. Ensure **Microphone** and **Exact Alarm** permissions are granted.
3. Tap **"Test Alarm Now (5s delay)"**.
4. **Immediately press your device's power button to lock the screen**.
5. After 5 seconds:
   - The screen will turn on automatically over the lock screen.
   - Text-to-Speech will speak: *"Did you complete soaking your socks?"*
   - Say *"Yes"* or *"No"* (or tap the on-screen buttons).
   - The app will progress through all 3 tasks and append the row directly to your Google Sheet!

---

## 🛡️ Verification Suite
You can verify the NLP parsing and Google Sheets payload contract immediately with Python:
```bash
python daily_accountability_alarm/verify_core_logic.py
```
Outputs:
```text
=== TEST SUITE: Daily Accountability App Logic Verification ===
✓ All 9 affirmative speech tests PASSED
✓ All 8 negative speech tests PASSED
✓ Edge cases (silence / unclear) PASSED
✓ AlarmManager 9:00 PM boundary tests PASSED
✓ Google Sheets v4 API payload contract PASSED

ALL VERIFICATION TESTS COMPLETED SUCCESSFULLY!
```
