# Unbound — the widget

*No more tiny boxes.*

A native Android home-screen widget that shows a **rolling 1–4 week calendar** read straight
from your phone's own calendar store. No Google sign-in, no account, no server — it reads the
calendars already synced to your device (`READ_CALENDAR` permission only). Weeks grow to fit
every event and the list scrolls, so nothing hides behind a "+3 more."

Built to pair with your Google Calendar: anything on your phone's calendars shows here,
including the Ridgeline project calendars (Davi, Red Leaf, Vorse) once they're synced to the device.

---

## Build the APK (no tools on your computer)

You don't need Android Studio. GitHub builds it for you.

1. Make a new **GitHub repo** (private is fine). Name it anything, e.g. `unbound`.
2. Upload **all the files in this folder** to the repo, keeping the structure
   (the `.github`, `app`, and the three `.gradle` files must be at the repo root).
   - Easiest: on the repo page → **Add file → Upload files** → drag the whole contents in.
   - Or with git: `git init && git add . && git commit -m "unbound" && git branch -M main && git remote add origin <your-repo-url> && git push -u origin main`
3. The push triggers the build automatically. Go to the **Actions** tab → open the running
   **"Build Unbound APK"** job → wait ~3–5 min for the green check.
4. In that finished run, scroll to **Artifacts** → download **`unbound-debug-apk`**.
   Unzip it to get **`app-debug.apk`**.

> No luck on the first run? Open the failed step's log and paste me the red error text — CI
> builds are fussy about versions and I'll patch it fast.

## Install it on your phone

1. Copy `app-debug.apk` to your phone (email it to yourself, Drive, USB, whatever).
2. Tap it. Android will ask to allow installing from this source — allow it (Settings →
   "Install unknown apps" for your file manager/browser).
3. Install. You now have an app called **Unbound**.

## First run

1. Open **Unbound** once. Tap **Grant calendar access** → Allow.
2. You'll see a list of your calendars with checkboxes — untick any you don't want on the
   widget (e.g. holidays, birthdays). Leave the project calendars ticked.

## Add the widget

1. Long-press an empty spot on your home screen → **Widgets** → find **Unbound**.
2. Drag it out. It'll ask **how many weeks** (1–4) — pick one; you can change it anytime.
3. **Resize** it by long-pressing and dragging the handles. Tap the **gear** to change weeks,
   the **↻** to refresh, or the **UNBOUND** wordmark to open the app.

---

## The one gotcha (same as before)

The widget shows calendars that are **synced to the device**. Secondary/shared calendars
(your Davi / Red Leaf / Vorse project calendars) often aren't synced by default. If the widget
shows your personal events but not the projects:

- Open the **Google Calendar** app → **☰ Settings** → tap each project calendar → turn on **Sync**.
- Or on your phone browser, open `calendar.google.com/calendar/syncselect` and check them.

Then tap **↻** on the widget.

---

## Notes & known edges (v1)

- **Auto-refresh** runs about every 30 min (Android's minimum) plus the ↻ button. If you add an
  event elsewhere and want it now, tap ↻.
- **Rendering:** each week is drawn to an image sized to your widget's width. On very dense weeks
  the image is capped for memory safety; if a week ever looks soft or clipped, tell me and I'll
  tune the cap.
- **Colors** come from each event's calendar color on the device.
- Everything stays on your phone. The app has no internet permission at all.

## What I'd add next (just ask)
- Tap a day to open that day in your calendar app.
- A compact "today + next 7 days" agenda mode.
- Auto-refresh at midnight and on calendar changes.
