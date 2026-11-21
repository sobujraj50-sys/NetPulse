# Publishing NetPulse on Google Play — checklist

## 1. Create a signing key (one time, keep it safe forever)
```
keytool -genkeypair -v -keystore netpulse.jks -alias netpulse -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 netpulse.jks > netpulse.b64
```
Add these GitHub repository secrets (Settings → Secrets and variables → Actions):
| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | contents of `netpulse.b64` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `netpulse` |
| `KEY_PASSWORD` | key password |

The next build will produce a signed `NetPulse.aab` (in the workflow artifact). Upload that file to Play Console.
Turn on **Play App Signing** when Play Console asks you to.

## 2. Play Console forms
- **Target API**: the app targets Android 16 (API 36), which Google Play requires for new apps.
- **Privacy policy URL**: `https://github.com/<your-user>/NetPulse/blob/main/PRIVACY.md`
- **Data safety**:
  - Location (precise): collected = **No** (processed on the device only). If you add an OpenCelliD key, cell IDs are sent to OpenCelliD. Declare "Approximate location → shared with a third party, for app functionality, optional".
  - No personal info, no financial info, no contacts, no analytics.
  - Data is encrypted in transit (HTTPS): Yes. Users can delete data: Yes (clear storage).
- **Foreground service declaration** (`FOREGROUND_SERVICE_LOCATION`): "Drive-test logging. The user taps Start Logging; the service records cell signal + GPS every second until the user taps Stop. A notification with a Stop button is shown the whole time." Google asks for a short video showing this.
- **Location permission**: the app uses foreground location only (no background location permission).
- **Content rating**: fill in the questionnaire (utility app, no user content) → usually "Everyone".
- **Category**: Tools.

## 3. Personal developer accounts
New personal accounts must run a **closed test with at least 12 testers for 14 days** before production. Plan for it.

## 4. Store listing ideas
- Title: *NetPulse – Cell Signal & 5G Monitor*
- Short: *Live 2G/3G/4G/5G cell info, tower map, drive test logging and CA detection.*
- Screenshots: CELL tab (dark), MAP with tower, NEI table, DRIVE log, Settings with languages.
