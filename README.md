# NetPulse

A network signal monitor for Android, similar to G-NetTrack Lite. It reads live cell info from the phone modem.

## Tabs
- **CELL** – Operator, MCC/MNC, LAC/TAC, Node (eNB/RNC/gNB), CID, PCI/PSC, TA, ARFCN, band, bandwidth, DL/UL frequency, RSRP/RSRQ/SNR/RSSI with colours, a live signal graph, GPS, UL/DL speed, data state, serving time, and an event log (START / HO / IRAT / NO SVC).
- **NEI** – The serving cell, the other SIM's serving cell, and all neighbour cells sorted by signal.
- **MAP** – An OpenStreetMap map with your track. Each point is coloured by signal level.
- **INFO** – SIM, operator, roaming, data network and device info.
- **DRIVE** – Start/stop drive-test logging. Writes one CSV row per second. Tap a saved log to share it.

## Cell name & address
- **Cell name** – Tap "Cell name" on the CELL tab to give the current cell a name (for example "Mirpur-10 Tower"). Names are saved on the phone and appear on the CELL, NEI and MAP tabs and in drive logs.
- **Tower address** – Get a free API key at opencellid.org and enter it in INFO → "Set OpenCelliD API key". The app then finds the tower's location and street address, shows how far away it is, and marks it on the map with a red triangle. Results are cached.
- **Your area** – The address of your own GPS position. Needs no key.

Works with every Bangladesh operator — Grameenphone, Robi, Airtel, Banglalink and Teletalk — each shown by name and brand colour. Supports GSM, WCDMA, LTE and 5G NR, and dual SIM (use the **SIM ⇄** button).

## Version 2.0 (Phase 1)
- **Background logging**: drive tests keep running with the screen off. A notification shows live signal and a Stop button.
- **5G NSA / SA, 4G+ and carrier aggregation**: shows what the status bar shows (4G+, 5G NSA, 5G+) and the component carriers (for example "3 CC: 20 + 15 + 10 MHz").
- **Material 3** dark and light themes, and a two-column layout on tablets.
- **5 languages**: English, বাংলা, हिन्दी, العربية (right-to-left), Bahasa Indonesia. You can switch language inside the app.
- **Settings**: theme, language, units (metric or imperial), refresh interval, map style, keep screen on, OpenCelliD key, privacy policy.
- **Play Store ready**: targets API 36, edge-to-edge layout, permission explanation screen, privacy policy, and a signed AAB from GitHub Actions. See `PLAY_STORE.md`.

## Version 2.1 (Phase 2)
- **SPEED tab**: ping, jitter, download and upload speed tested against Cloudflare's servers (speed.cloudflare.com). Each result is saved with the operator, network, signal level and GPS position. The history can be shared as a CSV file.
- **Export a drive log** (DRIVE tab → tap a log):
  - **PDF report**: summary, signal quality distribution, technology share, handovers, level-over-time chart, route plot and a top-cells table
  - **KML**: open in Google Earth. Points are coloured by signal, with the route line and known towers.
  - **GPX**: a track for any map app
  - Share the CSV, or delete the log
- **Signal alerts** (Settings → Alerts): weak signal (threshold from -100 to -120 dBm), no service, and network change (for example 4G → 3G), with sound, vibration and a notification. Alerts work while the app is open or a drive test is running.

## Build
Push to the `main` branch. GitHub Actions builds `NetPulse.apk` and publishes it as a Release.
You can also build locally: `gradle assembleDebug` (Gradle 8.14, JDK 17, Android SDK 36).

## Notes
- Android only gives cell info when you allow **Location** and **Phone** permissions and **Location is turned ON**.
- Some fields (band, bandwidth, RSSI, EcNo) depend on your Android version and the phone's modem.
