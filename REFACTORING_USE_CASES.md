# Use-Case / Repository Extraktion — Kandidatenanalyse

**Status:** Analyse abgeschlossen — **BLOCKED / Freigabe ausstehend**. Keine Logik verschoben und keine neue Klasse angelegt.  
**Repository:** `MrLongNight/Local_LLM_Engine` · **Branch:** `main`  
**Code-HEAD:** `4492dc3ec7a281cd64640f0186482f98fc07fca8`  
**Ziel:** Nur vorhandene Geschäftslogik-Blöcke 1:1 verschieben; keine neuen Regeln, Threading- oder Coroutine-Änderungen.

## 1. Prüfmaßstab

Ein Kandidat darf keine ViewModel-UI-State-Flows direkt manipulieren und sollte einen klaren Daten-/Domain-Vertrag besitzen. Funktionen, die `viewModelScope`, UI-State, ein laufendes Engine-Objekt oder eine konkrete Screen-Meldung besitzen, sind keine reinen Use Cases, solange diese Abhängigkeiten nicht ausdrücklich Bestandteil des freigegebenen Vertrags sind.

## 2. Kandidaten

| Aktueller Owner / Funktion | Befund | Empfehlung |
|---|---|---|
| `EngineViewModel.configurationTestSafetyBlock()` | Liest `ActivityManager.MemoryInfo` und Thermalstatus über Android `Application`; liefert optionalen Blockiertext zurück; verändert selbst keinen UI-State | **Einziger substantieller Kandidat** für eine Android-gebundene `InferenceEnvironmentSafetyUseCase`, falls die Policy künftig außerhalb des ViewModels wiederverwendet wird. Benötigt `Application` als Dependency/Parameter |
| `EngineViewModel.backendFamily(value)` | Reine String-Klassifikation CPU/Vulkan/Unknown; sehr klein und eng mit Benchmark-Report-Formatierung gekoppelt | Als privaten Helper belassen, keine eigene Use-Case-Klasse |
| `ModelManagementViewModel.requiresMemoryGuardOverride(error)` | Reine Cause-Chain-Prüfung eines Memory-Guard-Marker-Präfixes | Als privaten Helper belassen; zu klein für eigenständige Geschäftsoperation |
| `ModelManagementViewModel.modelSourceKind(source)` | Reine `ModelSource`-Typklassifikation für Diagnose-Label | Als lokalen privaten Helper belassen |
| `LastModelStore` | Persistenz-/Restore-Logik bereits in separatem Store-Objekt organisiert | Nicht in ein paralleles Use Case duplizieren |
| `SafGgufFolderScanner.scan(...)` | SAF-Dateibaum-Scan bereits separat gekapselt | Keine zweite Abstraktion |
| `BackendBenchmarkPlan`, `CorrectnessGatePlan`, `InferenceConfigSmokePlan`, `VulkanLayerSweepPlan` | Bestehende zustandsarme Planungs-/Auswertungsobjekte mit Tests | Bereits passende Trennung; keine Wrapper-Use-Cases |
| Importmethoden im `EngineViewModel` | Setzen `_isImporting`, `_statusMessage`, `_errorMessage`, verwenden `viewModelScope`, `refreshModelFiles()`, SAF Grants und `ModelLibrary` | **Nicht rein**; keine Extraktion als reine Use Cases ausgeben |
| Benchmark-/Sweep-/Smoke-Methoden und `collect*Sample`-Helfer | Orchestrieren Engine-Lade-/Inference-Vorgänge und schreiben Benchmark-/UI-Report-State | **Nicht rein**; im Benchmark-/Diagnostics-Owner belassen |
| `ChatViewModel.sendMessage()` / `cancelGeneration()` | Ändern Conversation-/Streaming-State und steuern Engine-Requests | Nicht in einen Use Case verschieben |

## 3. Aktuelle Schlussfolgerung

Der aktuelle Bestand liefert **keinen großen, klar abgegrenzten Use-Case-Block**, der ohne Anpassung seiner Abhängigkeiten verschoben werden könnte. Die meisten datenorientierten Operationen sind bereits ausgelagert (Store/Scanner/Plan-Objekte) oder verwalten Coroutine-/UI-/Engine-Zustand.

Nur `configurationTestSafetyBlock()` ist ein sinnvoller Kandidat, falls die Sicherheits-/Thermalprüfung auch an anderer Stelle wiederverwendet wird. Ohne konkreten Wiederverwendungsbedarf ist eine eigene Klasse unnötige Abstraktion.

## 4. Falls der Kandidat freigegeben wird

- Klasse: `InferenceEnvironmentSafetyUseCase`.
- Signatur-Vorschlag: `operator fun invoke(application: Application): String?` (nicht suspend, da der aktuelle Block synchron arbeitet).
- Existierenden Methoden-Body exakt übernehmen; höchstens `getApplication<Application>()` auf den expliziten Parameter umstellen, wenn diese Dependency-Übergabe freigegeben ist.
- ViewModel bleibt am selben Aufrufpunkt und nutzt denselben Rückgabewert, dieselbe Reihenfolge und Threading-Semantik.
- Keine neuen Coroutines, Dispatchers oder Flows. `suspend` wäre für diesen synchronen Block keine 1:1-Abbildung.

## 5. Freigabe-Gates

- [ ] Nutzer entscheidet, ob nur `configurationTestSafetyBlock()` extrahiert werden soll oder R5 mangels sinnvoller Kandidaten entfällt.
- [ ] Vor Codeänderung Abhängigkeit und Signatur freigeben.
- [ ] Keine anderen Methoden ohne neue Analyse mitverschieben.
- [ ] Originaler Body comparebar und unverändert.
- [ ] Unit Tests + APK Build grün; keine Threading-/Coroutine-Abweichung.

**STOP:** Keine Use-Case-Implementierung autorisiert.
