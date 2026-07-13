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

## What's new in v3.2

- **The widget now matches the app:** each week floats as its own glass card right on
  your wallpaper (no more single flat panel). The header is a small glass pill, agenda
  days are cards too, and the Background Opacity slider now controls the cards' glass.

## What's new in v3.1 - Float, finished (the design-handoff spec)

- **Glass cards in the app:** every week is now its own floating glass card over
  ambient color glows - current week most opaque, others slightly more transparent.
- **Agenda in the app:** the Weeks | Agenda pill in the header switches the full-screen
  view; agenda days are glass cards too.
- **Widget polish:** "July 2026" header (month bold, year faint), weekday strip removed,
  later weeks slightly dimmed, refresh/menu glyphs at 50%.
- **Chips finish the spec:** titles wrap fully (never truncated), compact times ("9a",
  "9:30"), banner text is always a tone-matched dark shade, past banners wash out.
- **Light theme** matches the 3b spec: 18dp card radius, deep-green accent.

## What's new in v3.0 — the "Float" redesign

- **New look, dark-first:** cool ink card with a hairline border and 24dp corners,
  mint accent for today (filled circle), and a matching deep-green accent in light theme.
- **Events are chips:** each timed event is a rounded, color-tinted chip — bold time in
  the calendar's color, full title wrapped underneath. Still no "+3 more", ever.
- **All-day banners:** multi-day events are now solid color banners with tone-matched text.
- **Cleaner dates:** centered day numbers, "AUG 1" month-turn marker in the accent color,
  finished days get a small strike through the number instead of a big slash.
- **Settings restyled:** dark sheet, mint segmented controls.

## What's new in v2.1

- **Fixed:** weeks now reliably stretch to fill the widget (launchers that under-report
  the widget size get a sane floor), and event text can no longer overlap or bleed into
  the next day's column.
- **Tap a day** — opens that day in your calendar app.
- **Agenda view** — a "today + next 7 days" list mode; switch in the gear settings.
- **Auto-refresh** — the widget now updates itself just after midnight (so today's
  highlight and strike-throughs move on their own) and whenever any calendar event on
  the device is added, edited, or removed.

## What's new in v2.0

- **Themes:** Light, Dark, or Auto (follows your phone's dark mode). Tap the gear to switch.
- **Transparency:** a Background Opacity slider (20–100%) — the calendar card can float
  translucently over your wallpaper while text and events stay fully readable.
- **Fills its space:** if your weeks are light on events, the grid stretches so the calendar
  always fills the widget instead of leaving dead space. Dense weeks still scroll.
- **Full titles:** long event names wrap to a second line instead of getting chopped off.
- **Done days get crossed off:** past days are dimmed and struck through with a slash
  (toggle in settings).
- **Today** gets a subtle column highlight plus the date pill; weekends get a faint tint;
  the 1st of each month is labeled (e.g. `AUG 1`).
- **Header redesign:** the black bar is gone — a clean header shows the month + year, and you
  can hide the header entirely for a minimal floating-calendar look.
- **Start week on Monday** option, and **text size** (Small / Medium / Large).
- **Settings all in one place:** weeks, theme, opacity, text size, toggles, and calendar
  checkboxes now live in the gear menu. Changes apply to the widget instantly.
- Rounded card corners to match modern Android widgets.

## Notes & known edges

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
