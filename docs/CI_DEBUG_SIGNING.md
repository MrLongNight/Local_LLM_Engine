# Persistent CI debug signing

The manual APK workflow reads the repository Actions secret
`LOCAL_LLM_DEBUG_KEYSTORE_BASE64`. The key has been generated for the
side-by-side DMMV debug application only. It is not a production/release key.

## Key details

- Keystore format: PKCS#12
- File: `Local_LLM_Engine-DMMV-CI-Debug.p12`
- Alias: `androiddebugkey`
- Store password: `android`
- Key password: `android`
- Certificate SHA-256:
  `47:64:8D:F1:70:13:70:57:02:B5:9F:58:71:44:DE:7B:86:A4:75:9E:19:E3:64:12:EE:C5:65:B9:46:00:E5:97`

## One-time repository setup

1. Open **Settings → Secrets and variables → Actions** in this repository.
2. Create a repository secret named `LOCAL_LLM_DEBUG_KEYSTORE_BASE64`.
3. Copy the complete single-line content from
   `Local_LLM_Engine-CI-Debug-KEYSTORE_BASE64.txt` into that secret.
4. Keep `Local_LLM_Engine-DMMV-CI-Debug.p12` as a private backup. Never commit
   either file or its Base64 contents to the public repository.

The key itself is not stored in this repository. The build workflow validates
that the secret decodes to a PKCS#12 keystore with the expected alias. When the
secret is present, future CI DMMV APKs use the same signing certificate and
can update an existing DMMV installation without removing its app data.

The workflow currently falls back to a fresh temporary key when the secret is
absent so CI can still build and run instrumentation tests. Such APKs are
one-run installs and cannot be updated in place by a later APK signed with a
different key. The CI log emits a warning when that fallback is used.

**Current status:** the CI log for APK build #231 confirmed that the secret was
empty, so #231 was signed with a one-run temporary key. Do not treat that APK
as the reusable DMMV installation. Add the secret first, then start
**Actions → Android APK Build → Run workflow → debug**. The next run should
state that it is using the persistent key; compare its signing-certificate
fingerprint with the SHA-256 value above before relying on in-place updates.

The Debug build uses the separate application ID
`com.aistudio.localllm.mdoanx.dmmv` and launcher label
`Local LLM Engine – DMMV-Test`. It installs alongside the original app
(`com.aistudio.localllm.mdoanx`) and does not require uninstalling or altering
the original app. SAF document permissions are package-specific, so select
the GGUF again once in the DMMV-Test app.
