# SlopeTrace (Android, Kotlin)

Ski tracking app for alpine sessions with sensor fusion, 3D trail rendering, event-based stats, and Supabase-backed session sharing.

## Current Capabilities

- Session flow:
  - Create session with default `yyyy-MM-dd HH:mm` name.
  - Join existing sessions from dropdown (latest first).
  - Start/stop tracking explicitly (tracking does not auto-start on join).
- Live tracking:
  - GPS + barometer + accelerometer ingestion.
  - Smoothed speed and fused altitude.
  - Background-capable foreground service tracking.
- Segment classification:
  - `LIFT`, `DOWNHILL`, `UNKNOWN` state machine with hysteresis and confidence.
  - Z-gate to reduce false positives:
    - `LIFT` confirmed after +10m ascent.
    - `DOWNHILL` confirmed after -10m descent.
    - First 10m are backfilled once confirmed.
- 3D view:
  - Multi-user trail rendering.
  - Live refresh in live view (periodic session snapshot updates).
  - Active/inactive rider status in legend with live speed and distance to active users.
  - Grid floor at minimum recorded Z.
  - Camera rotate + pinch zoom.
- Auth and navigation:
  - Email login + sign up with alias.
  - Log out action in hamburger menu.
  - Header shows signed-in alias in brackets.
- Stats:
  - Session totals (runs, lift/downhill/other time, max session speed).
  - Event feed for each lift and downhill.
  - Downhill metrics include fall height, avg/max speed, duration, mean/max angle, and airtime events.
- Physical lift grouping:
  - Lift rides are clustered into physical lifts if paths are within 20m for >=80% of both paths.
  - Physical lift names can be edited and reused across matching rides in the session.
- Data export:
  - On leave session, local points are exported to JSON for later model/rule tuning.
  - Export path is shown in session screen.

## Project Structure

- `app/src/main/java/com/slopetrace/sensor` - sensor + fused location ingestion
- `app/src/main/java/com/slopetrace/tracking` - tracking pipeline, gates, export, active session store
- `app/src/main/java/com/slopetrace/domain/classification` - sliding-window segment classifier
- `app/src/main/java/com/slopetrace/domain/stats` - event-based stats engine + physical lift clustering
- `app/src/main/java/com/slopetrace/domain/coords` - WGS84 to local ENU conversion
- `app/src/main/java/com/slopetrace/data/local` - Room entities and DAO
- `app/src/main/java/com/slopetrace/data/remote` - Supabase auth/realtime/persistence integration
- `app/src/main/java/com/slopetrace/render` - OpenGL ES renderer host
- `app/src/main/java/com/slopetrace/ui` - Compose screens (login, session, live, stats)

## Supabase Setup

1. Run schema from `supabase/schema.sql` in your Supabase SQL editor.
2. Replace values in `app/build.gradle.kts`:
   - `SUPABASE_URL`
   - `SUPABASE_ANON_KEY`
3. Ensure email auth is enabled in Supabase Auth settings.
4. Enable realtime for table `position_stream`.
5. Configure auth URLs:
   - `Authentication -> URL Configuration -> Site URL`: set a real URL.
   - `Authentication -> URL Configuration -> Redirect URLs`: include `slopetrace://auth`.

## Permissions and Background

- Required runtime permissions:
  - Foreground location (`ACCESS_FINE_LOCATION` or `ACCESS_COARSE_LOCATION`)
  - Background location (`ACCESS_BACKGROUND_LOCATION`, Android 10+)
- Tracking in background depends on device battery policy; whitelist app from aggressive battery optimization where possible.

## Data Export

Session exports are written to:

- `Android/data/com.slopetrace/files/Documents/session_exports/`

Each file contains raw point history and derived fields (ENU, segment, confidence, run/lift ids) for offline analysis.

## Build and Test

- Build debug APK:
  - `./gradlew assembleDebug`
- Unit tests:
  - `./gradlew testDebugUnitTest`

If Gradle/Kotlin incremental cache gets stale, run:

- `./gradlew clean`
