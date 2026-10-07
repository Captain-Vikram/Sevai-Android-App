# Sevai.app Android SMS Client

Android client for forwarding high-confidence financial SMS messages to the Sevai.app webhook.

## Requirements

- Android Studio
- JDK 17 for CI builds
- Android SDK configured through Android Studio

## First launch

The app asks the user for their Sevai.app username and API key on first launch. These values are stored locally in Android `SharedPreferences` and the API key is sent with requests as:

```http
X-SMS-API-Key: <user-api-key>
```

The webhook endpoint is configured in the SMS receiver and activities:

```text
https://debtless-creation-goal.ngrok-free.dev/api/sms/ingest
```

## Local build

```powershell
.\gradlew assembleDebug
.\gradlew assembleRelease
```

The release APK is generated at:

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

## GitHub Actions

The workflow at `.github/workflows/android-release.yml`:

- builds `assembleRelease` on pushes and pull requests
- uploads the unsigned release APK as a workflow artifact
- creates a GitHub Release when a version tag such as `v1.0.0` is pushed

Create a release locally after adding a GitHub remote:

```powershell
git tag v1.0.0
git push origin main
git push origin v1.0.0
```

The generated APK is unsigned. Add Android signing secrets and a signing configuration before distributing it through Google Play or outside trusted testing.

## Webhook contract

Individual SMS requests use `POST /api/sms/ingest` with `Content-Type: application/json` and `X-SMS-API-Key` headers. The body contains:

```json
{
  "time": "2026-10-07 14:30:00",
  "senderName": "HDFCBK",
  "receipientNumber": "+919876543210",
  "message": "Your account was debited by Rs 500"
}
```

The app forwards incoming messages when the classifier score is at least 7. The `1-Month History` feature sends a batch payload containing `owner` and a `messages` array to the same configured endpoint.
