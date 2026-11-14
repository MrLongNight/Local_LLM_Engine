# Refactoring Status — Local_LLM_Engine

**Fokus: Task 3 – SettingsScreen in Sektionen zerlegen.**

Task 2 ist strukturell umgesetzt; Unit Tests grün, APK und Instrumentation-APK gebaut. Emulator-Abnahme separat verfolgen.
Repository: MrLongNight/Local_LLM_Engine · Branch: main
Code-HEAD für die laufende Verifikation: c48090423ae985226f92e1c7d805d308023504cc

## Task 2 — aktueller Stand

| Cluster | Datei | Status |
|---|---|---|
| Public Facade / Navigation / Diagnostics Export | EngineViewModel.kt | auf ca. 2,3 KB reduziert |
| Chat | ChatViewModel.kt | bereits extrahiert |
| Model Management / Load / Restore / Unload | ModelManagementViewModel.kt | bereits extrahiert |
| SAF Import | ModelImportViewModel.kt | extrahiert |
| Settings / Config / System Prompt | SettingsViewModel.kt | extrahiert |
| Shared Benchmark State / Safety | BenchmarkDiagnosticsViewModel.kt | extrahiert |
| Backend Acceptance / CPU-Vulkan Benchmark | BackendBenchmarkViewModel.kt | extrahiert |
| Vulkan Layer / Q4_K Sweeps | VulkanDiagnosticsViewModel.kt | extrahiert |
| Configuration Smoke Test | ConfigurationSmokeViewModel.kt | extrahiert |
| Startup / Status / Engine Close | EngineLifecycleViewModel.kt | extrahiert |

Alle Screens verwenden weiterhin dieselbe EngineViewModel-Instanz. Der Konstruktor EngineViewModel(Application) und die öffentlichen Screen-Aufrufstellen bleiben bestehen.

**Download:** Kein eigener Download-Cluster in EngineViewModel. HTTPS/RemoteUrl liegt bereits im :llm-engine-Modul in ModelSourceResolver; keine Doppelimplementierung.

**1:1-Prüfung:** 20 Methoden-Körper sind im Quellvergleich identisch. Vier Helper/Datentypen wurden nur von private auf protected gesetzt, damit die Unterklassen sie verwenden können.

**CI-Korrektur:** d86b2cf schlug wegen privatem _benchmarkReport beim Kotlin-Compile fehl. c480904 ändert nur dessen Sichtbarkeit auf protected; der öffentliche benchmarkReport-StateFlow bleibt unverändert.

## Verifikation auf c480904

- Unit Tests Run 43561524744: completed / success.
- APK Workflow Run 43561524767 für denselben Code-SHA: APK- und Instrumentation-APK-Build erfolgreich; Manifest-/Native-Prüfungen grün; Upload erfolgreich. Artefakte: Debug APK 14100747596 (36.938.722 Bytes), Instrumentation APK 14100338808 (24.297.890 Bytes). Emulator-Prozessisolation läuft noch (zuletzt Step 22, started 05:36:12Z); Gesamtworkflow daher noch in_progress.
- Fehlerlauf d86b2cf: Compilefehler, kein Testfehler. Host C++ prompt-cache tests waren grün.

## Abschlusskriterien Task 2

1. APK und Instrumentation-APK für den finalen Code-SHA erfolgreich gebaut und Artefakte geprüft.
2. Emulator-Instrumentation für denselben SHA erfolgreich; bei Fehler Ursache im Log isolieren und beheben.
3. Öffentliche Aufrufstellen, Konstruktor und Lifecycle geprüft; keine fachlichen Settings-, Import-, Benchmark- oder Lifecycle-Funktionen in der Fassade zurückgelassen.
4. Nutzer führt reale Geräte-Smoke-Tests selbst aus; CI darf nicht als Zielgeräteabnahme ausgegeben werden.

## Danach zurückgestellte Aufgaben

- SettingsScreen: bisher nur SystemPromptSection extrahiert; übrige Sektionen offen.
- ModelImportScreen: noch nicht zerlegt.
- InferenceServiceProcessTest: noch nicht zerlegt; ein bestehender Testkörper liegt bereits über 10 KB, daher Größenkonflikt ohne Rewrite.
- :app Paketstruktur: Inventar erfasst, Moves offen.
- Use Cases/Repositories: optional; nur nach Task 2 auf echte, nicht bereits ausgelagerte Kandidaten prüfen.

Status und Fehlerursachen werden hier aktualisiert. Keine getrennten Refactoring-Dokumente pro Untertask.

## Task 3 — SettingsScreen

**Status:** Backend/GPU-Sektion extrahiert; Korrektur der nachträglich erkannten Imports committed; CI-Abnahme des korrigierten Stands ausstehend. Die frühere Freigabe zur mechanischen Extraktion wird verwendet. Screen-Signatur, State-Sammlung, LaunchedEffect, LazyColumn-Reihenfolge und Card-/Column-Hierarchie bleiben erhalten.

| Sektion | Datei | Status |
|---|---|---|
| System Prompt | `ui/settings/sections/SystemPromptSection.kt` | bereits extrahiert |
| Backend + GPU-Layer | `ui/settings/sections/BackendSettingsSection.kt` | extrahiert |
| Vulkan DMMV + GDN | `ui/settings/sections/VulkanTuningSection.kt` | gleiche Card/Column-Hierarchie |
| Kontext / Inferenz | `ui/settings/sections/InferenceSettingsSection.kt` | offen |
| Backend-Abnahmetest | `ui/settings/sections/BackendAcceptanceTestSection.kt` | offen |
| Q4_K-Diagnose | `ui/settings/sections/VulkanQ4kDiagnosticsSection.kt` | offen |
| Benchmark-Ergebnis / Diagnosehinweis | `ui/settings/sections/BenchmarkResultSection.kt` | offen |

BackendSettingsSection hält die bestehende Card und Column; VulkanTuningSection ersetzt nur den inneren UI-Block an derselben Position. Keine State-Logik wurde verschoben. Nächster Schritt nach erfolgreicher Build-/Testprüfung.
