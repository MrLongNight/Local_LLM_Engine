# InferenceServiceProcessTest — mechanische Test-Aufteilung

**Status:** SCHRITT 1 abgeschlossen — **STOP / Freigabe ausstehend**. Keine Test-Quelldatei für diesen Task geändert.  
**Repository:** `MrLongNight/Local_LLM_Engine` · **Branch:** `main`  
**Analyse-Code-HEAD:** `4492dc3ec7a281cd64640f0186482f98fc07fca8`  
**Datei:** `llm-engine/src/androidTest/java/com/mydocanizer/llm/engine/ipc/InferenceServiceProcessTest.kt`  
**Blob SHA:** `a2b4ecf553e133d8da90e62305726dedbe79b94c`  
**Umfang:** 30.405 Zeichen, 629 Zeilen. **JUnit-Testmethoden: 8.**

## 1. Testmethoden-Inventar

| Testmethode | Zeilen | Vorgeschlagene Zielklasse |
|---|---:|---|
| `cxxExceptionAtJniBoundaryBecomesJavaException` | 55–74 | `InferenceIpcContractTest` |
| `inferenceRequestParcelablePreservesThinkingModeAcrossIpc` | 76–93 | `InferenceIpcContractTest` |
| `directSeekableProcDescriptorIsAcceptedWithoutCopying` | 95–115 | `ModelSourceDescriptorTest` |
| `directPipeDescriptorIsRejectedWithNoCopyDiagnostic` | 117–140 | `ModelSourceDescriptorTest` |
| `inferenceClientReconnectsAfterRemoteInferenceProcessDies` | 142–186 | `InferenceClientReconnectTest` |
| `inferenceClientHardAbortsWhileNativeProcessChatIsBlockedAndReconnects` | 188–302 | `InferenceClientHardAbortTest` |
| `realGgufIsReloadedAndFollowupInferenceSucceedsAfterTimeoutKill` | 304–487 | `GgufRecoveryAfterAbortTest` |
| `serviceRunsInDedicatedProcessCanBeKilledAndBoundAgain` | 489–528 | `InferenceServiceLifecycleTest` |

Ziel-Package bleibt zunächst `com.mydocanizer.llm.engine.ipc`; Source Set bleibt `llm-engine/src/androidTest/java`.

## 2. Setup / Teardown / gemeinsame Hilfen

Im aktuellen Source gibt es **keine** Methoden mit `@Before`, `@After`, `@BeforeClass` oder `@AfterClass`. Kein Setup/Teardown darf neu eingeführt werden.

| Element | Quellzeilen | Vorgeschlagener Owner |
|---|---:|---|
| `BoundInferenceService` nested data class | 43–51 | `InferenceServiceProcessTestBase` |
| `assertInferenceServiceManifest(context)` | 530–545 | `InferenceServiceProcessTestBase` |
| `bindInferenceService(context)` | 546–591 | `InferenceServiceProcessTestBase` |
| `resetAppProcessDiagnostics(context, appPid)` | 593–612 | `InferenceServiceProcessTestBase` |
| `awaitProcessExit(pid, timeoutMs)` | 613–621 | `InferenceServiceProcessTestBase` |
| Timeout-Konstanten im privaten Companion Object | Dateiende | `InferenceServiceProcessTestBase` |

Die Hilfsmethoden sind momentan private. Eine Basisklasse erfordert eine Sichtbarkeitsänderung für aufrufende Subklassen (z. B. `protected`); Methoden-Körper und Konstantenwerte müssen identisch bleiben. Jede konkrete Testklasse muss `@RunWith(AndroidJUnit4::class)` explizit tragen.

## 3. Harte Konflikte der Akzeptanzkriterien

### 3.1 Dateigröße

Der bestehende Testkörper von `realGgufIsReloadedAndFollowupInferenceSucceedsAfterTimeoutKill` (Zeilen 304–487) umfasst bereits **10.034 Zeichen** vor Klassenhülle, Imports und Fixture-Verkabelung.

Daher können die beiden Vorgaben nicht gleichzeitig erfüllt werden:
1. Testmethode byte-identisch verschieben, nicht umformulieren.
2. Jede neue Testklasse strikt unter 10 KB halten.

**Empfehlung:** Testmethode unangetastet verschieben und eine begründete Größen-Ausnahme für `GgufRecoveryAfterAbortTest.kt` genehmigen. Nicht kürzen oder umschreiben. Dafür ist vor Implementierung eine ausdrückliche Entscheidung erforderlich.

### 3.2 Reporting-Kontinuität

Testmethoden-Namen bleiben identisch. Der vollqualifizierte JUnit-Testname ändert sich zwangsläufig, weil die Klasse neu benannt wird. Falls Reporting-Kontinuität auch den Klassennamen umfasst, ist dies mit dem Klassen-Split inkompatibel und muss vor Implementierung geklärt werden.

## 4. Vorgeschlagene Zielstruktur

- `InferenceServiceProcessTestBase.kt` — gemeinsame Datenklasse, Bind-/Manifest-/Diagnostics-/PID-Helfer und Konstanten; keine Testmethoden.
- `InferenceIpcContractTest.kt` — JNI Exception Boundary + Parcelable Contract (2 Tests).
- `ModelSourceDescriptorTest.kt` — regulärer seekbarer FD + Pipe-Rejection (2 Tests).
- `InferenceClientReconnectTest.kt` — Reconnect nach Remote-Prozess-Tod (1 Test).
- `InferenceClientHardAbortTest.kt` — blockiertes JNI und PID-Abbruch (1 Test).
- `GgufRecoveryAfterAbortTest.kt` — echte GGUF-Wiederherstellung (1 Test; Größen-Ausnahme erforderlich).
- `InferenceServiceLifecycleTest.kt` — Dedicated Process, Kill und Rebind (1 Test).

Gesamt: **8 Tests vorher = 8 Tests nachher**.

## 5. Aktueller CI-Hinweis

Im APK-Workflow Run `43557763490` (Code-HEAD `4492dc3`) waren APK-Build, Instrumentation-APK-Build, Manifestprüfung und Artifact Upload erfolgreich. Ein bestehender Emulator-Test `realGgufIsReloadedAndFollowupInferenceSucceedsAfterTimeoutKill` schlug an Zeile 393 fehl: erwartet `DEBUG_NATIVE_INFERENCE_BLOCKED`, erhalten `MODEL_LOADED`. Der identische Test-Blob ist im vorherigen grünen APK-Code-HEAD `a6bcd532...` vorhanden. Retry Attempt 2 wurde gestartet; der Live-Status steht in der Roadmap. Den Fehler nicht dem geplanten Dateisplit zuschreiben.

## 6. Verifikations- und Freigabe-Gates

- [ ] Nutzerfreigabe für den Testklassen-Split.
- [ ] Entscheidung zur unvermeidbaren >10-KB-Datei.
- [ ] Entscheidung, ob Reporting-Kontinuität nur Methodennamen oder auch Klassenname umfasst.
- [ ] Acht Testnamen und Methoden-Körper byte-identisch zur Baseline.
- [ ] Gemeinsame Helper-Körper und Timeout-Konstanten unverändert.
- [ ] `@RunWith(AndroidJUnit4::class)` für jede konkrete Klasse.
- [ ] Testanzahl vor/nach = **8**.
- [ ] APK Build + `:llm-engine:connectedDebugAndroidTest` grün; genaue Runs dokumentieren.
- [ ] Echte Geräteabnahme separat durch Nutzer.

**STOP:** Analyse only. Keine Testquelle wurde geteilt oder geändert.
