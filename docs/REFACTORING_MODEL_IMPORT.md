# ModelImportScreen – Extraktionsanalyse

**Status:** Schritt 1 abgeschlossen – Analyse und Cluster-Vorschlag; Extraktion ausdrücklich noch nicht begonnen.  
**Analysierter Branch/Stand:** `main` · `af594b8fe80147ce4a544555a9af683ebc20782b`  
**Quelldatei:** `app/src/main/java/com/example/ui/ModelImportScreen.kt` · 21.454 Zeichen · 454 Zeilen

## 1. Top-level Composables und vorhandene Typen

| Deklaration | Ort / Sichtbarkeit | Verwendung und Abhängigkeiten | Behandlung |
|---|---|---|---|
| `ModelImportScreen(viewModel: EngineViewModel, modifier: Modifier = Modifier)` | `ui/ModelImportScreen.kt`, public `@Composable` | Aufruf aus `MainScreen` für `AppTab.IMPORT`; sammelt sechs ViewModel-StateFlows und registriert drei Activity-Result-Launcher | Öffentliche Signatur unverändert in der Hauptdatei belassen |
| `ModelLoadProgressIndicator(progress: Int?, modifier: Modifier = Modifier)` | `ui/ModelLoadProgressIndicator.kt`, public `@Composable` | Wird sowohl von `ModelImportScreen` als auch von `ChatScreen` verwendet | Bestehende gemeinsame Komponente weiterverwenden; nicht in einen Import-Cluster verschieben |
| `ImportedModelEntry(file, name, sizeBytes)` | `ui/ModelManagementViewModel.kt`, Data Class | Listeneintrag mit Datei, Anzeigename und Größe; Screen liest `file`, `name` und `sizeMb` | Typ und ViewModel unverändert lassen |

**Preview-Bestand:** In `ModelImportScreen.kt` gibt es keine `@Preview`-Funktion (0 Treffer). Es ist daher keine Preview zu verschieben. Die öffentliche Screen-Signatur und alle bestehenden Aufrufstellen bleiben erhalten.

## 2. State- und Aktionsabhängigkeiten

Diese sechs Werte werden aktuell in `ModelImportScreen` gesammelt und sollen dort gesammelt bleiben:

| State | Verwendung |
|---|---|
| `currentModel` | aktive Modellkarte; Vergleich mit Listeneinträgen |
| `importedModelEntries` | Anzahl, Leerzustand und Modellliste |
| `isImporting` | Import-Schaltflächen und Ladeaktionen aktivieren/deaktivieren |
| `modelLoadProgress` | gemeinsame Fortschrittsanzeige |
| `errorMessage` | Fehlerbanner bedingt anzeigen und Text darstellen |
| `memoryOverrideAvailable` | Aktion „Trotzdem laden“ bedingt anzeigen |

Die drei Activity-Result-Launcher bleiben ebenfalls in der Hauptdatei:
- `OpenDocument` → `viewModel.importModelFromSaf(uri)`
- `OpenMultipleDocuments` → `viewModel.importModelsFromSaf(uris)`
- `OpenDocumentTree` → `viewModel.importModelsFromTree(treeUri)`

Weitere bestehende ViewModel-Aktionen, die als Callbacks übergeben werden:
`loadModelDespiteMemoryWarning()`, `dismissError()`, `unloadModel()`, `refreshModelFiles()` und `loadModelFile(entry.file)`.

## 3. UI-Reihenfolge und Cluster-Vorschlag

Die vorhandene `LazyColumn` enthält in dieser Reihenfolge: optionales Fehlerbanner → aktive Modellkarte → SAF-Importkarte → Listen-Header → entweder Leerzustand oder Modellkarten. Diese Reihenfolge, alle `item`-/`items`-Grenzen und die vorhandene Card-/Column-Hierarchie müssen exakt erhalten bleiben.

| Cluster / Zieldatei | Aktueller Quellblock | Inhalt und Abhängigkeiten | Geplante Extraktion |
|---|---|---|---|
| `ImportErrorBanner.kt` | Error Banner, ca. Zeile 88–133 | Fehler-Card; `errorMessage`, `memoryOverrideAvailable`, `isImporting`; Callbacks für Override und Dismiss | Eigenständiges internes Composable; bleibt als optionales erstes Lazy-Item |
| `ActiveModelSection.kt` | Active Loaded Model Card, ca. Zeile 134–235 | `ElevatedCard`, Kopfzeile, Entladen-Aktion; `currentModel` und `onUnload` | Card und bestehende äußere Column-Hierarchie verschieben |
| `ActiveModelDetails.kt` | Detailbereich innerhalb der aktiven Modellkarte | Modellname, Dateigröße, Parameter, trainierter Kontext, Architektur, aktives Backend und optionaler SHA-256-Wert | Detail-UI als direkt emittierendes Composable innerhalb der vorhandenen Column; kein zusätzlicher Layout-Container |
| `FilePickerSection.kt` | SAF-Importkarte, ca. Zeile 236–337 | Erklärung zur Originaldatei, Beschreibung des direkten SAF-Zugriffs und bedingte Fortschrittsanzeige; `isImporting`, `modelLoadProgress` | Card und Text-/Progress-Bereich verschieben; `ModelLoadProgressIndicator` unverändert aufrufen |
| `FilePickerActions.kt` | Drei SAF-Auswahlaktionen innerhalb der SAF-Karte | Einzeldatei-, Mehrfachdatei- und Ordnerauswahl; Status `isImporting`; drei Launcher-Callbacks | Button-Sequenz als Composable ohne zusätzlichen Wrapper verschieben und an derselben Stelle aufrufen |
| `ImportedModelsSection.kt` | Listen-Header und Verzweigung ab ca. Zeile 338 | Header mit Anzahl/Refresh sowie Leerzustand oder `items(modelEntries)` | `LazyListScope`-Erweiterung, die dieselben `item`-/`items`-Einträge in derselben Reihenfolge registriert |
| `ImportedModelCard.kt` | Karteninhalt innerhalb von `items(modelEntries)` | `ImportedModelEntry`, Vergleich des geladenen Pfads, `isImporting`, Laden-/Entladen-Callbacks | Einzelne Modellkarte verschieben; Pfadvergleich und Callback-Ausführung unverändert übernehmen |

Die neuen Cluster-Composables bzw. die LazyListScope-Erweiterung können `internal` sein; keine neue öffentliche Screen-API ist erforderlich.

## 4. Bewusste Nicht-Cluster

- **Keine Import-Validierungssektion:** In dieser Screen-Datei gibt es keinen eigenständigen Validierungs-Composable. Validierung und Importablauf bleiben in den bestehenden ViewModel-/Engine-Pfaden.
- **Keine Fehlerdialoge:** Der Fehler wird aktuell als inline `Card`-Banner in der Liste angezeigt, nicht als `AlertDialog`. Deshalb heißt der Cluster `ImportErrorBanner`.
- **Keine neue Progress-Komponente:** `ModelLoadProgressIndicator` existiert bereits als gemeinsame Komponente für Chat und Import. Der bestehende bedingte Aufruf bleibt innerhalb der SAF-Card an derselben Position.
- **Kein eigener Scaffold:** `ModelImportScreen` enthält aktuell eine `LazyColumn`, keinen Scaffold. Die Screen-Fassade behält die `LazyColumn` sowie State-Sammlung und Launcher; die einzelnen Listeneinträge werden zu Aufrufen der extrahierten Komponenten.

## 5. Erhaltungs- und Größenkriterien für Schritt 2

1. `ModelImportScreen(viewModel, modifier)` bleibt exakt gleich signiert.
2. State-Sammlung und drei Launcher bleiben in der Hauptdatei; UI-Abschnitte erhalten Werte und Callbacks statt eigener neuer ViewModel-/Launcher-Logik.
3. Alle vorhandenen Texte, Modifier, Test-Tags, Icons, Farben, Abstände, Card-/Column-Verschachtelungen und Lazy-List-Grenzen bleiben erhalten.
4. Geplante Dateien werden nach der Extraktion auf **unter 6 KB** geprüft; `ModelImportScreen.kt` auf **unter 5 KB**. Die Größen sind nach dem tatsächlichen Commit anhand der Dateibytes zu verifizieren.
5. Nach jedem Cluster: Build und fokussierte Tests, anschließend separater Commit. Abschließend Build/Tests sowie manueller Import-Flow (Erfolg, Abbruch, Fehler); keine Verhaltens- oder Performancebehauptung ohne entsprechende Prüfung.

**Aktueller Umfang:** Nur diese Analyse-Dokumentation wird hinzugefügt. `ModelImportScreen.kt` und alle Produktions-/Testdateien bleiben unverändert. Auf Freigabe warten, bevor Schritt 2 beginnt.
