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

## Version 2.2 — Tower finder
When OpenCelliD doesn't know a tower, NetPulse works out where it is from your own measurements, the way CellMapper does:
- Every good GPS fix (accuracy 30 m or better, after moving at least 15 m) is saved with the serving cell's signal level, grouped by site (eNB/gNB for 4G/5G).
- After 6 points the app fits a path-loss model (RSRP = P0 − 10·n·log10(d)) using a grid search. If the phone reports Timing Advance, it also uses that as a distance check.
- The CELL tab shows "Tower (estimated)" with the position, distance, a ± uncertainty, an accuracy level (Low / Medium / High) and the street address. The MAP tab shows a purple triangle with an uncertainty circle, plus every other tower estimated so far ("Towers" button). Estimated towers are also added to KML exports.
- In tests with simulated data, the error was about 30–40 m when driving around the tower, about 15 m with TA, and about 300 m from one straight road. A straight road is always marked "Low", because the app can't tell which side of the road the tower is on.

## Version 3.0–6.0 (Phases 3–6)
- **Phase 3 (everyday users):** Simple view, "Why is my internet slow?" check, live dBm in the status bar, home-screen widget, signal & tower finder (beep + compass), Wi-Fi tab (scanner, channel graph, best channel, router info, Wi-Fi 6/7, security audit), data usage with a monthly limit alert, operator USSD shortcuts, 24-hour signal history, outages, call-drop log, best operator in your area, automatic speed tests and a monthly ISP report.
- **Phase 4 (network tools & QoE):** live ICMP ping chart, traceroute, DNS lookup and fastest-DNS comparison, connection-quality test (bufferbloat grade, call MOS, web page load, video streaming estimate, Google vs Cloudflare, IPv6, CGNAT, MTU), ping and speed tests during drive tests.
- **Phase 5 (RF engineering):** analysis of each drive log (signal / best-server / technology / throughput maps, sector azimuth wedges, handover ping-pong/late/IRAT, pilot pollution, PCI collision and confusion, band statistics, throughput vs signal), indoor floor-plan heatmap for mobile and Wi-Fi, 3D KML, OpenCelliD and CellMapper upload files, and an RF-analysis page in the PDF report.
- **Phase 6 (security):** fake-tower (IMSI-catcher) warning signs with alerts, Wi-Fi evil-twin detection (heuristic warnings, not proof).

## Build
Push to the `main` branch. GitHub Actions builds `NetPulse.apk` and publishes it as a Release.
You can also build locally: `gradle assembleDebug` (Gradle 8.14, JDK 17, Android SDK 36).

## Notes
- Android only gives cell info when you allow **Location** and **Phone** permissions and **Location is turned ON**.
- Some fields (band, bandwidth, RSSI, EcNo) depend on your Android version and the phone's modem.
