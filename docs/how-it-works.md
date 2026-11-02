# Wireless Display Receiver: how it works

Wireless Display Receiver turns a Google TV into a **Miracast (Wi-Fi Display) receiver**, made for **Samsung DeX
wireless** and for plain phone screen mirroring. (`lt.tvcast.displayreceiver`). It is an independent project: not made by or affiliated with Samsung, Google or Sony,
whose product names are used here only to say what it works with.

## Why it exists

Android has no Miracast receiver of its own. The Sony Google TV this was built on has one in its
firmware, but it never starts a session: the Wi-Fi Direct events it waits for
(`PEERS_CHANGED`, `CONNECTION_STATE_CHANGE`) are only delivered to apps that hold
`ACCESS_FINE_LOCATION`, and the Sony component does not. A phone therefore finds the TV, joins its
Wi-Fi Direct group, waits 30 seconds for the TV to connect, and gives up.

An ordinary app *can* hold that permission, so Wireless Display Receiver does what the firmware should have done.

## Using it

1. **5 GHz mode (optional).** By default the app uses the standard mode (the Wi-Fi Direct group is on
   2.4 GHz, the phone sends 1080p) and never touches adb. For 5 GHz and 2560x1440, turn on USB
   debugging in the TV's developer options and press **5 GHz mode** on the app's screen (see
   [5 GHz and 2560x1440](#5-ghz-and-2560x1440)). The first time, the TV asks "Allow USB debugging?":
   press Allow. 5 GHz mode lasts for one visit: pressing Stop or leaving the app (Home, Back, the TV
   going to sleep) switches it off again and deletes the app's adb key, so the next time you turn it on a
   new key is made and the TV asks once more. Setting up takes about a minute ("Getting 5 GHz ready"). If USB debugging is off the screen
   says so and the app keeps working at 1080p. The button does not change anything at once: it opens
   a screen that says why the switch exists, what the app will do and that it is safe to try, with two
   pictures (1080p against 2560x1440, and the three steps); the change is made from that screen.
2. Open **Wireless Display Receiver** on the TV. It does not search until you press **Start** (the Wi-Fi Direct search is off whenever the screen is left, so the app is never listening in the background); then it shows "Ready, waiting for your phone". The TV name, as the phone will show it, is on the screen.
3. On a Samsung phone: Quick settings → **Samsung DeX** (or **Smart View**) → pick the TV.
4. The picture and sound start in a second or two and take the whole screen. Back ends the session.

The **How to use** button opens an illustrated version of steps 2-4 (three pictured cards and a hint about the mouse pointer). Back or Close returns to the main screen.

The app needs the location permission once. On an Android TV without a permission dialog:
`adb shell pm grant lt.tvcast.displayreceiver android.permission.ACCESS_FINE_LOCATION`.

Build and install:

```powershell
.\build.ps1                      # -> build\displayreceiver.apk
adb install -r build\displayreceiver.apk
```

## How it works

In Miracast the **phone (source) runs the RTSP server on TCP 7236 and the TV (sink) connects to
it**, the opposite of what one would guess.

```
phone                                        TV (Wireless Display Receiver)
  | Wi-Fi Direct group formed  <------------>  |  (either side may own the group)
  | <-- TCP connect to 7236 -------------------|  all 253 addresses of the group are tried at once
  | M1 OPTIONS ------------------------------> |  we answer, then send our own M2 OPTIONS
  | M3 GET_PARAMETER (what can you show?) ---> |  we answer with formats, audio, ports
  | M4 SET_PARAMETER (this format) ----------> |
  | M5 trigger SETUP ------------------------> |  we send SETUP (RTP over TCP or UDP), then PLAY
  | HEVC or H.264 + AAC in MPEG-TS over RTP    |  RTCP receiver reports go back (UDP only)
```

| Class | Job |
|---|---|
| `MainActivity` | The screen; keeps a Wi-Fi Direct search running; finds the phone's address and connects; holds a low-latency Wi-Fi lock |
| `RtspSink` | The RTSP conversation, the RTP/RTCP sockets (UDP, or TCP with a two-byte length in front of each packet), reading on one thread and decoding on another |
| `TsPlayer` | MPEG-TS demux, HEVC/H.264 decoder to a `TextureView`, AAC decoder to an `AudioTrack` |
| `Guide`, `PictureView` | The two full-screen explanations (How to use, 5 GHz mode) and the code-drawn pictures in them; no image files |
| `SelfAdb` | ADB client for the TV's own adb daemon, used to give the Wi-Fi a country code (see below) |

### Looking like a Samsung TV

A Samsung phone judges the receiver by one RTSP header, `user-agent: SVDTZ8_0/SEC-UIBC3` (a Samsung
TV sends it; the phone logs `tizen main version = 8`). Only then it asks for the Samsung parameters
(`wfd2_video_formats`, `wfd_sec_dex_support`, ...) and offers its faster modes: **HEVC** instead of
H.264, **RTP over TCP** instead of UDP, and higher resolutions. The receiver sends that header and
answers those parameters (the video list is the one a Samsung TV gives). It does not claim HDCP.

## 5 GHz and 2560x1440

With a Samsung TV the phone picks `2560 x 1440 p30` and 10 Mb/s; with this TV it first picked
`1920 x 1080 p30` and 4 Mb/s. The difference is the **channel the Wi-Fi Direct group ends up on**:

- The TV had **no Wi-Fi country code** (`cmd wifi get-country-code` says `null`). An Android TV without a SIM
  takes it from "telephony", so nothing ever set it. Without a country the regulatory rules are the
  world defaults, where every 5 GHz channel is passive-scan only: the TV may join a router there but
  may not start a Wi-Fi Direct group. All groups (TV owner or phone owner, any combination) came up on 2.4 GHz,
  `GROUP_OWNER_BAND_5GHZ` failed, and the phone offered only 1080p.
- The shell can fix that without root: the telephony broadcast
  `android.telephony.action.NETWORK_COUNTRY_CHANGED` can be sent from the adb shell and the Wi-Fi stack
  takes the country from it; it is applied when the Wi-Fi is restarted (`mDriverCountryCode: LT`).
  The group then comes up on the channel of the TV's own Wi-Fi (5180 MHz) and the phone picks 2560x1440.
- **The app does it by itself, if you switch 5 GHz mode on** (it is off by default, and stays off until
  you press the button; off, the app never uses adb). `SelfAdb` is a small ADB client that talks to the TV's own adb
  daemon (loopback address, port 5555), so USB debugging must be on and allowed once. The country comes from the
  TV's language. On start the app asks `cmd wifi get-country-code`; if the country is missing it
  sends the broadcast, restarts the Wi-Fi (adb survives that, it is on the loopback), waits for Wi-Fi
  and only then starts looking for a phone. The key it signs in with is in the app's private storage.
  **While USB debugging is on, that key lets this app run shell commands on the TV**; the daemon on
  the network still only accepts authorised keys. `enable-5ghz.ps1` does the same from a PC.
- **The code is kept in memory only**, so it is set again after every TV restart (that is the minute
  the app spends on "Getting 5 GHz ready"). The shell cannot write it permanently
  (`force-country-code`, `setDefaultCountryCode` and `iw reg set` are refused), and enabling Google's
  setup app (which Sony reads the country from) does not pass it on to the Wi-Fi.

While the TV's Wi-Fi is on 2.4 GHz the group follows it (same channel); with the TV on 5 GHz and no
country code, one radio had to hop between two channels, which showed up as 100-200 ms pauses every
few hundred ms. Both are fixed by the country code.

## What else limits the picture, and what was done about it

Measured on a Sony KD-50X80J (MediaTek, Android 12) with a Galaxy S23 FE.

| Finding | Detail |
|---|---|
| 60 fps is not offered | Offering only 1080p60, 720p60 or only 1440p to a phone that does not allow it ends the session (`Sink and source share no commonly supported video formats`); with a wide list the phone picks p30. Even a Samsung TV gets p30 |
| The display path garbled 1440p | The TV's own video plane drew 2560x1440 with the wrong line pitch (picture doubled sideways, lower half noise) although a screenshot looked fine. A `TextureView` (GPU composition) shows it correctly |
| Never drop a picture | Every later picture is built on the previous ones. When the decoder had no free input the first version dropped the frame; the image then smeared until the next key frame. Now it waits and, if it still cannot, asks the source for a key frame (`wfd_idr_request`) |
| Output on its own thread | Pictures leave the decoder on a separate thread; reading the network, demuxing and decoding input are separate from it |
| Level in the SPS (H.264) | A decoder sizes its picture buffer from the SPS level (level 4.0 = four 1080p pictures, ~3 held back: ~160 ms). Rewriting the level byte to 3.1 gave ~60 ms. Level 3.0 was slower. (HEVC does not need it: ~3 ms) |
| Show pictures with a small, adaptive delay | Showing each picture at its own timestamp plus a delay that follows the measured jitter (0-60 ms, reference creeping 0.5 ms/s so the phone's clock cannot drift away); showing on arrival judders |
| Frames end by length | The phone's video PES packets carry their length, so a frame is handed on when complete |
| Wi-Fi power saving | A low-latency Wi-Fi lock is held while the app is in front; without it the link delay grew from ~4 ms to 20-50 ms a few minutes into a cast |
| RTCP | Receiver reports are sent to the source's RTCP port every second (UDP sessions). The phone did not visibly react |

Tried and removed: rewriting the whole SPS VUI, an access-unit delimiter after every picture, a software
decoder, a frame-rate-control field, `wfd_sec_framerate: 60`, timing pictures only by the phone's clock,
creating a 5 GHz group from the app, asking the phone to own the group, forgetting saved Wi-Fi Direct groups.

## Security

The other end of the stream is a phone on a Wi-Fi Direct link, but the code treats it like any device
on a network. This was reviewed by reading the code, by checks on the TV (`ss` shows what is
listening) and by unit tests; findings from the review were fixed.

| Concern | What the receiver does |
|---|---|
| Who can send video | The RTP sockets are bound to the TV's address **on the Wi-Fi Direct link only**, not to every network the TV is on (checked: the listener is on that one address). Over TCP, any connection that is not from the source of the RTSP session is closed; over UDP, packets from any other address are dropped. A device on the home network cannot show a picture on the TV |
| RTSP messages | Headers are limited to 16 kB and bodies to 256 kB; a negative, huge or non-numeric `Content-Length` ends the session as an `IOException` (an exception of another kind used to be able to end the app). Only plain parameter names (`[A-Za-z0-9_]`, up to 64 characters) are echoed back, so nothing from the phone ends up inside a message as a line break or a header |
| Malformed streams | A packet that cannot be decoded is counted and skipped (on the decoding thread, so it cannot end the app); TS and PES lengths are checked; input to the decoder is cut to its buffer; the TCP packet length is at most 64 kB; the RTP connection has a timeout and is closed with the session |
| Looking like a Samsung TV | Only the RTSP `User-Agent` and the answers a Samsung TV gives. No credentials or secrets are involved, and **HDCP is never claimed**, so the phone sends nothing that needs decrypting |
| Anyone in the Wi-Fi Direct group | The TV's own firmware accepts Wi-Fi Direct invitations automatically (it did in the logs). A device that joins the TV's group and listens on the RTSP port could show a picture on the TV; it gets nothing else from the app. Not fixed: the app cannot see who joined, only which address answers |
| 5 GHz mode | **Off by default**; the app never uses adb until the button is pressed. On: it needs USB debugging and the TV's "Allow USB debugging" prompt. It talks to the TV's own adb daemon on the loopback address only, runs a fixed set of commands (`cmd wifi get-country-code`, one `am broadcast`, `cmd wifi set-wifi-enabled`, `cmd wifi status`, `dumpsys wifi`), the country code is two ASCII letters checked before it goes into a command, shell output is cut at 64 kB and every adb message is size-checked |
| The adb key | A 2048-bit RSA key in the app's private storage (mode 600, no backup: `allowBackup` is false). It is **deleted when 5 GHz mode is switched off, and 5 GHz mode switches itself off when the app is stopped or left** (or started again after being killed), so the shell access exists only while the screen is open and the mode was turned on that visit; without it the app can no longer sign in, even though the TV still lists the key (switching on again makes a new key and the TV asks again). While 5 GHz mode and USB debugging are both on, the key lets this app run shell commands: a bug in the app that gave someone control of it would give them the shell, and the app reads untrusted network data in the same process. That is why the mode is optional and why the command set is small |
| What it changes on the TV | Nothing permanent. The Wi-Fi country code is held in memory until the TV restarts (the Wi-Fi restart disconnects it for about 20 seconds). The app does not touch other settings, apps or accounts. It is a regulatory setting, and the country comes from the TV's language |
| Permissions | Location (Wi-Fi Direct needs it on this Android), network and Wi-Fi state, `CHANGE_WIFI_STATE`, `INTERNET` (sockets), `WAKE_LOCK` (low-latency Wi-Fi lock). No storage, camera, microphone or contacts |
| Logs | `logcat` shows the RTSP conversation and the phone's Wi-Fi Direct address. It is readable only over adb |

**Tests** (`tests/java/lt/tvcast/displayreceiver`, 35 Java tests): the ADB header layout, wrong magic and
data lengths out of range are rejected; the token that is signed (SHA-1 DigestInfo and the token) and that the signature
verifies for that token and not for another; the public key structure, read back and checked by the maths the daemon
uses it for; RTSP messages split at every byte, two in one read, bodies at the limit, bad lengths, an endless header, bytes
that are not text; the capabilities announced (never HDCP, unknown names answered `none`, odd names not echoed, the port
given); the country code check including shell characters and the Turkish locale. **Not covered by unit tests:** the MPEG-TS
demux and the decoders (they use `MediaCodec`), the screen, and the Wi-Fi itself; those were checked on the TV with a
Samsung phone and by photographing the screen.

## Not done

- **Mouse and touchpad.** DeX shows the phone as a touchpad only after the "Samsung DeX is running"
  notification is tapped. The touchpad activity is not exported, so neither the TV nor `adb` can
  open it. A phone-side automation (accessibility service) would be needed.
- **Higher bitrate or 60 fps.** Decided by the phone. Properties such as `media.wfd.video-bitrate`
  exist in the phone's library but cannot be set without root.
- **HDCP, UIBC input and rotation** are answered `none`.

## Prior art

A short web search (November 2026) found no write-up of the three less common parts of this app:
reporting a Samsung TV identity to get HEVC 2560x1440, sending the telephony country broadcast from
the adb shell so Wi-Fi Direct may use 5 GHz, and an app talking to its own TV's adbd over the loopback
address. That only means the search found nothing; similar notes may well exist in forums or issue
trackers it did not reach, so this project makes no claim to be the first.
## Clues for further work

The phone's library `libremotedisplay_wfd.so` can be pulled with `adb pull` and read with
`strings`. It lists the sink parameters a Samsung receiver sends (`wfd_sec_sink_resolution`,
`wfd_sec_tv_panel_resolution`, `wfd_sec_low_latency_mode`, `wfd2_sec_version`). The phone's own
`WifiDisplaySource` log shows the whole conversation with a real Samsung TV, which is how the
values above were found. Another receiver for DeX (`natarajan0007/DeXBridge`) documents a different
TV; it does not deal with the channel.
