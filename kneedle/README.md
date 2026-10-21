# kneedle

Natural language in, Arduino serial out.

You type `turn the LED on`. A local model (`needle`) picks the matching tool
from `tools.json`, a Kotlin script maps that tool to a single character, and
that character goes down the serial port to a board that acts on it.

```
prompt -> needle -> tool name -> letter -> serial -> Arduino
```

Nothing leaves the machine: `needle` runs an embedded model, no API key, no
network after the first dependency fetch.

## Files

| file | role |
| --- | --- |
| `script.main.kts` | the driver: prompt, dispatch, serial write |
| `tools.json` | the tools the model may choose from |
| `needle` | the model binary (aarch64 Android / Windows) |


## Run

```sh
kotlin script.main.kts "turn the LED on"   # one command
kotlin script.main.kts                     # interactive prompt
```

Must be named `script.main.kts` and run with `kotlin`, not `kotlinc -script` —
the `@file:DependsOn` lines only resolve under the main-kts definition. Jars
come from Maven Central on first run, then cache.

| variable | default |
| --- | --- |
| `NEEDLE_HOME` | cwd — the directory holding `needle` and `tools.json` |
| `ARDUINO_PORT` | first port found |
| `ARDUINO_BAUD` | 9600 — must match `Serial.begin()` |

## Current tools

| tool | sends | firmware does |
| --- | --- | --- |
| `spin` | `s` | spin in place |
| `dance` | `d` | dance |

## Adding a tool

1. Declare it in `tools.json` — the description is what the model matches on.
2. Add a handler under the same name in the `tools` map in `script.main.kts`,
   returning the character to send.
3. Handle that character in the sketch.

A name declared in `tools.json` with no handler is reported, not ignored, and
the same goes for a name the model returns that isn't declared.

Two caveats worth knowing:

- **Order matters.** The model leans toward tools listed later. With `LED on`
  before `LED off`, "turn the light on" resolved to `LED off`. Swapping them
  fixed it. Keep near-opposite pairs apart, and put the one that loses out
  last.
- **Descriptions carry the weight.** Write them with the words a user would
  actually say, not just the mechanism.
