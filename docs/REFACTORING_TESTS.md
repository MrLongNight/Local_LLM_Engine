# InferenceServiceProcessTest – Extraktion und Verifikation

**Status:** Testaufteilung abgeschlossen; alle 8 Testmethoden unverändert verschoben; Unit-CI grün; Android-APK-/Instrumentation-CI läuft noch.  
**Repository:** `MrLongNight/Local_LLM_Engine` · Branch `main`  
**Code-Stand:** `b526616ab54c7fa75ac552eef04502c3404650e8`  
**Ausgangsdatei:** `llm-engine/src/androidTest/java/com/mydocanizer/llm/engine/ipc/InferenceServiceProcessTest.kt`  
**Ausgangsgröße:** 30.405 Bytes · 629 Zeilen · 8 Testmethoden

## 1. Testmethoden und Zielklassen

| Testmethode | Zielklasse | Ergebnis des Methodenvergleichs |
|---|---|---|
| `cxxExceptionAtJniBoundaryBecomesJavaException` | `NativeBoundaryAndIpcTest` | byte-identisch |
| `inferenceRequestParcelablePreservesThinkingModeAcrossIpc` | `NativeBoundaryAndIpcTest` | byte-identisch |
| `directSeekableProcDescriptorIsAcceptedWithoutCopying` | `ModelSourceDescriptorTest` | byte-identisch |
| `directPipeDescriptorIsRejectedWithNoCopyDiagnostic` | `ModelSourceDescriptorTest` | byte-identisch |
| `inferenceClientReconnectsAfterRemoteInferenceProcessDies` | `ProcessLifecycleTest` | byte-identisch |
| `inferenceClientHardAbortsWhileNativeProcessChatIsBlockedAndReconnects` | `InferenceCancellationTest` | byte-identisch |
| `realGgufIsReloadedAndFollowupInferenceSucceedsAfterTimeoutKill` | `GgufRecoveryTest` | byte-identisch |
| `serviceRunsInDedicatedProcessCanBeKilledAndBoundAgain` | `ProcessLifecycleTest` | byte-identisch |

**Testanzahl: 8 vorher = 8 nachher.** Die Namen sind eindeutig; keine Methode wurde hinzugefügt, entfernt, geteilt oder inhaltlich editiert. Der Vergleich extrahiert jeweils den kompletten `@Test`-Methodenblock von `@Test` bis zur schließenden Methodenklammer und vergleicht ihn bytegenau mit der Ausgangsversion.

## 2. Neue Testdateien und Größen

Alle Dateien bleiben im existierenden Package `com.mydocanizer.llm.engine.ipc` unter `llm-engine/src/androidTest/`.

| Datei | Tests | Bytes |
|---|---:|---:|
| `NativeBoundaryAndIpcTest.kt` | 2 | 2.248 |
| `ModelSourceDescriptorTest.kt` | 2 | 2.820 |
| `ProcessLifecycleTest.kt` | 2 | 4.769 |
| `InferenceCancellationTest.kt` | 1 | 7.338 |
| `GgufRecoveryTest.kt` | 1 | 11.360 |
| `InferenceServiceProcessTestBase.kt` | 0 | 4.868 |

Die ursprüngliche Datei `InferenceServiceProcessTest.kt` wurde vollständig entfernt. Die fünf konkreten Testklassen erhalten jeweils weiterhin `@RunWith(AndroidJUnit4::class)`.

**Größen-Ausnahme:** `GgufRecoveryTest.kt` überschreitet 10 KB. Die einzelne Testmethode `realGgufIsReloadedAndFollowupInferenceSucceedsAfterTimeoutKill` ist bereits als vollständiger `@Test`-Methodenblock 10.034 Bytes groß. Sie kann daher nicht in einer Datei unter 10 KB liegen, ohne die Methode aufzuteilen oder zu verändern. Sie blieb bewusst vollständig erhalten. Alle anderen Testklassen liegen unter 10 KB.

## 3. Gemeinsame Basisklasse und Lifecycle

Die Ausgangsdatei enthielt **keine** JUnit-Lifecycle-Hooks (`@Before`, `@After`, `@BeforeClass`, `@AfterClass`, `@BeforeEach`, `@AfterEach`). Deshalb war keine Setup-/Teardown-Migration notwendig.

`InferenceServiceProcessTestBase.kt` übernimmt ausschließlich die bisher in der ursprünglichen Klasse gemeinsam verwendeten Bestandteile:

- `BoundInferenceService` – gleiche Data-Class-Felder und Typen; Sichtbarkeit von `private` auf `protected` geöffnet.
- `assertInferenceServiceManifest(context)`
- `bindInferenceService(context)`
- `resetAppProcessDiagnostics(context, appPid)`
- `awaitProcessExit(pid, timeoutMs)`

Die Helper-Funktionskörper blieben unverändert; nur die Sichtbarkeit wurde für Unterklassen von `private` zu `protected` geändert. Die vier Timeout-Konstanten behalten exakt ihre ursprünglichen Namen und Werte und sind als `internal const val` auf Paketebene verfügbar. Die vier prozessbezogenen Testklassen erben von der Basisklasse. Die zwei unabhängigen Gruppen benötigen sie nicht.

Alle methodeninternen `try/finally)-Blöcke bleiben in ihren vollständigen Testmethoden erhalten. Zusätzliche globale Setup-/Teardown-Logik wurde nicht eingeführt.

## 4. Commit-Reihenfolge

| Commit | Inhalt |
|---|---|
| `319ff848609e90545f828d42d03fd6ca8fd1a307` | JNI-/Parcelable-Tests und Dateideskriptor-Tests in zwei Klassen verschoben |
| `3988702a007b12f358ec6589aed508a0eb772fc9` | gemeinsame Binding-/Process-Helper in `InferenceServiceProcessTestBase` extrahiert |
| `f2d39475b3372ae462c419b217b0b70d02708f7c` | Lifecycle-Tests in `ProcessLifecycleTest` verschoben |
| `2b6b5d2ca8a61ae218555459e01963b34a995cdd` | Hard-Abort-Test in `InferenceCancellationTest` verschoben |
| `b526616ab54c7fa75ac552eef04502c3404650e8` | GGUF-Recovery-Test in `GgufRecoveryTest` verschoben; ursprüngliche Sammelklasse entfernt |

Die Verifikationsläufe auf Zwischenständen umfassen erfolgreiche Unit-Läufe und teilweise wegen des nächsten Commits abgebrochene APK-Läufe. Maßgeblich für den Gesamtstand ist die abschließende CI auf `b526616`.

## 5. Finale Verifikation

| Prüfung | Code-SHA / Run | Ergebnis |
|---|---|---|
| Unit Tests – JVM | `b526616` · Run `43572193823` | **success** |
| Host-only native Prompt-Cache-/Truncation-Tests | `b526616` · Run `43572193823` | **success** |
| Android Debug APK und Instrumentation APK Build | `b526616` · Run `43572193817` | bei letzter Aktualisierung in Arbeit |
| Emulator-Instrumentation | `b526616` · Run `43572193817` | noch nicht erreicht/bestätigt |
| Artefakt-IDs | Run `43572193817` | noch nicht verfügbar |

Der Unit-Workflow ist vollständig abgeschlossen und grün. Der Android-Workflow war zuletzt im Schritt `Build APK and process-isolation test APK`; APK-/Instrumentation-Artefakte waren zu diesem Zeitpunkt noch nicht hochgeladen. Ein erfolgreicher Emulator-Test wird erst nach einem abgeschlossenen Workflow und verifiziertem Job-Ergebnis behauptet.

## 6. Offener Größen-/Abnahmehinweis

Die geforderte 1:1-Erhaltung und die Testanzahl sind erfüllt. Das 10-KB-Limit wird von vier der fünf konkreten Testklassen eingehalten. Für `GgufRecoveryTest` ist es mit dem unveränderten 10.034-Byte-Testmethodenblock technisch nicht erreichbar; dies erfordert eine explizite Lockerung oder eine Freigabe, die Testmethode zu zerlegen. Ohne eine solche Freigabe wird sie nicht verändert.
