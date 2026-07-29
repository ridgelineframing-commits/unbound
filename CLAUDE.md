# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## ⚠️ Nested duplicate directory — do not edit `unbound-app/`

This repo contains a byte-identical-in-spirit but **stale, smaller duplicate** of the app at
`unbound-app/unbound-app/` (yes, double-nested). It has its own `build.gradle`/`settings.gradle`
and a `construction.ridgeline.unbound` source tree, but it's missing everything from Milestone 1
onward — no `MonthRenderer`, `AgendaRenderer`, `DayOpenActivity`, `EventEditActivity`,
`SearchActivity`, or `CalendarChangeJobService` (7 Kotlin files there vs. 14 at the real root).

**The canonical, live app is the top-level `app/` directory** (rooted by `settings.gradle` at the
repo root, which does `include ':app'`). The GitHub Actions workflow builds from the repo root.
Always work in `/app`, never in `/unbound-app/unbound-app/app`. If you're ever unsure which copy
you're looking at, check the path depth — the real one is one level under the repo root.

## Commands

Build from the **repo root** (not `unbound-app/`):

```bash
./gradlew assembleDebug     # -> app/build/outputs/apk/debug/app-debug.apk
```

There's no local signing setup beyond an optional debug keystore (`app/unbound-debug.keystore`,
restored from a CI secret so consecutive builds share one signing key and upgrade cleanly over
each other) — if the file is absent, Gradle falls back to the default ephemeral debug key. No
unit/instrumented test suite exists in this repo currently.

CI: `.github/workflows/android.yml` ("Build Unbound APK") runs on every push to `main`/`master`,
every PR, and manual dispatch. It builds `assembleDebug`, uploads the APK as a workflow artifact
(`unbound-debug-apk`), and — on pushes only — also republishes it as the GitHub release tagged
`latest`, giving a permanent download URL
(`releases/latest/download/app-debug.apk`) that doesn't change per-build.

## Architecture

**No server, no network permission, no accounts.** The app reads only the device's own
`CalendarContract` provider (`READ_CALENDAR`; `WRITE_CALENDAR` was added later for in-widget event
creation/editing) — this is a hard privacy/scope boundary, not an oversight, so don't reach for
network APIs here.

**Rendering model — RemoteViews can't do arbitrary layout, so weeks/days are pre-rendered images.**
`AppWidgetProvider`/`RemoteViews` only support a fixed set of pre-approved widget views; you can't
hand it an arbitrary Compose/View hierarchy. Instead, each week (or day/agenda row, or month grid)
is drawn onto a `Bitmap` sized to the widget's actual reported width
(`WeekRenderer`/`AgendaRenderer`/`MonthRenderer`, all plain `Canvas` drawing), and `WeekWidgetService`
(a `RemoteViewsService.RemoteViewsFactory`) hands those bitmaps to a `ListView`-style adapter
(`R.id.week_list`) inside the actual `RemoteViews` widget layout. `UnboundWidgetProvider` is the
`AppWidgetProvider` that assembles the surrounding chrome (header pill, refresh/settings glyphs)
and wires the remote adapter + a `PendingIntentTemplate` so tapping a day launches the no-display
`DayOpenActivity` with a fill-in intent carrying that day's date. Per-widget size is read from
`AppWidgetManager` options defensively (`UnboundWidgetProvider.updateWidget`) since some launchers
under-report or omit min/max width/height — bogus/zero values fall back to sane defaults rather
than producing a degenerate render.

**Settings/theme/opacity persistence** all lives in `Prefs.kt`, a single `SharedPreferences`
wrapper (`unbound_prefs`). Per-widget settings (weeks shown, view mode, cached width/height in px)
are keyed by appwidget id (`"weeks_$id"` etc.); appearance settings (theme, opacity, text size,
week-start day, strike-past-days) are global. The hidden-calendars set is stored as an
**exclusion** list (not an allow-list) specifically so newly-synced calendars show up on the
widget automatically instead of silently staying hidden.

**Auto-refresh has three independent triggers**, all re-armed on every `updateWidget` call (and
after boot / app replacement, since alarms/jobs don't survive those):
1. A midnight `AlarmManager.setInexactRepeating` fire (00:02 local) so "today"'s highlight and
   past-day strike-throughs advance without user action.
2. A `JobScheduler` content-trigger job (`CalendarChangeJobService`) watching
   `CalendarContract.CONTENT_URI` for descendant changes, so edits made in Google Calendar (or any
   other calendar app) propagate into the widget within seconds.
3. Android's own ~30 min minimum `AppWidgetProvider.onUpdate` floor, plus the manual ↻ button —
   this is a platform floor, not something the app can shorten.

Widget "modes" (`Prefs.mode`) select what `WeekWidgetService` renders per row: `0` = rolling
1–4 week grid, `1` = flat 30-day agenda, `2` = a month grid followed by the same agenda. All three
share one `CalendarRepository.events()` query window and one light/dark palette
(`WeekRenderer.LIGHT`/`DARK`).

The in-app `MainActivity` (full-screen companion, not just a config screen) mirrors this same
rendering approach for its own Weeks/Agenda views, and adds event search (`SearchActivity`) and
create/edit (`EventEditActivity`) on top of the same `CalendarRepository`.

Versioned feature history (v2.0 → v3.3, Milestones 1–2) lives in `README.md`'s changelog — consult
it there rather than duplicating it here; this file only tracks architecture that affects how you
should make changes.
