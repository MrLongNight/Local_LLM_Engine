# InferenceServiceProcessTest – Extraktionsanalyse

**Status:** Schritt 1 abgeschlossen – Testinventar und Zielklassen vorgeschlagen; Testcode unverändert.  
**Repository:** `MrLongNight/Local_LLM_Engine` · Branch `main`  
**Analysierter Branch-Stand:** `8f600694e9e818487159bfb5f675dd8882d77d74`  
**Testdatei-Blob:** `a2b4ecf553e133d8da90e62305726dedbe79b94c`  
**Quelldatei:** `llm-engine/src/androidTest/java/com/mydocanizer/llm/engine/ipc/InferenceServiceProcessTest.kt`  
**Ausgangsgröße:** 30.405 Bytes · 629 Zeilen · 8 Testmethoden

## 1. Testmethoden → Zielklasse

Alle acht vorhandenen `@Test)-Methoden werden als vollständige Methodenblöcke verschoben. Namen, Annotationen, Parameter, Rückgabetypen, Coroutine-Wrapper, Assertions, Kommentare und methodeninterne `try/finally)-Aufräumlogik bleiben 1:1 erhalten.

| Testmethode | Aktuelle Zeile | Aspekt | Vorgeschlagene Zielklasse |
|---|---:|---|---|
| `cxxExceptionAtJniBoundaryBecomesJavaException` | 55 | JNI-/Native-Fehlergrenze | `NativeBoundaryAndIpcTest` |
| `inferenceRequestParcelablePreservesThinkingModeAcrossIpc` | 76 | Parcelable-/IPC-Vertrag | `NativeBoundaryAndIpcTest` |
| `directSeekableProcDescriptorIsAcceptedWithoutCopying` | 95 | SAF-/Dateideskriptor: seekbare Datei | `ModelSourceDescriptorTest` |
| `directPipeDescriptorIsRejectedWithNoCopyDiagnostic` | 117 | SAF-/Dateideskriptor: Pipe-Ablehnung | `ModelSourceDescriptorTest` |
| `inferenceClientReconnectsAfterRemoteInferenceProcessDies` | 142 | Binder-Disconnect, PID-Ende und Reconnect | `ProcessLifecycleTest` |
| `inferenceClientHardAbortsWhileNativeProcessChatIsBlockedAndReconnects` | 188 | Timeout, nicht-kooperativer JNI-Aufruf, Prozessabbruch und Reconnect | `InferenceCancellationTest` |
| `realGgufIsReloadedAndFollowupInferenceSucceedsAfterTimeoutKill` | 304 | echter GGUF-Load, Timeout-Kill, Reload und Folge-Inferenz | `GgufRecoveryTest` |
| `serviceRunsInDedicatedProcessCanBeKilledAndBoundAgain` | 489 | separater Service-Prozess, Kill und Rebind | `ProcessLifecycleTest` |

**Geplante Testanzahl nach der Aufteilung:** 2 + 2 + 2 + 1 + 1 = **8**. Es werden keine Tests hinzugefügt, entfernt oder in Teiltests zerlegt.

## 2. Setup-/Teardown- und gemeinsame Helper-Analyse

**JUnit-Lifecycle-Hooks:** In der Ausgangsdatei gibt es keine `@Before`, `@After`, `@BeforeClass`, `@AfterClass`, `@BeforeEach` oder `@AfterEach)-Methoden. Es gibt genau eine Klassenannotation `@RunWith(AndroidJUnit4::class)` und acht `@Test)-Annotationen.

Die Ressourcenbereinigung einzelner Tests liegt in deren eigenen `try/finally)-Blöcken. Diese Blöcke bleiben ungeteilt innerhalb der jeweiligen Testmethode. Es wird keine neue globale Setup-/Teardown-Logik eingeführt.

Die vier prozessbezogenen Tests verwenden dieselben privaten Hilfsfunktionen und den gemeinsamen Binding-Datentyp. Vorgeschlagene gemeinsame Datei:

`llm-engine/src/androidTest/java/com/mydocanizer/llm/engine/ipc/InferenceServiceProcessTestBase.kt`

| Gemeinsamer Bestandteil | Ursprünglicher Ort | Verwendung | Vorschlag |
|---|---|---|---|
| `BoundInferenceService` | verschachtelte private Data Class | alle vier Prozess-/Reconnect-Tests | geschützte verschachtelte Data Class in der Basisklasse |
| `assertInferenceServiceManifest(context)` | private Helper-Funktion | Reconnect, Hard-Abort, GGUF-Recovery, Kill/Rebind | Helper unverändert in die Basisklasse verschieben; Sichtbarkeit für Unterklassen öffnen |
| `bindInferenceService(context)` | private Helper-Funktion | dieselben vier Prozess-/Reconnect-Tests | Helper unverändert in die Basisklasse verschieben |
| `resetAppProcessDiagnostics(context, appPid)` | private Helper-Funktion | Hard-Abort und GGUF-Recovery | Helper unverändert in die Basisklasse verschieben |
| `awaitProcessExit(pid, timeoutMs)` | private Helper-Funktion | dieselben vier Prozess-/Reconnect-Tests | Helper unverändert in die Basisklasse verschieben |
| vier Timeout-Konstanten | privates Companion Object | Bind-, Exit-, Recovery- und Polling-Fristen | Werte und Namen unverändert im Testpaket verfügbar machen |

Die vier prozessbezogenen Zielklassen erben von `InferenceServiceProcessTestBase`. `NativeBoundaryAndIpcTest` und `ModelSourceDescriptorTest` benötigen diese Basisklasse nicht.

**Wichtig:** Es existieren keine Lifecycle-Hooks, die aus den Testklassen in die Basisklasse übernommen werden müssten. Die Basisklasse dient ausschließlich dazu, vorhandene gemeinsame Helper, den Binding-Datentyp und die Timeout-Konstanten nicht zu duplizieren. Die Helper-Funktionskörper bleiben identisch; nur die Sichtbarkeit muss für die Unterklassen angepasst werden. Die Konstanten behalten ihre exakten Werte und Namen.

## 3. Größenhinweis – unteilbarer GGUF-Test

`realGgufIsReloadedAndFollowupInferenceSucceedsAfterTimeoutKill` ist die größte einzelne Testmethode:

- Funktionssignatur einschließlich Methodenrumpf: **10.020 Bytes**
- Rumpf ab öffnender Funktionsklammer: **9.937 Bytes**
- Der vollständige Methodenblock einschließlich `@Test)-Annotation und Trennern ist entsprechend größer.

Diese Methode muss als Ganzes in `GgufRecoveryTest.kt` verbleiben. Eine Klassengrenze unter 10.000 Bytes ist deshalb für diese Zielklasse nicht erreichbar, ohne die Testmethode zu teilen, Teile in Helper zu verschieben oder ihren Inhalt sonst zu verändern. Das würde der 1:1-Vorgabe widersprechen. Der aktuelle Auftrag nennt kein explizites 10-KB-Limit als Akzeptanzkriterium; falls ein solches Limit zusätzlich gelten soll, besteht hier ein direkter Zielkonflikt.

## 4. Verifikationsplan für Schritt 2–4

1. Gemeinsame Helper und Binding-Datentyp in `InferenceServiceProcessTestBase` verschieben; keine JUnit-Lifecycle-Hooks hinzufügen.
2. Die fünf vorgeschlagenen Testklassen im bestehenden `androidTest)-Source-Set und Paket `com.mydocanizer.llm.engine.ipc` anlegen.
3. Die acht vollständigen Testmethoden unverändert den oben genannten Zielklassen zuordnen. Jede neue konkrete Testklasse erhält weiterhin `@RunWith(AndroidJUnit4::class)`.
4. Nach jeder neuen Testklasse `:llm-engine:test` ausführen und erst bei grünem Ergebnis committen.
5. Vorher/Nachher die Anzahl der `@Test)-Methoden vergleichen: **8 = 8**.
6. Den Diff gegen die Ausgangsdatei prüfen: Testmethoden dürfen ausschließlich verschoben werden; keine Testnamen, Assertions, Konstantenwerte oder methodeninternen Setup-/Cleanup-Zeilen verändern.

## 5. Umfang dieses Schritts

In diesem Schritt wurde ausschließlich die Analyse dokumentiert. `InferenceServiceProcessTest.kt` und alle anderen Test-/Produktionsdateien bleiben unverändert. Es wurden weder Testklassen angelegt noch Tests ausgeführt.

**Stopp:** Auf Freigabe warten, bevor Basisklasse und Testklassen angelegt werden.
