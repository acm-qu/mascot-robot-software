# ACMO remote

A page for a laptop on the same network as the robot: type a line, pick a
face, and ACMO says it in an ElevenLabs voice. The tablet does the talking
(`remote/RemoteServer.kt` and `voice/ElevenLabs.kt` in the app); this is only
the console. Needs Node 20.9 or newer.

```sh
adb forward tcp:8765 tcp:8765   # the tablet's server, as localhost:8765 -- over USB or adb-over-Wi-Fi
npm install
npm run dev                     # http://localhost:3000
```

Not on adb? Five taps in the top-left corner of the face open ACMO's settings
card; the address under **Remote console** (`http://10.20.55.42:8765` or so)
goes in the box at the top of the page. It is remembered.

- **Enter** queues the line; **Shift+Enter** is a newline; **⌘/Ctrl+Enter**
  says it now, cutting off whatever ACMO is doing; **Esc** stops everything.
- The status under the box is polled from the tablet twice a second: what is
  playing, what is queued, and why the last line failed, if it did.
- This folder is not part of the Gradle build (`settings.gradle.kts` includes
  only `:app`). If Android Studio indexes `node_modules`, right-click the folder
  → *Mark Directory as* → *Excluded*.
