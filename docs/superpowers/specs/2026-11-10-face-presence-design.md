# Face presence and greeting — design

*2026-11-10. Approved in conversation; this is the written form.*

ACMO notices when someone walks up and looks at it, and says hello without
waiting for "hey ACMO". The tablet's front camera feeds ML Kit's face detector
(the bundled model, so everything runs on the device and nothing needs Google
Play Services). No image ever leaves the tablet or is stored.

The first version is deliberately simple to test: the hello is one of a few
**fixed lines** spoken in the ElevenLabs voice, through the same queue the
remote console uses. Gemini is not involved. Once detection is shown to work
on the robot, the lines can be handed to Gemini (§8).

```
 front camera            vision/VisionPipeline           Brain (main thread)                 ElevenLabs
 ┌──────────┐  640x480   ┌──────────────────────┐ Seen[] ┌───────────────────────────────┐ text ┌───────────┐
 │ CameraX  │ ─────────▶ │ ImageAnalysis ≤5 fps │ ─────▶ │ FacePresence (pure)           │ ───▶ │ flash_v2_5│
 │ front    │ YUV frames │  └▶ ML Kit FaceDet.  │  main  │  └▶ Arrival → greet()         │ ◀─── │ pcm_24000 │
 └──────────┘            └──────────────────────┘        │     → say(Greetings.pick(…))  │ audio└───────────┘
                                                         │     IDLE → SPEAKING → IDLE    │
                                                         └───────────────────────────────┘
```

Decisions taken during the design, in one place:

| Question | Decision |
| --- | --- |
| Detector | ML Kit face detection, **bundled** (`com.google.mlkit:face-detection`). Not the Play Services variant, which downloads its model on first use; not MediaPipe (no tracking IDs, short-range model only). |
| Camera | CameraX `ImageAnalysis` only: no preview, since the screen is the face. |
| What counts as "someone walked up" | One tracked face, big enough (close), facing the tablet, held for a moment. Passers-by and side-on faces do not count. See §2. |
| What ACMO says | A **fixed line**, picked from a short list, spoken word for word in the ElevenLabs voice. No Gemini, no cost per greeting, about 0.4 s to first sound. |
| After the greeting | Back to waiting for the wake word. The mic is never opened by the camera. |
| Modes | **Off** and **Greet**, default **Greet**. "Greet & listen" waits until greeting is proven (§8). |
| When it may greet | Only in `IDLE`, only with Conversation on, never over a console line or a queue. |
| Privacy | Frames are analysed in memory and closed; none are saved, logged or sent. |
| Toolchain | Versions must work with AGP 8.13.2 / compile SDK 36; checked first (§5, step 0). |

## 1. The camera — `vision/VisionPipeline.kt`

Owned by `MainActivity`, like `RemoteServer`, because CameraX binds to a
`LifecycleOwner`. Binding to the activity means the camera stops on its own in
`onStop` and restarts in `onStart`, matching `Brain.setForeground`.

```kotlin
class VisionPipeline(private val context: Context, private val owner: LifecycleOwner, private val listener: Listener) {
    interface Listener {
        /** Main thread. Every analysed frame, including frames with no faces. */
        fun onFaces(faces: List<Seen>, atMs: Long)
        /** Main thread. No front camera, the camera failed, or the detector failed to load. */
        fun onVisionError(message: String)
    }
    fun start()   // binds; idempotent
    fun stop()    // unbinds, closes the detector; idempotent
}
```

- **Camera**: `ProcessCameraProvider`, `CameraSelector.DEFAULT_FRONT_CAMERA`.
  No front camera → `onVisionError("no front camera")`; ACMO carries on without vision.
- **Analysis**: `ImageAnalysis` with a `ResolutionSelector` asking for about
  640×480, `STRATEGY_KEEP_ONLY_LATEST`, and one background thread
  (`Executors.newSingleThreadExecutor`, named `acmo-vision`).
- **Rate**: the analyser drops any frame that arrives within `FRAME_GAP_MS`
  (200 ms) of the last one it analysed, so ML Kit runs at most 5 times a
  second. Presence does not need more, and the tablet is already running Vosk,
  the Live socket and the face animation. Our own `ImageAnalysis.Analyzer` is
  used instead of CameraX's `MlKitAnalyzer` because the latter has no way to
  skip frames.
- **Detector** options: `PERFORMANCE_MODE_FAST`, `LANDMARK_MODE_NONE`,
  `CONTOUR_MODE_NONE` (contours switch tracking IDs off), `CLASSIFICATION_MODE_ALL`
  (smile probability), `enableTracking()`, `setMinFaceSize(0.1f)`.
- **Per frame**: `InputImage.fromMediaImage(image, rotationDegrees)` (so
  portrait or landscape both work), then `detector.process(…)`. The `ImageProxy`
  is closed in `addOnCompleteListener`, success or not; a frame left open stalls
  the camera. ML Kit's listeners run on the main thread by default, which is
  where `Brain` wants them.
- **Mapping**: each ML Kit `Face` becomes plain data, so nothing past this file
  touches ML Kit or Android types:

```kotlin
data class Seen(
    val id: Int?,          // ML Kit tracking ID; null if tracking lost it
    val width: Float,      // bounding box width / image width, 0..1 (after rotation)
    val yaw: Float,        // headEulerAngleY, degrees; 0 = facing the camera
    val pitch: Float,      // headEulerAngleX, degrees
    val smiling: Float?,   // 0..1, or null when ML Kit could not tell
)
```

- `getMediaImage()` needs `@OptIn(ExperimentalGetImage::class)`.
- A detector failure on one frame is logged at debug and skipped. Ten in a row
  → `onVisionError`, and the pipeline stops (it does not retry in a loop).
- Logs under the tag `Vision`: the face count whenever it changes, never per frame.

## 2. Deciding someone arrived — `vision/FacePresence.kt`

Pure Kotlin: no Android, no ML Kit, a clock passed in. This is where all the
judgement lives, and it is unit-tested on the JVM like `WakeWord` and `MouthGate`.

```kotlin
data class Arrival(val people: Int, val smiling: Boolean)

class FacePresence {
    /** One analysed frame. Returns an Arrival at most once per person. */
    fun feed(faces: List<Seen>, atMs: Long, busy: Boolean): Arrival?
    fun reset()   // forgets dwell timers, keeps who was greeted and the cooldown
}
```

A face **qualifies** when:

- it has a tracking `id`;
- `width >= MIN_WIDTH` (0.12 of the frame: roughly arm's length to 1.5 m on a
  tablet's front camera; tune on the robot);
- `|yaw| <= MAX_YAW` (25°) and `|pitch| <= MAX_PITCH` (20°), so it is looking
  at ACMO, not walking past.

An **Arrival** is returned when a qualifying `id`:

- has qualified for `DWELL_MS` (800 ms) without a gap longer than `LOST_MS`
  (1 500 ms) (a blink or a missed frame does not restart the clock);
- has not been greeted before;
- and `COOLDOWN_MS` (20 s) have passed since the last Arrival. This also
  covers ML Kit giving a returning face a new ID.

When it fires, every ID currently in view is marked greeted (a group of three
gets one hello, not three). `people` is the number of qualifying faces;
`smiling` is true if any of them has `smiling >= 0.7`.

**`busy = true`** (ACMO is not `IDLE`): nothing fires, and every ID in view is
marked greeted. The person who said "hey ACMO" and is still standing there
must not get a hello when the answer ends.

Greeted IDs are forgotten once unseen for `FORGET_MS` (60 s), so the set stays small.
All thresholds are constants in the companion, with a comment on what to tune.

## 3. The lines — `vision/Greetings.kt`

Pure, like `Tags`. A short list of `Line(text, feeling)` in three groups:

- **one person**, e.g. `"Hey there! Say hey ACMO if you want to chat."`, `happy`;
- **a group**, e.g. `"Oh, a whole crowd! Say hey ACMO and let's talk."`, `excited`;
- **a smiler**, e.g. `"Nice smile! I'm ACMO. Say hey ACMO to talk to me."`, `happy`.

The group is chosen from the `Arrival`, with group first, then smiling, then one
person. `fun pick(a: Arrival, last: String?, random: Random): Line` never repeats
the previous line when the group has more than one. The lines are plain text
with no `[tags]`, so they go to `eleven_flash_v2_5` and start fastest. Every line
ends by inviting "hey ACMO", because the greeting itself does not listen. The
exact wording is for the team to write; the code takes whatever is in the list.

## 4. `Brain`

`Brain` implements `VisionPipeline.Listener` and owns one `FacePresence`.

**`onFaces(faces, atMs)`**: `presence.feed(faces, atMs, busy = !canGreet())`; an
`Arrival` → `greet(it)`. `canGreet()` is all of:

- `state == IDLE`
- `conversation` on
- vision mode is Greet
- `eleven != null`
- `queue.isEmpty()` and `playing == null`

**`greet(arrival)`**: `say(Greetings.pick(arrival, lastGreeting, random), now = false)`,
the console's own entry point. ACMO is idle, so the line plays at once. Log
line: `Brain: greeting 1 person (smiling): "<text>"`. Reusing `say` means the
following all come for free:

- the operator's face per line;
- face cues;
- mouth movement;
- the watchdog;
- `lineFailed` (sad face for a moment, then idle) when ElevenLabs fails;
- tap-to-stop;
- `/stop`;
- a console line sent during a greeting queues behind it, or cuts it off with `now`;
- `GET /state` shows the greeting as the line playing, which helps when tuning
  from the console.

Without an ElevenLabs key there is no greeting. This is logged once at start,
like the missing-key warning for the console.

The Gemini session is not touched, so a "hey ACMO" after the hello starts a
normal conversation, which does not know a hello was said.

## 5. Build, permissions, settings

**Step 0: versions (before any code).** Add to `gradle/libs.versions.toml`:

```toml
camerax = "1.6.1"          # latest stable at the time of writing (May 2026)
mlkitFace = "16.1.7"       # bundled face detection
```

and `camera-core`, `camera-camera2`, `camera-lifecycle`, `face-detection` to
`app/build.gradle.kts`. Then run `./gradlew :app:assembleDebug` and
`./gradlew :app:dependencies --configuration debugRuntimeClasspath`:

- `checkDebugAarMetadata` fails if anything wants compile SDK 37 or AGP 9;
- the dependency tree shows whether `androidx.core`, `lifecycle` or `activity`
  were silently upgraded past the pins.

If CameraX 1.6 pulls in something too new, step down to the latest 1.5.x, then
1.4.x. Record the version that worked in §7. `abiFilters` stays
`armeabi-v7a, arm64-v8a`: the bundled face library ships both. Measure the
APK size before and after and note it (the Vosk model already makes it ~40 MB).

**Manifest.**

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
<uses-feature android:name="android.hardware.camera.front" android:required="false" />
```

The `required="false"` lines matter: without them, declaring `CAMERA` makes
the camera a requirement and a camera-less device could not install ACMO.

**Permission flow.** `MainActivity` swaps `RequestPermission(RECORD_AUDIO)`
for `RequestMultiplePermissions(RECORD_AUDIO, CAMERA)`, so both prompts appear
together on first launch. Mic granted → `boot()` as today. Camera granted →
`vision.start()`. Camera denied → vision stays off, logged, and the settings
card says so. Nothing is spoken, since vision is optional, as with a missing
ElevenLabs key.

**Settings.** `Settings.vision: Boolean`, key `"vision"`, default **true**
(Greet). The card gets a **Camera** row after Conversation, with *Off · Greet*
pills and one status line:

- *watching*
- *off*
- *no camera permission*
- *no front camera*
- *no ElevenLabs key*
- the last `onVisionError` message

`applySettings()` starts or stops the pipeline. Off unbinds the camera
entirely, so the privacy light goes out. Strings in `strings.xml`.

## 6. Files, docs, tests

```
app/
├── build.gradle.kts                          CameraX + ML Kit face detection
└── src/main/
    ├── AndroidManifest.xml                   CAMERA, camera features not required
    └── java/com/acmqu/acmo/
        ├── Brain.kt                          VisionPipeline.Listener, FacePresence, canGreet(), greet()
        ├── MainActivity.kt                   owns VisionPipeline; both permissions
        ├── vision/VisionPipeline.kt          new: CameraX + ML Kit -> Seen
        ├── vision/FacePresence.kt            new: Seen, Arrival, the rules in §2
        ├── vision/Greetings.kt               new: the fixed lines and pick()
        └── settings/Settings.kt, SettingsPanel.kt, res/layout/view_settings.xml, res/values/strings.xml
gradle/libs.versions.toml
```

**Tests** (JVM, JUnit 4):

- `FacePresenceTest`:
  - a face that qualifies for 800 ms arrives once;
  - one that leaves at 600 ms does not;
  - side-on (yaw 40°) or far (width 0.05) never arrives;
  - a 1 s dropout keeps the dwell, a 2 s one restarts it;
  - the same ID never arrives twice;
  - a second person inside 20 s waits for the cooldown;
  - a group arrives once with the right `people` and `smiling`;
  - faces seen while `busy` never arrive afterwards;
  - `reset` keeps greeted IDs.
- `GreetingsTest`:
  - each arrival gets a line from the right group;
  - no line repeats twice in a row;
  - no line contains a `[tag]`;
  - every feeling is a real `Expression`.

**By hand on the tablet**, reported as observed:

- the permission prompts;
- walk up and look, which should greet once;
- walk past side-on, which should not;
- stand there after a "hey ACMO" chat, which should not re-greet;
- a second person after the cooldown is greeted;
- a console line during a greeting queues, and with *Say now* cuts it;
- Camera Off turns the privacy light off;
- `adb logcat -s Brain Vision` while doing it;
- the tablet's temperature after 15 minutes idle with the camera on.

Tune `MIN_WIDTH`, `MAX_YAW` and `DWELL_MS` from what is seen, and record the
values in §7.

**Docs**:

- `README.md` gets a *Camera* section: what it does, that nothing leaves the
  device, where the lines and tuning constants live.
- `CLAUDE.md` gets `Vision` in the logcat line and a line under Architecture.

## 7. Implementation notes

Filled in while building: the CameraX version used, the APK size change, the tuned thresholds.

## 8. Later, once greeting works

- **Gemini-written greetings**: a bracketed note (`[2 people just walked up,
  one is smiling]`) sent as a text turn, so the hello is varied and a following
  "hey ACMO" remembers it. Needs `Personality.SYSTEM` changed: it says *"You
  cannot see"*.
- **Greet & listen**: after the hello, open the mic as if the wake word had been
  heard, and go quietly back to idle if nobody speaks.
- Eye tracking: the face's pupils follow the nearest face. `Seen` would gain the
  box centre, which ML Kit already provides.
- The face count in `GET /state` and the console.
- Turning the wheels toward a person: needs a new serial message and firmware
  in `hardware/wheel-control`.
- Recognising returning people is deliberately out of scope, since it would
  mean storing face data.
