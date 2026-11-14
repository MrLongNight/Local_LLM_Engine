# ModelImportScreen — mechanische Extraktionsanalyse

**Status:** SCHRITT 1 abgeschlossen — **STOP / Freigabe ausstehend**. Keine Kotlin-Quelldatei für diesen Task geändert.  
**Repository:** `MrLongNight/Local_LLM_Engine` · **Branch:** `main`  
**Analyse-Code-HEAD:** `4492dc3ec7a281cd64640f0186482f98fc07fca8`  
**Datei:** `app/src/main/java/com/example/ui/ModelImportScreen.kt`  
**Blob SHA:** `4faa04838b60676a2b0f86256d1dd2f05d644365`  
**Umfang:** 21.454 Zeichen, 454 Zeilen.

## 1. Top-Level-Deklarationen und Ist-Befund

Die Datei enthält genau eine Top-Level-Composable:

| Deklaration | Zeilen | Sichtbarkeit / Vertrag |
|---|---:|---|
| `ModelImportScreen(viewModel: EngineViewModel, modifier: Modifier = Modifier)` | 51–453 | Öffentlicher Screen-Einstiegspunkt; Signatur unverändert lassen |

Keine `@Preview`-Funktionen, keine weiteren Top-Level-Composables und keine lokalen Helper-Funktionen vorhanden. Die UI-Bereiche sind Inline-Blöcke innerhalb derselben `LazyColumn`. Um die gewünschte Aufteilung zu erreichen, müssen die bestehenden Inline-Bereiche mechanisch in neue Composables gekapselt werden. Es ist keine vorhandene Sektion-Composable vorhanden, die einfach nur verschoben werden könnte.

## 2. Vorgeschlagene Cluster / Dateien

Zeilenbereiche beziehen sich auf den oben dokumentierten Blob-Snapshot. Große Card-Hüllen bleiben bei Bedarf im Screen; die kleineren UI-Blöcke werden als Kinder in derselben Compose-Hierarchie aufgerufen. Jede neue Datei muss nach Imports und Funktionshülle unter 6 KB gemessen werden.

| Vorgeschlagene Composable | Quellzeilen | Ziel-Datei | Anmerkung |
|---|---:|---|---|
| `ErrorBannerSection` | 88–132 | `ui/modelimport/ErrorBannerSection.kt` | Fehlerbanner mit Speicher-Override; **kein Dialog** |
| `ActiveModelHeader` | 146–174 | `ui/modelimport/ActiveModelHeader.kt` | Kopfzeile / Entladen-Button innerhalb bestehender Card |
| `ActiveModelDetails` | 175–231 | `ui/modelimport/ActiveModelDetails.kt` | Modell-Metadaten, Hash und Leerzustand |
| `SafImportDescription` | 240–265 | `ui/modelimport/SafImportDescription.kt` | Erklärtext innerhalb bestehender SAF-Card |
| `SafImportActions` | 267–336 | `ui/modelimport/SafImportActions.kt` | Bestehender Fortschrittsindikator + drei Picker-Aktionen |
| `ImportedModelsHeader` | 338–354 | `ui/modelimport/ImportedModelsHeader.kt` | LazyColumn-Header mit Refresh |
| `EmptyModelLibraryState` | 356–385 | `ui/modelimport/EmptyModelLibraryState.kt` | Bestehende Empty-State-Card |
| `ImportedModelIdentity` | 397–426 | `ui/modelimport/ImportedModelIdentity.kt` | Icon, Dateiname, Dateigröße |
| `ImportedModelActions` | 427–447 | `ui/modelimport/ImportedModelActions.kt` | Laden-/Entladen-Schaltfläche |

Die bestehende Card-/Column-Hierarchie und der `items(modelEntries)`-Builder bleiben im Screen, falls eine Verlagerung des LazyListScope-Builders die LazyColumn-Struktur verändern würde. Die Identitäts- und Aktions-Composables werden an denselben Positionen der bestehenden Row aufgerufen.

## 3. Screen-State und Launcher-Abhängigkeiten

| State / Ressource | Aktueller Owner | Regel für Extraktion |
|---|---|---|
| `currentModel`, `modelEntries`, `isImporting`, `modelLoadProgress`, `errorMessage`, `memoryOverrideAvailable` | `ModelImportScreen` via `collectAsState()` | State Collection bleibt oben im Screen; Werte als Parameter weiterreichen |
| `filePickerLauncher` | Screen: `OpenDocument` | Im Screen behalten; Callback an Picker-UI reichen |
| `multiFilePickerLauncher` | Screen: `OpenMultipleDocuments` | Im Screen behalten; Callback an Picker-UI reichen |
| `folderPickerLauncher` | Screen: `OpenDocumentTree` | Im Screen behalten; Callback an Picker-UI reichen |
| `EngineViewModel` | Bestehende gemeinsame Instanz | Methoden und Callback-Reihenfolge unverändert: Import, Dismiss, Memory Override, Unload, Load, Refresh |

Activity-Result-Launcher sind Lifecycle-gebunden. Sie dürfen nicht in Unter-Composables dupliziert oder neu erzeugt werden. Sichtbare Controls erhalten höchstens Callbacks; Modifier, enabled-Bedingungen, Beschriftungen und Test-Tags bleiben gleich.

## 4. LazyColumn-, Preview- und UI-Risiken

- Importfortschritt verwendet bereits `ModelLoadProgressIndicator.kt`; diese Composable wiederverwenden, nicht neu implementieren.
- Die Modellliste nutzt `item { ... }` und `items(modelEntries) { ... }`. Keine Umwandlung in eine verschachtelte `Column` oder eager Liste.
- Die Datei besitzt keine `@Preview`; es gibt daher keine Preview-Funktion, deren Sichtbarkeit oder Paketbezug zu erhalten wäre.
- Es existiert ein Fehlerbanner, aber kein Fehlerdialog. Keine `ErrorDialogs`-Datei erfinden.
- Es gibt keine eigenständige Validierungs-Composable. GGUF-/SAF-Validierung liegt außerhalb dieses Screens; keine ViewModel-/Business-Logik verschieben.
- Die Präsentationsabschnitte rund um Active-Model- und Modellzeilen werden bei Bedarf geteilt, damit <6 KB pro Datei erreichbar ist; Card-/Row-Wrapper bleiben dafür am identischen Compose-Punkt.
- Zielgröße `ModelImportScreen.kt < 5 KB` muss nach Entfernung der Präsentationsblöcke tatsächlich gemessen werden. State Collection, Launcher und LazyColumn-Container dürfen nicht zum Erzwingen des Limits entfernt werden.

## 5. Zielverantwortung von ModelImportScreen.kt

Beibehalten:
- öffentliche Signatur `ModelImportScreen(viewModel, modifier)`;
- alle sechs bestehenden State-Collections;
- die drei Activity Result Launcher;
- bestehende `LazyColumn`, Padding, Test-Tag, Spacing, Reihenfolge und Lazy-Builder;
- nur die notwendigen Calls zu extrahierten UI-Composables.

## 6. Verifikations- und Freigabe-Gates

- [ ] Explizite Nutzerfreigabe für das mechanische Kapseln der Inline-Blöcke.
- [ ] Eine Sektion pro Code-Commit; nach jedem Commit exakte Unit- und APK-/Emulator-Actions abwarten.
- [ ] Vergleich jedes verschobenen Körpers mit Baseline nach Entfernen ausschließlich der Funktionshülle/Einrückung.
- [ ] Public `ModelImportScreen`-Signatur identisch.
- [ ] LazyColumn-Reihenfolge, Item-Typen, Keys, UI-Hierarchie, State Ownership, Callback-Reihenfolge, Modifier, Test-Tags und Accessibility Labels identisch.
- [ ] Jede neue Datei < 6 KB; finale Hauptdatei < 5 KB.
- [ ] Unit Tests + APK Build + Emulator-Instrumentation erfolgreich.
- [ ] Nutzer führt manuellen Import-Smoke-Test aus: Erfolg, Abbruch, Fehler.

**STOP:** Analyse only. Kein ModelImportScreen-Code geändert.
