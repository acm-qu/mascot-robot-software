# Third-party notices

ACMO is MIT licensed (see `LICENSE`). It ships or downloads these:

| Component | Where | License |
| --- | --- | --- |
| [Vosk](https://alphacephei.com/vosk/) `vosk-android` 0.3.75 and the `vosk-model-small-en-us-0.15` model, downloaded at build time into `app/src/main/assets/model-en-us/` | wake word | Apache-2.0 |
| [JNA](https://github.com/java-native-access/jna) 5.18.1 | Vosk's bridge to its native library | Apache-2.0 / LGPL-2.1 (dual) |
| [OkHttp](https://square.github.io/okhttp/) 4.12.0 | HTTP to Gemini | Apache-2.0 |
| [JetBrains Mono](https://www.jetbrains.com/lp/mono/) Bold, `app/src/main/res/font/jetbrains_mono_bold.ttf` | the face's glyphs | SIL OFL 1.1 — `third_party/JetBrainsMono-OFL.txt` |

The face itself is a port of the *Interactive robot face demo* design, built on
the ACM QU design system (`_ds/acm-qu-design-system` in that project), whose
tokens come from [acm-qu/ACM-Landing-Page](https://github.com/acm-qu/ACM-Landing-Page).
