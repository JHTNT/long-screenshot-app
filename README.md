# Long Screenshot

An Android app for capturing and stitching long screenshots.

## Features

- Capture scrolling screens from any app with a floating button
- Automatically stitch captures, or select a content region for matching
- Preview captures and remove unwanted frames before stitching
- Crop the finished screenshot and export it as PNG or JPG
- Save to the gallery or copy directly to the clipboard

## Requirements

- Android 10 (API 29) or later
- Eclipse Temurin JDK 17

## Build

```powershell
.\gradlew.bat assembleDebug
```

The APK is generated in `app/build/outputs/apk/debug/`.

## CI and releases

GitHub Actions runs unit tests, Android lint, and a debug build on pull requests and pushes to `main`. Pushing a `v*` tag runs the same checks, builds and verifies a signed release APK, and publishes `LongScreenshot.apk` with generated release notes.

### Signing secrets

Use the existing release keystore and its `release` alias. The workflow checks the APK's signing certificate against the project's release fingerprint before publishing. Keep a backup of the keystore and its passwords outside the repository.

In **Settings → Secrets and variables → Actions → Repository secrets**, add these four secrets.

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded contents of the release keystore file |
| `ANDROID_KEYSTORE_PASSWORD` | Password that opens the keystore |
| `ANDROID_KEY_ALIAS` | Alias of the signing key in the keystore |
| `ANDROID_KEY_PASSWORD` | Password for that key. If it shares the keystore password, enter the same value here. |

To copy the Base64 value in PowerShell, replace the example path with your keystore path.

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\keys\release.jks')) | Set-Clipboard
```

### Publish a version

Set `versionName` in `app/build.gradle.kts` and increment `versionCode` for each update. Once the release commit is on GitHub, tag that commit with `v` followed by its exact `versionName`. For example, from the commit for version `0.1.0`, run

```sh
git tag v0.1.0
git push origin v0.1.0
```

## License

This project is licensed under the [MIT License](LICENSE).
