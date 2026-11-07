# Comity

A listening coach. Comity listens to a conversation, then shows who held the floor, who got cut off, and how well each person followed up on what others said.

Open it at https://captfantastikk.github.io/comity

Audio is deleted right after it's transcribed. Keys and saved conversations stay on your own phone.

## Android app

The `android/` folder is the Android version. Its screens are this same `index.html`; the app adds
listening with the screen locked (a foreground service with a "Comity is listening" notification)
and sends its own web requests.

Every push to `main` builds it on GitHub Actions and publishes the test APK at:
https://github.com/captfantastikk/comity/releases/download/test-build/comity.apk

Test builds are signed with `android/test-key` (for your own phone only). Google Play builds need
the `UPLOAD_KEYSTORE_BASE64`, `UPLOAD_STORE_PASSWORD`, `UPLOAD_KEY_ALIAS` and `UPLOAD_KEY_PASSWORD`
repository secrets.
