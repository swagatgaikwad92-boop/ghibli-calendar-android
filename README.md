# Ghibli Calendar — Android widget (version 2, reviewed)

Your calendar website's colours, CSS, layout and design are **not touched**.
Nothing in this package has been run on a real phone or built by the real Android toolchain (see "What was and was not tested").

## What's in this ZIP
| File / folder | What it is |
|---|---|
| `GhibliCalendarAndroid.zip` | The complete Android project. Upload it to GitHub **as a zip** (don't unzip). |
| `build-apk.yml` | The GitHub build recipe (turns the project into an APK). |
| `export-data.html` | Optional new page for your website repo: saves a backup of your Chrome data. |
| `optional-website-sync/` | Optional cloud sync (read "Sync" below before using). Complete files, ready to upload. |
| `verification/` | The tests I ran, and their results (`RESULTS.txt`). |

## 1. Sync — the honest picture
The Android widget cannot read Chrome's storage, so there are two real options:

**Option A — the app is your calendar (default, nothing extra to set up).** The app shows your same website and the widget shares its data. The Chrome PWA keeps its own separate copy. *Use the app from now on.* Your old Chrome data is moved in once with `export-data.html` (below). Safe and simple, but Chrome and the app are **not** synced with each other.

**Option B — optional cloud sync through your own private GitHub repo.** This is the only way I found to also keep Chrome and the app in step without paying for or running a server. It needs these files added to your **website** repo: `sync.js`, `sync-setup.html`, and a replacement `index.html` that is your current file plus **one added line** (`<script src="sync.js"></script>`; I checked with a diff that nothing else changed). It does nothing until you enter a token in `sync-setup.html`.
- How it protects your data: it merges (new items from both sides are kept, edits are kept, deletes are applied only if the other side did not edit that item), the first sync only *adds*, it keeps a backup before changing anything, it refuses to run if the cloud file is invalid, and it stops if a merge would delete most of your calendar.
- Limits: it is **not instant** (page open, coming back to the app, and a few seconds after each change). A change made **in the widget** reaches the cloud/Chrome the next time you **open the app**, not while it's closed. If two devices edit the same task at the same moment, one edit wins. The token is stored in each browser, so use a fine-grained token for that one private repo only ("Contents: Read and write").
- It was tested only against a **simulated** GitHub (22 checks), never against real GitHub.
- If you don't want it, ignore the folder. Option A is complete without it.

## 2. What I found and fixed in my first version
1. **Widget/board showed old unfinished tasks inside "Today".** Your website doesn't — it shows only that day's tasks. Removed, so widget = website.
2. **"Completed" was far from "Today"** (a very long drag). It now sits directly under Today.
3. **Every drop re-numbered every section.** Now only sections that actually changed are saved, and a drop that changes nothing writes nothing.
4. **Order could go wrong after the website moved a task.** Order is now remembered together with the day it was set for and ignored if the task is on another day.
5. **A background copy of the website could overwrite a widget change** (e.g. its notification timer saving old data). Out-of-date saves are now refused, and the page is refreshed as soon as the widget/board changes data.
6. **Opening a specific day while offline failed** (your service worker only has the home page cached). The app now falls back to the cached home page.
7. **No safety net for your data.** The app now keeps a safety copy once a day and before every import/restore (last 12). Board → **Backups** restores one (your current data is saved first).
8. **Updates might not install over the old app.** Each GitHub build now gets a higher version number; the signing key is fixed so updates keep your data.
9. The build now prints the SDK it found and verifies the APK signature in the log.

## 3. Drag and drop (Board → "⇅" button on the widget)
Android widgets cannot do drag-and-drop, so drag lives on the Board screen. Press and hold a task, then drag it: up/down = reorder, onto another day's heading = reschedule, into **Completed** = mark done (drag out = not done). Empty days show a dashed "Drop a task here" slot. Every drop saves at once and refreshes the widgets.
- Reordering is shared by the widget and the board. Your website's own list keeps its time order (changing that would mean editing the website). Tasks added later on the website appear after tasks you've arranged.
- Verified by tests (real project code, simulated phone storage): move between days, into/out of Completed, reorder persists after reopening the board, empty sections accept drops from above and below, no-op drops write nothing, unknown task fields are preserved. **Not verified:** the actual finger gesture (Android's drag helper) — please try it.

## 4. Build review (GitHub Actions)
- Java 17, Gradle 8.7, Android Gradle Plugin 8.5.2, compileSdk/targetSdk 34, minSdk 26: a combination that is documented as compatible. The runner's Android SDK is checked and the two needed packages are requested in the log.
- Output: `app-debug.apk`, published under **Releases** and as a run artifact.
- Signing: a fixed key included in the project (a personal, non-secret "debug" key) — the same on every build, so updates install over the old app. Fine for your own phone, not for the Play Store. A log step runs `apksigner verify` and prints the certificate.
- Not verified: I could not run the build here. If it goes red, copy the red error text to me.

## 5. Install (from your phone)
1. GitHub → new **private** repository (e.g. `ghibli-calendar-android`).
2. **Add file → Upload files** → upload `GhibliCalendarAndroid.zip` (as is).
3. **Add file → Create new file**, name it exactly `.github/workflows/build-apk.yml`, paste the contents of `build-apk.yml`, **Commit**.
4. Your calendar address is assumed to be `https://swagatgaikwad92-boop.github.io/Ghibli-Calander/` (taken from a comment in your code). If it's different: repo **Settings → Secrets and variables → Actions → Variables → New**: `SITE_URL` = your address.
5. **Actions → Build Ghibli Calendar APK → Run workflow**. Wait for the green tick (about 5 minutes).
6. **Releases → newest build → app-debug.apk** → download → install (allow "install unknown apps" for your browser).
7. Later updates: upload a new zip with the same name; install the new APK over the old one.

## 6. Bring your data across (once)
1. Add `export-data.html` to your website repo (new file only).
2. In Chrome open `…/Ghibli-Calander/export-data.html` → **Download backup file**.
3. Open the app → **Choose backup file** → pick `ghibli-calendar-backup.json`.

## 7. Test on your Vivo V25 (Funtouch OS 15)
- Allow notifications. Add the widgets: long-press Home → Widgets → Ghibli Calendar.
- Tap a circle in the widget → app shows the task done. Complete a task in the app → widget updates.
- Large widget: `↑` and `→`; `‹ ›` change day. Board: drag across days, into Completed, empty day; close and reopen the board; check the widget each time.
- Airplane mode: widgets still work; the app opens from cache (after one online start).
- Vivo: Settings → Battery → Background power consumption → allow for Ghibli Calendar; allow Autostart. Otherwise refresh may pause.

## 8. What was and was not tested
**Tested (56 automated checks, `verification/RESULTS.txt`):** the real data/board logic code with simulated storage (26), the cloud-sync merge against simulated GitHub (22), the script injected into your website (8). XML files were checked for well-formedness and every resource reference for existence.
**Not tested:** Gradle build, compilation against the real Android SDK (I only ran a limited consistency check), widget rendering, the finger drag, the WebView on your phone, real GitHub sync, Funtouch background behaviour. Expect that the first build may need one round of fixes.

## Known gaps
- Reminders show as Android notifications only while the app is alive in memory. Reminders while it's fully closed are not included.
- Fraunces (your headline font) can't be used in a widget; a system serif stands in.
- Tasks from earlier days that were never finished are not shown (same as your Today screen).
