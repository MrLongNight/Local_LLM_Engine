# Persistent CI Debug signing

The Android APK workflow can sign the prototype's side-by-side Debug application with a persistent test keystore. This is **not** a production/release signing key and must never be used for a release build.

## Workflow behavior

- GitHub Actions reads the repository secret `LOCAL_LLM_DEBUG_KEYSTORE_BASE64`.
- When the secret is present, the workflow validates the decoded PKCS#12 keystore and its expected alias, then uses the persistent certificate.
- When the secret is absent, CI creates a temporary Debug key so native builds and emulator tests can still run. Such an APK cannot be updated in place by a later APK signed with a different key.
- Read the specific workflow run log to determine which path that run used. Do not infer persistent signing from a successful build alone.

## One-time repository setup

1. Open **Settings → Secrets and variables → Actions** in the repository.
2. Create the repository secret `LOCAL_LLM_DEBUG_KEYSTORE_BASE64`.
3. Store the complete Base64 content of the private PKCS#12 test keystore in that secret.
4. Keep the `.p12` file and any Base64 export outside the repository in an access-controlled backup.
5. Start **Actions → Android APK Build → Run workflow → debug** and confirm the log says the persistent key is in use.

Never commit the keystore, Base64 content or passwords to the repository. The current test keystore uses alias `androiddebugkey`; the expected certificate SHA-256 is `47:64:8D:F1:70:13:70:57:02:B5:9F:58:71:44:DE:7B:86:A4:75:9E:19:E3:64:12:EE:C5:65:B9:46:00:E5:97`.

## Debug application identity

The prototype Debug build uses application ID `com.aistudio.localllm.mdoanx.dmmv` and launcher label `Local LLM Engine – DMMV-Test`. It is intended to install beside the prototype's original app ID `com.aistudio.localllm.mdoanx`. Android SAF document grants are package-specific, so select the GGUF again in the DMMV-Test application.

Artifact creation, signature identity and target-device acceptance are separate checks. A persistent Debug certificate only enables in-place Debug updates; it does not establish production signing or engine correctness.
