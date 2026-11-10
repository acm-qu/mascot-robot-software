# Face Presence Implementation Plan

**Goal:** ACMO notices someone who walks up and looks at it, and says hello
(a fixed line in the ElevenLabs voice) without waiting for "hey ACMO".

**Spec:** `docs/superpowers/specs/2026-11-10-face-presence-design.md`. Read it
first. It has the rules, the thresholds and the reasons behind them.

**Stack:** CameraX 1.6.1, ML Kit face detection 16.1.7 (bundled, on-device),
Kotlin, JUnit 4.

**Branch:** `feat/computer-vision`.

## Conventions

- Gradle needs Android Studio's JDK and the SDK:
  `export JAVA_HOME="<Android Studio>/jbr"`, and `ANDROID_HOME` set (or `sdk.dir` in `local.properties`).
- Tests: `./gradlew :app:testDebugUnitTest`. Build: `./gradlew :app:assembleDebug`.
- Logs on the tablet: `adb logcat -s Brain Vision`.
- Keep new logic pure and JVM-testable, like `Tags` and `MouthGate`; the Android parts stay thin.
- Comments are prose that explains *why*, matching the rest of the code.

## Phase 0: Library versions (done, `39f7020`)

- [x] Add CameraX (`camera-core`, `camera-camera2`, `camera-lifecycle`) and `com.google.mlkit:face-detection` to `gradle/libs.versions.toml` and `app/build.gradle.kts`.
- [x] `assembleDebug` passes `checkDebugAarMetadata` with AGP 8.13.2 and compile SDK 36.
- [x] No pinned library moved (`androidx.core` 1.17.0, `lifecycle` 2.9.4, `activity` 1.11.0, `kotlin-stdlib` 2.2.21).
- [x] Both ABIs are shipped. The face detector adds about 13 MB of native code, and the debug APK is 86 MB.

## Phase 1: Presence rules and greeting lines (done, `894168d`)

- [x] `vision/FacePresence.kt`:
  - `Seen` and `Arrival`;
  - `feed(faces, atMs, busy)` applies the rules: close, facing, an 800 ms dwell, a 20 s cooldown, one greeting per group, and no greeting for anyone seen while busy.
- [x] `vision/Greetings.kt` holds fixed lines for one person, a group and a smiler, and never repeats a line twice in a row.
- [x] Tests: `FacePresenceTest` (13) and `GreetingsTest` (6). All 107 unit tests pass.

## Phase 2: The camera (`vision/VisionPipeline.kt`)

**Files:**
- Create: `vision/VisionPipeline.kt`
- Modify: `AndroidManifest.xml`

- [ ] Write `VisionPipeline(context, owner: LifecycleOwner, listener)` with `start()` and `stop()`, both idempotent. It has:
  - `Listener.onFaces(faces: List<Seen>, atMs)`;
  - `Listener.onVisionError(message)`;
  - a `problem: String?` property for the settings card.
- [ ] Bind `ProcessCameraProvider` to the activity with `DEFAULT_FRONT_CAMERA` and `ImageAnalysis` only (no preview):
  - about 640×480;
  - `STRATEGY_KEEP_ONLY_LATEST`;
  - one `acmo-vision` thread.
  - If there is no front camera, report `onVisionError("no front camera")`.
- [ ] Analyzer:
  - drop frames within `FRAME_GAP_MS = 200` of the last analysed one;
  - pass the frame to ML Kit with `InputImage.fromMediaImage(image, rotationDegrees)`;
  - close the `ImageProxy` in `addOnCompleteListener`, every time.
- [ ] Detector options:
  - `PERFORMANCE_MODE_FAST`;
  - no landmarks, no contours (contours turn tracking off);
  - `CLASSIFICATION_MODE_ALL`;
  - `enableTracking()`;
  - `setMinFaceSize(0.1f)`.
- [ ] Map each `Face` to `Seen`:
  - `trackingId`;
  - the box width divided by the rotated frame width;
  - `headEulerAngleY` and `headEulerAngleX`;
  - `smilingProbability`.
- [ ] Ten detector failures in a row → `onVisionError` and stop.
- [ ] Log the face count under the tag `Vision`, only when it changes.
- [ ] Manifest: add the `CAMERA` permission, plus `android.hardware.camera` and `android.hardware.camera.front` with `required="false"`.
- [ ] `assembleDebug` passes.

## Phase 3: Wiring (`Brain`, `MainActivity`, settings)

**Files:**
- Modify: `Brain.kt`, `MainActivity.kt`, `settings/Settings.kt`, `settings/SettingsPanel.kt`
- Modify: `res/layout/view_settings.xml`, `res/values/strings.xml`

- [ ] `Brain` implements `VisionPipeline.Listener`, owns a `FacePresence`, and remembers `lastGreeting`.
- [ ] `onFaces` calls `presence.feed(faces, atMs, busy = !canGreet())`. An arrival calls `greet(it)`.
- [ ] `canGreet()` is true only when all of these hold:
  - `state == IDLE`;
  - conversation is on;
  - vision is on;
  - `eleven != null`;
  - the queue is empty;
  - `playing == null`.
- [ ] `greet(a)` calls `say(Greetings.pick(a, lastGreeting), now = false)` and logs `greeting N person(s) (smiling): "<text>"`. It reuses the console path: faces, mouth, watchdog, tap-to-stop, `/stop` and `/state`.
- [ ] `setForeground(false)` and `stop()` call `presence.reset()`.
- [ ] If there is no ElevenLabs key, log once that there will be no greetings.
- [ ] `MainActivity`:
  - Replace the mic request with `RequestMultiplePermissions(RECORD_AUDIO, CAMERA)`.
  - If the mic is granted, call `boot()` as today.
  - If the camera is granted and vision is on, call `vision.start()`.
  - If the camera is denied, log it and change nothing else.
- [ ] Settings:
  - `Settings.vision`, key `"vision"`, default `true`.
  - A **Camera** row with Off / Greet pills, after Conversation, built like the Conversation row.
  - A status line under it: *watching*, *off*, *no camera permission*, *no front camera*, *no ElevenLabs key*, or `vision.problem`.
- [ ] `applySettings()` starts or stops the pipeline. Off unbinds the camera, so the privacy light goes out.
- [ ] `onDestroy()` calls `vision.stop()`.
- [ ] Unit tests still pass, and `assembleDebug` passes.

## Phase 4: On the tablet

- [ ] `./gradlew :app:installDebug`. Both permission prompts appear on first launch.
- [ ] Walk up and look at ACMO: it greets once.
- [ ] Walk past side-on: no greeting.
- [ ] Stand still after a "hey ACMO" chat: no greeting when it ends.
- [ ] A second person after 20 s: greeted.
- [ ] A group: one group line.
- [ ] A console line during a greeting queues. *Say now* cuts the greeting off.
- [ ] Camera Off: the privacy light goes out and there are no greetings.
- [ ] Check the tablet's temperature after 15 minutes idle with the camera on.
- [ ] Tune `MIN_WIDTH`, `MAX_YAW` and `DWELL_MS` in `FacePresence` from what you see. Record the values in spec §7.

## Phase 5: Docs

- [ ] `README.md`: a *Camera* section covering what it does, that no image leaves the device, and where the lines and thresholds live.
- [ ] `CLAUDE.md`:
  - add `Vision` to the logcat line;
  - add a line about `vision/` under Architecture.
- [ ] Spec §7: what changed while building.

## Later

These come after greeting works (spec §8):

- Gemini-written greetings. `Personality.SYSTEM` currently says "You cannot see", so it needs updating.
- Greet & listen.
- Eye tracking.
- A face count in `/state`.
- Turning the wheels toward people.
