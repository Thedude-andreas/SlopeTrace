# SlopeTrace (Android, Kotlin)

MVP ski tracking app scaffold with local sensor fusion, rule-based segment classification, ENU conversion, Supabase realtime sync, and live 3D track rendering.

## Project Structure

- `app/src/main/java/com/slopetrace/sensor` - sensor + fused location ingestion
- `app/src/main/java/com/slopetrace/tracking` - local tracking pipeline and storage
- `app/src/main/java/com/slopetrace/domain/classification` - 5s sliding-window segment classifier
- `app/src/main/java/com/slopetrace/domain/coords` - WGS84 to local ENU conversion
- `app/src/main/java/com/slopetrace/data/local` - Room entities and DAO
- `app/src/main/java/com/slopetrace/data/remote` - Supabase auth/realtime/persistence integration
- `app/src/main/java/com/slopetrace/render` - OpenGL ES renderer host
- `app/src/main/java/com/slopetrace/ui` - Compose screens (login, session, live, stats)

## MVP Features Included

1. GPS + barometer sensor capture with dynamic sampling (0.5-5 Hz behavior).
2. Local-only rule-based segment classification (`LIFT`, `DOWNHILL`, `UNKNOWN`).
3. ENU conversion from first point origin.
4. Supabase auth (`sign in`/`sign up`) + realtime channel + offline pending sync queue.
5. Session-local 3D trail renderer host (`GLSurfaceView`) and live line growth hooks.
6. Session statistics engine for runs, vertical, times, speed, slope.
7. MVVM architecture around `SessionViewModel`.
8. Foreground tracking service start/stop based on active session.
9. Runtime location-permission flow before session join/live.
10. Auth deeplink handling for `slopetrace://auth`.

## Supabase Setup

1. Run schema from `supabase/schema.sql` in your Supabase SQL editor.
2. Replace values in `app/build.gradle.kts`:
   - `SUPABASE_URL`
   - `SUPABASE_ANON_KEY`
3. Ensure email auth is enabled in Supabase Auth settings.
4. Enable realtime for table `position_stream`.
5. Configure auth URLs:
   - `Authentication -> URL Configuration -> Site URL`: set a real URL (not localhost).
   - `Authentication -> URL Configuration -> Redirect URLs`: include `slopetrace://auth`.
6. If policies already exist, drop/recreate affected policies before rerunning schema.

### Important Session Rules

- Session ID must be a valid UUID.
- `Create new` generates a UUID and attempts to create/join session membership.
- Sync writes are scoped per active session and preserve original point timestamp.

## Auth and Recovery Notes

- If `Email confirmation` is enabled, users must verify before login.
- Recovery links require a valid redirect target; localhost links fail on real devices.
- The app handles deeplink auth intents via `slopetrace://auth`.

## Build Notes

- Build and run via Android Studio.
- If build cache is stale after dependency/config changes, run `Sync Project with Gradle Files` then `Clean Project`.
