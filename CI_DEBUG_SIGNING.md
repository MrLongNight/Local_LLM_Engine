# Persistent CI debug signing

The manual APK workflow uses the repository Actions secret
`LOCAL_LLM_DEBUG_KEYSTORE_BASE64` when it is configured. The secret must contain
the one-line Base64 encoding of the PKCS#12 debug keystore supplied to the
maintainer. The expected key settings are:

- Alias: `androiddebugkey`
- Store password: `android`
- Key password: `android`
- Certificate SHA-256: `92:BF:C2:1E:7B:AF:F5:EA:22:12:03:19:0D:F1:99:62:CA:82:0C:2E:61:E7:D9:A0:F7:8E:1C:A6:9A:D8:74:96`

## One-time repository setup

1. Open the repository's **Settings → Secrets and variables → Actions**.
2. Create a repository secret named `LOCAL_LLM_DEBUG_KEYSTORE_BASE64`.
3. Paste the complete single-line Base64 content of the supplied
   `Local_LLM_Engine-CI-Debug-KEYSTORE_BASE64.txt` file as its value.
4. Keep the original `.p12` keystore in a secure private backup. Never commit
   the keystore or its Base64 contents to the repository.

After the secret is configured, subsequent CI debug APKs use the same signing
certificate and can update an app installed from another APK signed with this
same key.

If the secret is absent, the workflow still generates a temporary key so the
CI build remains usable, but it emits a warning: that APK is not update-compatible
with an APK signed by a different CI run. The APK signed with the previous
temporary key cannot be converted to the persistent key without rebuilding.
Android will reject it as an in-place update if the installed app has a different
signing certificate; uninstalling the existing app can delete app-private models
and settings, so do not uninstall before protecting any data you need.
