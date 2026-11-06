# Signing material

Do not commit keystores or passwords. CI provisions both debug and release
keystores with `keytool` at build time (see `.github/workflows/android-ci.yml`).

For a store-ready release key, generate one locally and keep it outside Git:

```bash
keytool -genkeypair -v \
  -keystore release.jks -alias edgevision \
  -keyalg RSA -keysize 2048 -validity 10000
```

Then expose it to Gradle through these environment variables:

- `EDGEVISION_RELEASE_STORE_FILE`
- `EDGEVISION_RELEASE_STORE_PASSWORD`
- `EDGEVISION_RELEASE_KEY_ALIAS`
- `EDGEVISION_RELEASE_KEY_PASSWORD`

Without them, local release builds fall back to the Android debug key.
