# AGENTS.md — Local_LLM_Engine

## Verbindliche Lesereihenfolge

1. Diese Datei: Agent-Vertrag und Projektschutzregeln.
2. `README.md`: Module, Toolchain, Build- und Laufzeitgrenzen.
3. `REFACTORING_ROADMAP.md`: einziger Live-Statusregister für Refactoring, Freigaben, Abhängigkeiten und CI-Nachweise.
4. Task-spezifische Datei `REFACTORING_*.md`, die im Roadmap-Eintrag verlinkt ist.
5. Aktuelle Quelltexte am aktuellen `main`-HEAD. Zeilennummern in Analysedokumenten gehören immer zu einem angegebenen Blob-Snapshot.

Bei Widersprüchen nicht raten: aktuellen Source, Branch-HEAD und zugehörigen Actions-Run erneut lesen; anschließend die veraltete Dokumentation korrigieren.

## Repository-Invarianten

- Repository: `MrLongNight/Local_LLM_Engine`; ausschließlich vorhandenen Branch `main` verwenden. Keine Feature-Branches erzeugen.
- Module: `:app` ist Demo-App/UI; `:llm-engine` ist wiederverwendbare Engine/API/JNI/Native-Bibliothek.
- `llm-engine/src/main/cpp/llama.cpp` ist ein gepinntes Submodule. Nicht verändern, patchen oder aktualisieren, sofern nicht ausdrücklich angefordert.
- Öffentliche Kotlin-/Java-APIs, AIDL-/Parcelable-Verträge, Android-Manifest-Komponenten und Prozessnamen, JNI-Symbole, CMake-Pfade, ABIs und Source Sets bewahren.
- Keine NPU-Backend-Änderung, kein llama.cpp-Patch, keine neue Dependency, kein DI-Framework, keine neue Abstraktion und keine Default-/Performance-Änderung ohne gesonderte ausdrückliche Freigabe.
- Korrektheit und Verhaltensidentität haben Vorrang vor Dateigrößen-Zielen. Unvereinbare Akzeptanzkriterien melden, nicht stillschweigend abschwächen.

## Git- und Implementierungsprotokoll

- Vor jedem Schreibvorgang aktuellen `main`-HEAD und Blob-SHAs aller betroffenen Dateien erneut lesen.
- Optimistic ref update mit erwartetem Parent SHA verwenden. Niemals einen fortgeschrittenen `main`-HEAD überschreiben.
- Ein fokussierter Code-Commit je freigegebener Sektion/Klasse/Batch. Keine sachfremden Änderungen kombinieren.
- Eine Analyse mit Status **STOP / Freigabe ausstehend** ist ausdrücklich keine Implementierungserlaubnis.
- Bei mechanischen Refactorings Source-Blöcke exakt übertragen. Keine Bedingungen, Callback-Reihenfolgen, State-Ownership, Defaults, UI-Reihenfolge oder Testnamen "aufräumen".
- 1:1-Diff durch Quellenvergleich belegen; nur Funktions-/Klassenhülle und Einrückung normalisieren. Unvermeidbare Sichtbarkeits-/Import-/Package-/Manifest-Verkabelung separat dokumentieren.
- Statusdokumente nach jedem Code-Commit, CI-Ergebnis, Fehler und Freigabeschritt aktualisieren. Roadmap und Task-Detaildatei gemeinsam aktualisieren.
- Bestehende Mermaid-Dokumente über ihre bestehende Document-ID aktualisieren; keine Diagramm-Duplikate pro Iteration anlegen.

## Build- und Verifikationswahrheit

- Gradle-Wrapper-Launcher `gradlew` und Wrapper-JAR sind nicht eingecheckt. Niemals behaupten, `./gradlew` ausgeführt zu haben.
- Aktuelle GitHub-Actions verwenden Gradle 9.3.1 und JDK 21. Java Source-/Target-Kompatibilität ist Java 17.
- Unit-Workflow: `.github/workflows/unit-tests.yml`. Wesentliche Tasks:
  ```bash
  gradle --no-daemon --continue --build-cache \
    :llm-engine:testDebugUnitTest :app:testDebugUnitTest
  ```
  Zusätzlich läuft der Host-C++-Prompt-Cache-/Truncation-Test. Dieser Workflow baut keine Android-Native-Libraries und kein APK.
- APK-Workflow: `.github/workflows/manual-apk.yml`. Er baut `:app:assembleDebug` und `:llm-engine:assembleDebugAndroidTest`, prüft APK/Manifest, lädt beide APK-Artefakte hoch und führt danach die konfigurierte Android-36-Emulator-Prozessisolation-Instrumentation aus.
- **APK Build und Gesamtworkflow trennen:** APK-/Test-APK-Build kann erfolgreich sein, während der Gesamtworkflow wegen eines späteren Emulator-Tests fehlschlägt. Steps und Gesamt-Conclusison getrennt berichten.
- Ein Ergebnis ist nur dann grün, wenn der exakte Workflow für den exakten Code-SHA `completed / success` ist. Jobs/Steps und benötigte Artefakte prüfen.
- Emulator-Instrumentation ist keine Zieltelefon-Abnahme. Niemals reale Modellladung, SAF-Provider-Verhalten, Vulkan-Offload, Mali-Performance, Cancel/Timeout oder Speicherfreigabe auf dem Zielgerät aus CI ableiten.
- Workflow-Pfadfilter beachten: reine Dokumentationscommits lösen nicht unbedingt Builds aus. Build nicht aus Commit-Erstellung ableiten.
- GitHub-only Schreibzugriff committet direkt auf `main`; Actions startet nach dem Commit. Je freigegebener Sektion einen Code-Commit erstellen und erst nach abgeschlossener Prüfung für den exakten SHA die nächste Code-Sektion committen. Keinen Pre-Commit-Build behaupten.

## Mechanische Refactoring-Gates

- Öffentliche Einstiegspunkt-Signaturen unverändert.
- Bei Test-Splits Testmethoden-Namen und -Körper byte-identisch halten. Fixture-Lifecycle beachten; keine Setup-/Teardown-Methode erfinden.
- UI-Reihenfolge, LazyColumn-Builder, Keys, Modifier, Test-Tags, Labels, Accessibility, Callbacks, State Collection und ViewModel-Ownership unverändert halten.
- Bei Package-Moves Produktions- und Testsources inventarisieren. Package-Deklarationen und Imports sind strukturelle Quellmetadaten; Manifest-/JNI-/ProGuard-/Robolectric-/Instrumentation-Referenzen gesondert prüfen.
- Keine leeren `data`, `domain`, `util` oder `di`-Packages nur für die Optik anlegen.
- Wenn ein Größenlimit nicht ohne Umschreiben eines Testkörpers erreichbar ist, Ausnahme belegen und explizite Entscheidung einholen.

## Live-Dokumentationskarte

- `REFACTORING_ROADMAP.md` — einziger aktueller Gesamtstatus und Task-Abhängigkeiten; [gespeicherte Mermaid-Roadmap](https://mermaid.ai/app/projects/d7b9e612-2e4e-4eba-ac5b-098c4d58b067/diagrams/50efdfab-718e-4578-8580-01c437062111/version/v0.1/edit?utm_source=mermaid_app_widget&utm_medium=mermaid-chatgpt-app).
- `REFACTORING_SETTINGS.md` — SettingsScreen-Extraktion und Mermaid-Struktur.
- `REFACTORING_MODEL_IMPORT.md` — ModelImportScreen-Analyse; bis Freigabe keine Codeänderung.
- `REFACTORING_TESTS.md` — InferenceServiceProcessTest-Analyse; bis Freigabe keine Codeänderung.
- `REFACTORING_PACKAGES.md` — vollständiges :app Paket-Inventar; bis Freigabe keine Moves.
- `REFACTORING_USE_CASES.md` — mögliche Use-Case-Kandidaten; bis Freigabe keine Extraktion.
- `REFACTORING_ENGINE_VM.md` — frühere Chat-/Model-Management-ViewModel-Analyse und historische Verifikation.
- `REFACTORING_BASELINE.md` — historischer Baseline-Snapshot; kein Live-Status.
- `docs/IMPLEMENTATION_PLAN.md` — technische Implementierungshistorie; kein Live-Refactoring-Register.

## Verifizierter Snapshot 14.11.2026

- Settings-Code-HEAD: `4492dc3ec7a281cd64640f0186482f98fc07fca8`.
- Unit Tests Run `43557763450`: completed / success.
- APK Build + Instrumentation-APK + Manifest-Checks im Run `43557763490`: erfolgreich; APK- und Test-APK-Artefakte hochgeladen.
- Derselbe Run schlug im nachgelagerten Emulator-Test `realGgufIsReloadedAndFollowupInferenceSucceedsAfterTimeoutKill` mit Statusvergleich `expected DEBUG_NATIVE_INFERENCE_BLOCKED, actual MODEL_LOADED` fehl.
- Run `43557763490` wurde als Retry Attempt 2 erneut gestartet; aktuellen Status in Roadmap/GitHub Actions erneut lesen.
- Test- und Engine-Source-Blobs dieses Fehlers sind identisch zum vorherigen grünen APK-Code-HEAD `a6bcd532be45bc7d08381ed802c388dd6219cf4e`; nicht ohne weitere Belege als Settings-Regression bezeichnen.
- Chat-/Model-Management-ViewModel-Splits: Code-HEAD `a6bcd532be45bc7d08381ed802c388dd6219cf4e`, Unit Run `43556762677` success, APK Run `43556762673` success, APK-Artefakte belegt.
