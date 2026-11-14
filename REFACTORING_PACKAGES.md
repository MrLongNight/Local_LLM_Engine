# :app Paket-/Layer-Reorganisation — Inventar

**Status:** SCHRITT 1 abgeschlossen — **STOP / Freigabe ausstehend**. Keine Quelldatei verschoben.  
**Repository:** `MrLongNight/Local_LLM_Engine` · **Branch:** `main`  
**Analyse-Code-HEAD:** `4492dc3ec7a281cd64640f0186482f98fc07fca8`  
**Inventar:** alle Kotlin-/Java-Dateien unter `app/src/main/java` und `app/src/test/java`: **19 Produktionsdateien + 6 JVM-Testdateien = 25 Dateien**.

## 1. Produktionsquellen — aktuelle Location → Zielpaket

Zielpfade sind relativ zu `app/src/main/java/com/example/`.

| Aktuelle Datei | Zielpaket / Pfad |
|---|---|
| `ui/BackendBenchmarkPlan.kt` | `domain/benchmark/BackendBenchmarkPlan.kt` |
| `ui/ChatScreen.kt` | `ui/chat/ChatScreen.kt` |
| `ui/ChatViewModel.kt` | `ui/viewmodel/ChatViewModel.kt` |
| `ui/CorrectnessGatePlan.kt` | `domain/benchmark/CorrectnessGatePlan.kt` |
| `ui/EngineViewModel.kt` | `ui/viewmodel/EngineViewModel.kt` |
| `ui/InferenceConfigSmokePlan.kt` | `domain/benchmark/InferenceConfigSmokePlan.kt` |
| `ui/LastModelStore.kt` | `data/model/LastModelStore.kt` |
| `ui/MainScreen.kt` | `ui/shell/MainScreen.kt` |
| `ui/ModelImportScreen.kt` | `ui/modelimport/ModelImportScreen.kt` |
| `ui/ModelLoadProgressIndicator.kt` | `ui/components/ModelLoadProgressIndicator.kt` |
| `ui/ModelManagementViewModel.kt` | `ui/viewmodel/ModelManagementViewModel.kt` |
| `ui/SafGgufFolderScanner.kt` | `data/saf/SafGgufFolderScanner.kt` |
| `ui/SettingsScreen.kt` | `ui/settings/SettingsScreen.kt` |
| `ui/VulkanLayerSweepPlan.kt` | `domain/benchmark/VulkanLayerSweepPlan.kt` |
| `ui/settings/sections/SystemPromptSection.kt` | `ui/settings/sections/SystemPromptSection.kt` |
| `ui/theme/Color.kt` | `ui/theme/Color.kt` |
| `ui/theme/Theme.kt` | `ui/theme/Theme.kt` |
| `ui/theme/Type.kt` | `ui/theme/Type.kt` |
| `MainActivity.kt` | **Entscheidung erforderlich**: `ui/app/MainActivity.kt` oder FQCN `com.example.MainActivity` beibehalten |

`MainActivity.kt` liegt aktuell unter `app/src/main/java/com/example/MainActivity.kt`; alle anderen oben gelisteten Dateien liegen im aktuellen `ui`-Baum.

## 2. JVM-Tests — aktuelle Location → Zielpaket

Zielpfade sind relativ zu `app/src/test/java/com/example/`.

| Aktuelle Datei | Zielpaket / Pfad |
|---|---|
| `LocalLlmEngineAppTest.kt` | `ui/app/LocalLlmEngineAppTest.kt` |
| `ui/BackendBenchmarkPlanTest.kt` | `domain/benchmark/BackendBenchmarkPlanTest.kt` |
| `ui/CorrectnessGatePlanTest.kt` | `domain/benchmark/CorrectnessGatePlanTest.kt` |
| `ui/InferenceConfigSmokePlanTest.kt` | `domain/benchmark/InferenceConfigSmokePlanTest.kt` |
| `ui/LastModelStoreTest.kt` | `data/model/LastModelStoreTest.kt` |
| `ui/VulkanLayerSweepPlanTest.kt` | `domain/benchmark/VulkanLayerSweepPlanTest.kt` |

## 3. Layer-Entscheidungen

- **ui/** — Activities, Screens, Composables, ViewModels und Theme.
- **data/** — bestehende Persistenz und SAF-Dateisystem-/Dokumenten-Scanner: `LastModelStore`, `SafGgufFolderScanner`.
- **domain/** — bestehende Benchmark-/Correctness-/Configuration-Planungsalgorithmen: die vier `*Plan.kt`-Dateien und ihre Tests.
- **util/** — derzeit kein eigenständiges Sourcefile begründet; kleine private Formatter verbleiben bei ihrem aktuellen Owner.
- **di/** — im aktuellen :app-Inventar gibt es keine DI-Source und kein DI-Framework. Keine Container, Module oder leere Packages hinzufügen.

## 4. Package- und Manifest-Konflikt — vor Umsetzung entscheiden

Ein echter Kotlin-Paket-Move erfordert eine Änderung der `package`-Deklaration und der betroffenen Imports. Das ist mehr als ein reines `git mv` plus Importänderungen, aber strukturelle Quellmetadaten – keine Business-Logik.

**MainActivity-Ausnahme:** Aktuell lautet der FQCN `com.example.MainActivity`; das Manifest referenziert `.MainActivity`. Wenn die Datei nach `com.example.ui.app` verschoben wird, muss der Manifest-Eintrag zu `.ui.app.MainActivity` geändert werden. Alternativ bleibt der FQCN bestehen und MainActivity ist die ausdrücklich dokumentierte Root-Package-Ausnahme.

Vor Umsetzung muss der Nutzer eine Variante freigeben:
1. Package-Deklarationen und Imports ändern sowie den Manifest-Klassennamen für MainActivity aktualisieren; oder
2. `MainActivity` im aktuellen FQCN belassen und als Root-Entrypoint-Ausnahme dokumentieren.

Zusätzlich String-basierte Klassennamen, ProGuard/R8-Regeln, Robolectric-/Instrumentation-Package-Referenzen und manifestierte Android-Komponenten durchsuchen.

## 5. Batch-Vorschlag

1. UI-Dateien und ihre App-Test-Imports; MainActivity-Entscheidung vorher klären.
2. Vorhandene Data-Dateien und zugehörige Tests.
3. Vorhandene Domain-Planungsobjekte und zugehörige Tests.
4. Kein Util- oder DI-Batch, solange kein vorhandenes Sourcefile dafür entdeckt und freigegeben wird.

Nach jedem Batch: Inventar-Diff → Pfad-/Package-/Import-Diff prüfen → Unit Tests + APK/Emulator-Actions → Commit. Package-Migration nicht mit Logik-Extraktion (R5) vermischen.

## 6. Verifikations- und Freigabe-Gates

- [ ] Nutzerfreigabe für Inventar und Package-Policy.
- [ ] 25/25 Source-Dateien abgedeckt; keine Klasse oder Datei umbenennen.
- [ ] Pfad-Moves und nur Package-Deklaration/Import-Änderungen; Manifest-Update nur mit expliziter Freigabe.
- [ ] Produktions- und Test-Packages konsistent verschieben.
- [ ] Unit Tests, APK Build und Emulator-Instrumentation grün.
- [ ] Vollständiger Inventar-Abgleich nach dem letzten Batch.

**STOP:** Inventar only. Kein Package- oder Source-Move erfolgt.
