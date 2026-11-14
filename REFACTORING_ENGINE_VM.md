# EngineViewModel – Struktur-Analyse vor Refactoring

**Status:** Refactoring läuft – Chat-Extraktion umgesetzt; Model-Management-Extraktion auf main; aktuelle CI für HEAD a6bcd532 läuft  
**Repository:** `MrLongNight/Local_LLM_Engine`  
**Branch:** `main`  
**Analyse-Commit:** `8947f76bf48e6553e8d62a3d8f93d19d8fb49645`  
**Datei:** `app/src/main/java/com/example/ui/EngineViewModel.kt`  
**Datei-Snapshot (Git-Blob):** `8486dd90500f79c68f4ed1e4068657b1e7737a89`  
**Umfang:** 113.109 Bytes, 2.156 Zeilen  
**Gelesen:** vollständiger Dateiinhalt von Zeile 1 bis 2.156.

> In diesem Schritt wurden keine Kotlin-/Java-/C++-, Gradle-, Manifest-, AIDL- oder UI-Dateien geändert. Diese Datei enthält ausschließlich die Analyse und Vorschläge für eine spätere, separat freizugebende Extraktion.

## 1. Funktions- und Property-Tabelle

Die Zeilenbereiche beziehen sich auf den oben dokumentierten Snapshot. Grenzen umfassen bei Bedarf Kommentare und Leerzeilen, die unmittelbar zum jeweiligen Element gehören.

| Zeilenbereich | Funktion / Property / Deklaration | Zugehöriger Bereich |
|---:|---|---|
| 1–39 | Package und Imports | Querschnitt: Android-ViewModel, Engine-API, Chat, SAF, StateFlows, Coroutines, Datei-I/O |
| 41–45 | `AppTab` | Navigation / Lifecycle |
| 47–54 | `ImportedModelEntry` inkl. `sizeMb` | Model Management / Modellliste |
| 56–70 | `EngineViewModel`, Primärkonstruktor, sekundärer Application-Konstruktor, Companion Object, `TAG`, `DEFAULT_SYSTEM_PROMPT` | Lifecycle / Engine-Setup |
| 72–78 | `currentTab`, `engineState`, `currentModel`, `backendInfo`, `modelLoadProgress` | Navigation + gemeinsamer Engine-/Modellstatus |
| 82–95 | `chatMessages`, `inputText`, `isGenerating`, `currentStreamingText`, `inferenceProgress` | Chat / Conversation |
| 97–112 | `_engineConfig`, `engineConfig` und Default-`EngineConfig` | Settings / Preferences; zugleich zentrale Inferenzkonfiguration |
| 114–115 | `_systemPrompt`, `systemPrompt` | Settings / Preferences + Chat |
| 117–118 | `_importedModelEntries`, `importedModelEntries` | Model Management / Modellliste |
| 120–124 | `statusMessage`, `errorMessage` | Gemeinsamer UI-Status / Querschnitt aller Screens |
| 126–128 | `memoryOverrideAvailable`, `pendingMemoryOverride` | Model Management / Speicherwarnung beim Laden |
| 130–131 | `isImporting` | Model Import; wird zusätzlich als Sperrstatus von Chat und Benchmark verwendet |
| 133–143 | `isBenchmarking`, `benchmarkReport`, `isConfigSmokeTesting`, `configSmokeReport` | Benchmark / Diagnostics |
| 145–148 | `configurationSmokeJob`, `inferenceJob`, `generationSequence`, `lastModelStore` | Querschnitt: Lifecycle, Chat-Cancellation, Test-Cancellation und Last-Model-Restore |
| 150–153 | `init`: `refreshModelFiles()`, `restoreLastModelOnStartup()` | Lifecycle / Engine-Setup + Model Management |
| 155–158 | `selectTab()` | Navigation |
| 160–162 | `updateInputText()` | Chat / Conversation |
| 164–167 | `updateSystemPrompt()` | Settings / Preferences |
| 169–199 | `updateConfig()` inkl. Änderungsvergleich und Diagnosemeldung | Settings / Preferences |
| 201–203 | `dismissStatus()` | Gemeinsamer UI-Status |
| 205–207 | `dismissError()` | Gemeinsamer UI-Status |
| 209–235 | `refreshModelFiles()`: privates Modellverzeichnis lesen, GGUF-Dateien sortieren und Listeneinträge bilden | Model Management / Modellliste |
| 237–310 | `startModelLoad()`: gemeinsamer Ladeablauf, Config-Snapshot, Engine-Load, Last-Model-Speicherung, Status-/Fehler- und Speicherwarnungsbehandlung | Model Management / Engine-Ladevorgang |
| 312–322 | `loadModelDespiteMemoryWarning()` | Model Management / Speicherwarnung |
| 324–340 | `requiresMemoryGuardOverride()` | Model Management / Fehlerklassifikation des Speicherwächters |
| 342–410 | `importModelsFromTree()`: SAF-Ordnerberechtigung, rekursiver Scanner, Dateiimport, Fortschritt und Fehlerliste | Model Import |
| 412–478 | `importModelsFromSaf()`: Mehrfachauswahl, Dateinamenprüfung, persistente Rechte, Import nach `ModelLibrary` | Model Import |
| 480–514 | `importModelFromSaf()`: persistente SAF-Leseberechtigung sicherstellen und Original-URI direkt als Modellquelle laden | Model Import + Model Loading |
| 516–577 | `restoreLastModelOnStartup()`: gespeicherte Quelle/Config, File-/SAF-Rechte prüfen und Modell erneut laden | Lifecycle / Model Management |
| 579–584 | `modelSourceKind()` | Model Management / Restore-Diagnostik |
| 586–591 | `loadModelFile()` | Model Management / Modellauswahl |
| 593–615 | `unloadModel()` | Model Management / Engine-Lifecycle |
| 617–887 | `runBackendAcceptanceTest()`: CPU-Referenz, Vulkan-Kandidat, Korrektheits-Gate, Profilmatrix, Messung, Restore | Benchmark / Diagnostics |
| 889–893 | KDoc für kurzen Backendvergleich | Benchmark / Diagnostics |
| 894–1046 | `runDefaultBackendBenchmark()`: CPU-/Vulkan-Kurzvergleich inkl. Modell-Reload und Restore | Benchmark / Diagnostics |
| 1048–1059 | `BackendBenchmarkSample` | Benchmark / Diagnostics – Messdatenmodell |
| 1061–1203 | `collectBackendBenchmarkSample()`: Inferenzstream, Prompt-/TTFT-/Token-Messwerte, Warm-up, Messgeschwindigkeit und Fehlerausgabe | Benchmark / Diagnostics – gemeinsamer Messhelfer |
| 1205–1447 | `runVulkanLayerSweep()`: Layer-Offload-Matrix inkl. Wiederholungsmessung und Restore | Benchmark / Vulkan Diagnostics |
| 1449–1714 | `runVulkanQ4kDecodePathSweep()`: Q4_K-MMVQ-vs.-DMMV-ABBA-Sweep, native Run-ID-/Kernelpfadprüfung und Restore | Benchmark / Vulkan Diagnostics |
| 1716–1869 | `runConfigurationSmokeTest()`: Profilgenerierung, Modell-Reload, Warm-up/Messung, Report und Restore | Benchmark / Configuration Diagnostics |
| 1871–1877 | `cancelConfigurationSmokeTest()` | Benchmark / Diagnostics – Cancellation |
| 1879–1884 | `ConfigurationSmokeSample` | Benchmark / Diagnostics – Messdatenmodell |
| 1886–1976 | `collectConfigurationSmokeSample()`: Testprompt, Inferenzstream, Fortschritt, Ergebnisvalidierung und Fehlerdiagnostik | Benchmark / Diagnostics – gemeinsamer Messhelfer |
| 1978–1995 | `configurationTestSafetyBlock()`: RAM- und Thermal-Sicherheitsgrenzen | Benchmark / Diagnostics – Safety Guard |
| 1997–2001 | `backendFamily()` | Benchmark / Diagnostics – Backendklassifikation |
| 2003–2017 | `restartConversation()`: Generation invalidieren, Inferenz abbrechen und Chat-Zustand zurücksetzen | Chat / Conversation |
| 2019–2124 | `sendMessage()`: Eingabe, Chat-Historie, Systemprompt, Request, Streaming, Token-/Progress-State, Completed/Cancelled/Error | Chat / Conversation |
| 2126–2130 | `cancelGeneration()` | Chat / Conversation |
| 2132–2149 | `exportDiagnostics()` | Gemeinsame App-Diagnostics / UI-Status |
| 2151–2155 | `onCleared()`: `engine.close()` | Lifecycle / Engine-Setup |

## 2. Erkannte logische Cluster

### A. Chat / Conversation

**Kernbereiche:** Zeilen 82–95, 160–162, 2003–2130.

Enthält Chat-Historie, Texteingabe, Streaming-Ausgabe, Inferenzfortschritt, Start/Abbruch und Reset. Der Chat verwendet zusätzlich den gemeinsamen Modellstatus, die Engine-Konfiguration, den Systemprompt sowie Status-/Fehlermeldungen.

### B. Model Management

**Kernbereiche:** Zeilen 47–54, 72–78 (Modellstatus), 117–118, 126–128, 209–340, 516–615.

Enthält Modellbibliotheksliste, Refresh, Load/Unload, Speicherwarnungs-Override und Wiederherstellung des zuletzt geladenen Modells. Der gemeinsame Loader `startModelLoad()` ist ein zentraler Kopplungspunkt und wird sowohl aus der SAF-Auswahl als auch aus der Dateiauswahl und dem Startup-Restore verwendet.

### C. Model Import

**Kernbereiche:** Zeilen 130–131, 342–514.

Enthält SAF-Ordner- und Mehrfachimport sowie den direkten Ladevorgang einer einzelnen SAF-URI. Die Methode `importModelFromSaf()` kopiert die einzelne Datei nicht in die private Modellbibliothek, sondern sichert den persistenten Zugriff und lädt die Original-URI als `ModelSource.ContentUri`. Daher ist sie funktional ein Import-/Picker-Einstieg mit Übergang in Model Management.

**Download:** In `EngineViewModel.kt` ist keine eigenständige Download-/Remote-Download-Funktion vorhanden. `ModelSource.RemoteUrl` kommt nur in der Typklassifikation `modelSourceKind()` vor. Ein eigener Download-Cluster lässt sich aus dieser Datei daher nicht begründen.

### D. Settings / Preferences

**Kernbereiche:** Zeilen 97–115, 164–199.

Enthält Engine-Konfiguration, Systemprompt und deren UI-Updates. Die Konfiguration wird vom Chat, allen Benchmarkabläufen und dem Modell-Ladevorgang verwendet. Das zuletzt geladene Modell speichert außerdem eine Config-Kopie; der Restore schreibt sie wieder in `_engineConfig`.

### E. Benchmark / Diagnostics

**Kernbereiche:** Zeilen 133–143, 617–2001 sowie 2132–2149 (Export als App-weite Diagnostics-Funktion).

Dieser eigenständige Cluster ist zusätzlich zu den erwarteten Clustern erforderlich. Die Funktionen umfassen Abnahme-/Korrektheits-Gates, Backendvergleich, Vulkan-Layer- und Q4_K-Sweep sowie den In-App-Konfigurationstest. Die Settings-Oberfläche zeigt Benchmark-Status/Report an und bietet mindestens die Aktionen `runBackendAcceptanceTest()` und `runVulkanQ4kDecodePathSweep()` an. Die Hilfsfunktionen und übrigen Sweeps liegen ebenfalls in dieser Datei.

### F. Lifecycle / Engine-Setup und gemeinsamer App-Zustand

**Kernbereiche:** Zeilen 56–78, 120–124, 145–153, 2151–2155.

Enthält die Engine-Erzeugung, die gemeinsamen Engine-StateFlows, Navigation, initiale Modellliste/Restore, gemeinsame UI-Statusmeldungen und das Schließen der Engine. Dieser Bereich ist aktuell kein isolierter, kleiner Block: Er bildet den gemeinsamen Besitzer der Engine und der Zustände, die mehrere Screens gleichzeitig verwenden.

## 3. Tatsächliche UI- und Konstruktor-Kopplungen

Die Aufrufer wurden zusätzlich kontrolliert:

| Datei | Aktuelle ViewModel-Verwendung |
|---|---|
| `MainActivity.kt` | Ruft nur `MainScreen()` auf. |
| `MainScreen.kt` | Erstellt standardmäßig genau ein `EngineViewModel` über `viewModel()`; liest Navigation/Status, exportiert Diagnosen und reicht dieselbe Instanz an alle drei Screens weiter. |
| `ChatScreen.kt` | Erhält `EngineViewModel` und liest Chat-State, Modell-/Engine-State, Konfiguration, Fehler und Ladefortschritt; ruft Chat-Aktionen sowie `selectTab()`/Fehler-Dismiss auf. |
| `ModelImportScreen.kt` | Erhält dieselbe Instanz; liest Modellliste, aktuelles Modell, Importstatus, Ladefortschritt, Speicher-Override und Fehler; ruft Import-, Load-, Unload- und Refresh-Aktionen auf. |
| `SettingsScreen.kt` | Erhält dieselbe Instanz; liest EngineConfig, Systemprompt, aktuelles Modell, Benchmarkstatus und Benchmarkreport; ruft Config-/Prompt-Updates und Diagnoseaktionen auf. |
| `LocalLlmEngineAppTest.kt` | Erstellt `EngineViewModel(application)` direkt und prüft u. a. Navigation und Initialzustand. |

### Konsequenz für die spätere Extraktion

Eine reine Verschiebung von Methoden in mehrere unabhängige `AndroidViewModel`-Klassen genügt nicht, um das bestehende Verhalten automatisch zu erhalten:

1. **Ein geteilter Engine-Besitzer:** Der sekundäre Konstruktor `EngineViewModel(application)` erzeugt aktuell `LocalLlmEngine.create(application)`. Würden neue ViewModels jeweils auf dieselbe Standard-Compose-Factory mit eigener Application-Konstruktion gesetzt, könnten getrennte Engine-Instanzen entstehen. Die drei Screens verwenden heute aber bewusst dieselbe Instanz.
2. **Eine gemeinsame ViewModel-Instanz für alle Screens:** `MainScreen` erzeugt ein ViewModel und übergibt es an alle Screens. Das Aufteilen der Screen-Parameter auf getrennte ViewModels ist eine UI-Verdrahtungsänderung und keine bloße Quelltextverschiebung.
3. **Übergreifende Zustände:** `isImporting`, `isGenerating`, `isBenchmarking`, `currentModel`, `statusMessage` und `errorMessage` werden in mehreren Bereichen zur Anzeige oder gegenseitigen Sperre verwendet.
4. **Scope-gebundene Jobs:** `viewModelScope`, `inferenceJob`, `configurationSmokeJob` und `onCleared()` hängen am Lifecycle des aktuellen ViewModels. Beim Verschieben muss geklärt werden, welcher Besitzer die jeweiligen Jobs und die Engine beendet.
5. **Start-Reihenfolge:** `init` aktualisiert erst die Modellliste und startet danach den Last-Model-Restore. Der Restore nutzt Config, SAF-Rechte, den gemeinsamen Loader und UI-Status.
6. **Bestehende Testkonstruktion:** Die direkte Konstruktion `EngineViewModel(application)` muss erhalten bleiben oder mit einer exakt dokumentierten, minimalen Anpassung kompatibel gehalten werden.

Diese Punkte sind keine Aufforderung, jetzt Architektur oder Code umzubauen. Sie markieren die Stellen, an denen ein angeblich reines Verschieben ohne Verkabelungsänderung möglicherweise nicht möglich ist.

## 4. Vorläufige Zielzuordnung für die Freigabe

Die folgende Zuordnung ist ein Vorschlag für die spätere Extraktion. Sie ändert weder Namen noch Verhalten und ist noch keine Freigabe zur Umsetzung.

| Zieldatei / Eigentümer | Zugeordnete Quellbereiche |
|---|---|
| `ChatViewModel.kt` | Chat-State 82–95, Eingabe 160–162, Conversation-Aktionen 2003–2130 |
| `ModelManagementViewModel.kt` | Modelllistendaten 47–54 und 117–118, Modellstatus/Memory-Override 72–78 und 126–128, Refresh/Load/Restore/Unload 209–340 und 516–615 |
| `ModelImportViewModel.kt` | Importstatus 130–131 und SAF-Import 342–514 |
| `SettingsViewModel.kt` | Config/Systemprompt-State 97–115 und Update-Funktionen 164–199 |
| `BenchmarkDiagnosticsViewModel.kt` | Benchmark-State 133–143 sowie Benchmark-/Sweep-/Smoke-Methoden und Helfer 617–2001 |
| Bestehendes `EngineViewModel.kt` als zentraler Owner/Coordinator | Engine-Konstruktion und gemeinsame Engine-States, Navigation, shared Status/Error, Init-/Restore-Orchestrierung, Diagnostics-Export und Engine-Close; konkrete Restmenge erst nach schrittweiser Freigabe festlegbar |

**Nicht zuordnen, bevor separat geprüft:** `lastModelStore` und Restore-Orchestrierung (derzeit gleichzeitig Lifecycle, Settings-Persistenz und Model Management); `AppTab` (Navigation); `ImportedModelEntry` (UI-Modell der Modellliste); gemeinsame Status-/Fehlermeldungen; Engine-States und Engine-Close.

## 5. Nicht-Ziele und Schutzregeln für spätere Schritte

- Keine Use Cases, Repositories, neuen DI-Frameworks oder sonstigen Architektur-Umbauten.
- Keine neuen Dependencies und keine neuen Tests.
- Keine Umbenennung öffentlicher APIs, AIDL- oder Parcelable-Verträge.
- Keine Verhaltens-/Default-/Formatänderungen; der primäre Diff muss aus verschobenen, inhaltlich identischen Blöcken bestehen.
- Jede unvermeidbare inhaltliche Änderung muss vorab als eindeutiger Fehler/Blocker benannt und exakt dokumentiert werden; keine stillen Verbesserungen.
- Vor jeder Extraktion den betreffenden Quellblock und seine UI-/Test-Aufrufer sichern.
- Nach jedem Cluster-Schritt muss `./gradlew assembleDebug` erfolgreich sein; erst dann den Schritt committen und zum nächsten Cluster übergehen.
- Am Ende `./gradlew assembleDebug test` ausführen und Diff auf reine Verschiebungen plus dokumentierte minimale Verdrahtung prüfen.
- Echte Geräte-Funktionstests werden **nicht** durch diesen Arbeitsablauf ausgeführt. Die manuellen Geräte-/Funktionsprüfungen erfolgen durch den Nutzer; der abschließende Smoke-Test-Plan benennt die Screens und Abläufe.
- Vorher-/Nachher-LOC, verschobene Zeilen, Build/Test-Ergebnisse und manuelle Smoke-Test-Checkliste in dieser Datei ergänzen.


## 6. Laufendes Refactoring – Status 2026-11-14

**Freigabe:** Der Nutzer hat die Cluster-Extraktion mit „Let's go“ freigegeben. Echte Geräte-Funktionstests führt ausschließlich der Nutzer selbst aus.

### 6.1 Aktueller main-Stand

- Aktueller HEAD bei dieser Aktualisierung: a6bcd532be45bc7d08381ed802c388dd6219cf4e.
- EngineViewModel.kt: 1.750 Zeilen, 94.792 Bytes (vorher 2.156 Zeilen, 113.109 Bytes).
- ChatViewModel.kt: neu, 201 Zeilen, 8.862 Bytes.
- ModelManagementViewModel.kt: neu, 276 Zeilen, 11.943 Bytes.
- Gegenüber dem Analyse-Stand wurden ausschließlich diese drei Kotlin-Dateien geändert: ChatViewModel hinzugefügt, ModelManagementViewModel hinzugefügt, EngineViewModel angepasst.
- Noch nicht extrahiert: Model Import, Settings / Preferences und Benchmark / Diagnostics.

### 6.2 Chat / Conversation – Code- und CI-Status

Commits auf main:
- b0d96c380914445d36f790b7da3dc420f6caacbe – Chat-Block extrahiert.
- 65a3efaae6fb56b15ad90bd78bef22f05efe7912 – EngineViewModel an ChatViewModel-Basis verdrahtet.
- 7e6a78b6509355062ee2aeeb0a847fca7399c222 – fehlende geschützte State-Abhängigkeiten ergänzt.
- 4d5badc16206b7e65b81a4bd23f17838f43a7aee – bestehende Engine-States mit ChatViewModel verdrahtet.

Verifikation:
- Exakt verschobene Chat-Blöcke wurden bytegenau im neuen ChatViewModel wiedergefunden; keine Duplikate der verschobenen Methoden/Properties in EngineViewModel.
- Unit-Tests für Commit 4d5badc: GitHub Actions Run 43556039525 erfolgreich; :llm-engine:testDebugUnitTest, :app:testDebugUnitTest und nativer Host-Prompt-Cache-/Truncation-Test erfolgreich.
- APK-Workflow Run 43556039527: Gradle-Schritt „Build APK and process-isolation test APK“ erfolgreich; APK-Existenz, Instrumentation-Test-APK, Manifest und Native-Debug-Flags erfolgreich geprüft; beide APK-Artefakte hochgeladen. Der nachfolgende Android-Emulator-Prozessisolationstest wurde abgebrochen, daher ist der Gesamt-Workflow als cancelled markiert. APK-Build selbst war erfolgreich; Emulator-Instrumentation ist nicht als bestanden belegt.
- Die erste CI-Runde hatte Compilerfehler wegen nicht sichtbarer State-Abhängigkeiten. Diese wurden mit den oben genannten Wiring-Commits behoben; die nachfolgende JVM-Testausführung war grün.

### 6.3 Model Management – Code- und CI-Status

Commits auf main:
- f6e8666eff86c5c2916cc95814b12f3aa4c315ec – ModelManagementViewModel angelegt und Model-Management-Code verschoben.
- edf252984b99381c70632ee581d788168b99f64f – EngineViewModel an ModelManagementViewModel delegiert.
- a6bcd532be45bc7d08381ed802c388dd6219cf4e – Engine-Config-Abhängigkeit verdrahtet.

Aktuelle CI für HEAD a6bcd532:
- Unit Tests: Run 43556762677, Status in_progress bei letzter Abfrage.
- Android APK Build: Run 43556762673, Status in_progress bei letzter Abfrage.
- Daher sind die Model-Management-Änderungen noch nicht CI-verifiziert. Abgebrochene ältere Läufe auf Zwischen-Commits gelten nicht als Fehlernachweis und nicht als erfolgreiche Verifikation.

### 6.4 Nächste Schritte und Verifikationsregeln

1. Zuerst die beiden laufenden CI-Runs für HEAD a6bcd532 abschließen und dokumentieren. Bei Fehlern nur den konkret belegten Fehler beheben und neue CI abwarten.
2. Erst nach erfolgreicher JVM-Test- und APK-Build-Verifikation mit dem nächsten Cluster beginnen.
3. Nach jedem Cluster die verschobenen Blöcke, Engine-/ViewModel-Wiring, Dateigrößen, Commit-SHAs und GitHub-Actions-Ergebnisse hier aktualisieren.
4. Emulator-/CI-Instrumentation separat von echten Geräte-Funktionstests ausweisen. Echte Geräte-Funktionstests werden vom Nutzer durchgeführt und nicht durch diesen Arbeitsablauf behauptet.
5. Abschlussprüfung nach allen Clustern: assembleDebug und test in CI; finaler Diff muss aus reinen Verschiebungen plus dokumentierter minimaler Verdrahtung bestehen. Keine neuen Tests oder Dependencies.

**Wichtig:** Statusangaben in dieser Datei sind ein fortlaufendes Protokoll und müssen nach jedem Cluster sowie bei jedem wesentlichen CI-Ergebnis aktualisiert werden.
