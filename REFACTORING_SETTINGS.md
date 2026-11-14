# SettingsScreen – Analyse vor der Sektionsextraktion

**Status:** SCHRITT 2 läuft – Freigabe erteilt; SystemPromptSection extrahiert, CI-Abnahme ausstehend  
**Repository:** `MrLongNight/Local_LLM_Engine`  
**Branch:** `main`  
**Analyse-HEAD:** `98cbaf367ba8661e2377c09cf3ca0514bcb5ec18`
**Aktueller Extraktionsstand:** SystemPromptSection extrahiert; GitHub Actions laufen
**Mermaid-Diagramm:** [SettingsScreen-Struktur live öffnen](https://mermaid.ai/app/projects/d7b9e612-2e4e-4eba-ac5b-098c4d58b067/diagrams/4583f977-9047-4e7c-bf08-18105680eae0/version/v0.1/edit?utm_source=mermaid_app_widget&utm_medium=mermaid-chatgpt-app)  
**Mermaid documentID:** `4583f977-9047-4e7c-bf08-18105680eae0` (bestehendes Diagramm wird aktualisiert, keine Duplikate)  
**Datei:** `app/src/main/java/com/example/ui/SettingsScreen.kt`  
**Datei-Snapshot (Git-Blob):** `68c1f57b8f0ffc78cb0a3ae86a2c1f07a8eff4dd`  
**Umfang:** 22.823 Bytes laut GitHub-Dateimetadaten, 438 Zeilen

> In diesem Schritt wurden keine Kotlin-, UI-, Gradle- oder Testdateien geändert. Diese Datei dokumentiert ausschließlich den Ist-Zustand und einen mechanischen Extraktionsplan.

## 1. Wichtiger Befund zum Ist-Zustand

`SettingsScreen.kt` enthält aktuell **genau eine vorhandene `@Composable`-Funktion**:

- `SettingsScreen(viewModel: EngineViewModel, modifier: Modifier = Modifier)`, Zeilen 37–437.

Die sechs sichtbaren Einstellungsbereiche sind aktuell keine separaten Composable-Funktionen. Sie liegen als sechs Inline-`item { ... }`-Blöcke innerhalb der `LazyColumn`. Es gibt in dieser Datei **keine privaten Composable- oder sonstigen Helper-Funktionen**, die separat verschoben werden könnten.

Damit ist ein wörtliches „nur bereits vorhandene Composable-Funktionen verschieben“ nicht ausreichend, um die gewünschte Aufteilung zu erreichen. Dafür müssen die Inhalte der Inline-`item`-Blöcke mechanisch in neue Composable-Funktionen überführt werden. Der Inhalt der jeweiligen UI-Blöcke soll dabei 1:1 übernommen werden; geändert werden nur die strukturelle Hülle (Funktionsdeklaration und Aufruf innerhalb des bestehenden `item`-Blocks).

## 2. Zuordnung der vorhandenen UI-Sektionen

Zeilenbereiche beziehen sich auf den oben genannten unveränderten Snapshot. Die Bereiche enthalten jeweils den bestehenden Abschnittskommentar und/oder den Inline-`item`-Block.

| Composable (Vorschlag) | Zeilen | Vorgesehene neue Datei |
|---|---:|---|
| `SystemPromptSection` | 59–86 | `ui/settings/sections/SystemPromptSection.kt` |
| `BackendSettingsSection` – Kartenkopf, Backend-Auswahl und GPU-Layer-Regler | 88–137 | `ui/settings/sections/BackendSettingsSection.kt` |
| `VulkanTuningSection` – Vulkan-DMMV-Workgroup, GDN-Policy und Anzeige des aktiven Backends | 139–211 | `ui/settings/sections/VulkanTuningSection.kt` |
| `InferenceSettingsSection` | 216–360 | `ui/settings/sections/InferenceSettingsSection.kt` |
| `BackendAcceptanceTestSection` | 362–390 | `ui/settings/sections/BackendAcceptanceTestSection.kt` |
| `VulkanQ4kDiagnosticsSection` | 391–418 | `ui/settings/sections/VulkanQ4kDiagnosticsSection.kt` |
| `BenchmarkResultSection` – Testbericht und Diagnosehinweis | 419–435 | `ui/settings/sections/BenchmarkResultSection.kt` |

### 2.1 Sonderfall Backend-Karte

Der ursprüngliche Backend-Bereich (Zeilen 88–214) ist **eine einzige Card und ein einziger LazyColumn-Eintrag**. Sein Inline-Quellsegment umfasst bereits rund 7.681 UTF-8-Bytes, bevor neue Datei-Imports und die Funktionsdeklaration hinzukommen. Als einzelnes neues Kotlin-File würde es deshalb die Grenze von 8 KB voraussichtlich überschreiten.

Vorgeschlagene Lösung: Die bestehende Card-/Column-Hülle bleibt in `BackendSettingsSection`. Die darin enthaltenen Vulkan-/GDN-Steuerelemente werden als `VulkanTuningSection` aus derselben Column heraus aufgerufen. Es wird **keine zweite Card und kein zusätzlicher LazyColumn-Eintrag** erzeugt. Damit bleibt die sichtbare UI-Hierarchie unverändert und beide Dateien können unter 8 KB bleiben.

## 3. Gemeinsame Zustände und ViewModel-Zugriffe

Die folgenden Werte werden derzeit in `SettingsScreen` gesammelt und müssen an die passenden neuen Composables weitergegeben werden:

| Zustand / Zugriff | Verwendung |
|---|---|
| `config: EngineConfig` | Backend-, Vulkan-/GDN- und Inferenz-Einstellungen |
| `systemPrompt: String` | System-Prompt-Eingabe |
| `currentModel: ModelInfo?` | Aktives Backend sowie Aktivierung der Diagnoseläufe |
| `isBenchmarking: Boolean` | Diagnose-Button-Zustände und Beschriftungen |
| `benchmarkReport: String?` | Testergebnis |
| `isGenerating: Boolean` | Aktivierungsbedingung der Diagnoseläufe |
| `isImporting: Boolean` | Aktivierungsbedingung der Diagnoseläufe |
| `viewModel` | Vorhandene Aufrufe wie `updateSystemPrompt`, `updateConfig`, `runBackendAcceptanceTest` und `runVulkanQ4kDecodePathSweep` |

Die einfachste verhaltensneutrale Übergabe ist, die bereits vorhandene `EngineViewModel`-Instanz und exakt die aktuell verwendeten State-Werte als Parameter zu übergeben. Die Screen-weite `collectAsState()`-Sammlung bleibt im Einstiegspunkt. Es wird weder ein neues ViewModel erzeugt noch ein neuer State eingeführt.

## 4. Imports und Helper

- Es existieren keine privaten Helper-Funktionen oder Helper-Composables in `SettingsScreen.kt`.
- Jeder Abschnitt erhält nur die Imports, die sein unverändert übernommener Code benötigt.
- Engine-Typen wie `EngineConfig`, `ModelInfo`, `BackendPreference`, `GdnPolicy`, `ReloadAfterAbort` und `VulkanMatvecWorkgroup` werden ausschließlich in den Dateien importiert, die sie tatsächlich verwenden.
- `LaunchedEffect(Unit)` mit `EngineDiagnostics.info("ui", "SETTINGS_SCREEN_VISIBLE")` bleibt in `SettingsScreen`; es ist Screen-Lifecycle-Verhalten und keine sichtbare Sektion.
- Der öffentliche Einstiegspunkt bleibt exakt:
  `fun SettingsScreen(viewModel: EngineViewModel, modifier: Modifier = Modifier)`.
- `SettingsScreen.kt` behält die State-Sammlung, den Diagnose-`LaunchedEffect`, das vorhandene `LazyColumn`-Scaffold und die Aufrufe in derselben Reihenfolge.

## 5. Geplanter Extraktionsablauf nach Freigabe

1. Für jede Sektion eine Datei unter `app/src/main/java/com/example/ui/settings/sections/` anlegen (Package `com.example.ui.settings.sections`).
2. Den jeweiligen bestehenden UI-Inhalt ohne Text-, Modifier-, TestTag-, Reihenfolge- oder Callback-Änderung übernehmen. Keine Logik neu schreiben.
3. In der bestehenden `LazyColumn` an derselben Position innerhalb desselben `item`-Blocks die neue Composable aufrufen. Für den Backend-Sonderfall die bestehende Card-/Column-Hierarchie beibehalten und nur den inneren Vulkan-/GDN-Teil als Composable kapseln.
4. Nach jeder Sektion `assembleDebug` in GitHub Actions ausführen und nur nach erfolgreichem Build committen.
5. Abschließend `SettingsScreen.kt` auf Scaffold, State-Sammlung und Aufrufe reduzieren; Zielgröße < 5 KB. Jede neue Sektion-/Helper-Datei muss < 8 KB bleiben.
6. `assembleDebug` und Tests in CI ausführen. Der Screenshot-/UI-Vergleich bzw. manuelle Smoke-Test bleibt ein separater Nachweis; ein CI-Build allein belegt keine identische Darstellung auf dem Gerät.

## 6. Verbindliche Prüfpunkte

- [ ] Öffentliche Signatur von `SettingsScreen` unverändert.
- [ ] Vorhandene UI-Ausdrücke, Strings, Modifier, TestTags, Callbacks und Reihenfolge 1:1 erhalten.
- [ ] Kein neues ViewModel, kein neuer Zustand, keine neue Logik und keine Dependency.
- [ ] Bestehende Anzahl und Reihenfolge der LazyColumn-Einträge erhalten.
- [ ] Backend-Einstellungen bleiben in einer Card.
- [ ] Jede neue Sektion-/Helper-Datei < 8 KB.
- [ ] `SettingsScreen.kt` < 5 KB.
- [ ] CI-Build und Tests erfolgreich; UI-Vergleich separat dokumentiert.

## 7. Freigabepunkt

**SCHRITT 1 – ANALYSE ABGESCHLOSSEN.** Es wurden keine Kotlin-Dateien verändert.

**Freigabe erforderlich:** Soll die beschriebene mechanische Extraktion der bisher inline definierten `LazyColumn`-Inhalte in neue `@Composable`-Funktionen erfolgen? Die Inhalte werden 1:1 übernommen; nur die notwendige Funktionshülle und die Aufrufstellen werden ergänzt. Ohne diese Zustimmung erfolgt keine Codeänderung.


## 8. Freigabe und laufende Umsetzung

**Freigabe:** Der Nutzer hat die mechanische Extraktion am 14.11.2026 ausdrücklich freigegeben. Es dürfen die Inline-UI-Blöcke als Composable-Funktionen gekapselt werden; ihr innerer UI-Code bleibt unverändert.

### Extraktionsstatus

| Sektion | Status | Code-Commit | Build |
|---|---|---|---|
| SystemPromptSection | Extrahiert | `0600cdd903170d7e439c8621095868afc045e9d3` (Korrekturcommit folgt: fehlender Compose-testTag-Import ergänzt) | APK + Unit Tests laufen (Runs `43557624968`, `43557624991`) |
| BackendSettingsSection | Offen | – | – |
| VulkanTuningSection | Offen | – | – |
| InferenceSettingsSection | Offen | – | – |
| BackendAcceptanceTestSection | Offen | – | – |
| VulkanQ4kDiagnosticsSection | Offen | – | – |
| BenchmarkResultSection | Offen | – | – |

**Erste Extraktion:** Die bestehende System-Prompt-Card wurde in `SystemPromptSection.kt` verschoben. Die ursprünglichen Card-Inhalte (Text, Modifier, TestTag, Callbacks und Parameter) werden unverändert übernommen. Der vorhandene LazyColumn-Eintrag bleibt an derselben Position und ruft die neue Composable auf. `SettingsScreen`-Signatur und Screen-weite State-Sammlung bleiben unverändert.

**Build-Gate:** Der Commit wird durch die vorhandenen GitHub-Actions geprüft. Weitere Sektionen werden erst nach erfolgreicher Prüfung des jeweiligen Code-Commits extrahiert. Bei einem CI-Fehler wird zuerst die Ursache behoben und erneut geprüft.


### Strukturdiagramm – Pflegepflicht

Das gespeicherte Mermaid-Diagramm ist Teil der Refactoring-Dokumentation. Es wird nach **jedem** Sektion-Commit und jeder strukturellen Abweichung aktualisiert. Die `documentID` bleibt stabil; es wird das bestehende Diagramm aktualisiert, kein neues angelegt. Statusfarben und Dateigrößen müssen dem tatsächlich verifizierten Repository-Stand entsprechen.
