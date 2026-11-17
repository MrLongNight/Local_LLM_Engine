# Code- und Aufgaben-Audit: Übernahme der Engine in myDocAnizer-Mobile

**Prüfdatum:** 17.11.2026  
**Engine-Prototyp:** MrLongNight/Local_LLM_Engine, main, 5180ce1abbb0f8d5dfe3fe490d5eb80f207936c9  
**Zielprojekt:** MrLongNight/myDocAnizer_Mobile, main, 772caa5b6df2921fab1cdbba5f2cca6b487d4f3d

## 1. Korrigiertes Projektziel

Local_LLM_Engine ist ein zusätzlicher Entwicklungsprototyp für die LLM-Engine von myDocAnizer_Mobile. Das Ziel ist nicht mehr, den Prototyp als eigenständige App zu vervollständigen. Die bisherige LLM-Laufzeit in myDocAnizer_Mobile soll durch die im Prototyp entwickelte Engine-Logik ersetzt werden.

- Übernommen wird ausschließlich das wiederverwendbare Modul :llm-engine einschließlich Kotlin-API, AIDL-/Binder-Prozessgrenze, JNI, CMake, Backend-Auswahl, Modell-Lifecycle, Timeout-/Abbruchlogik, Recovery und Ressourcenverwaltung.
- Nicht übernommen wird die Demo-App :app des Prototyps. Ihre Compose-Oberflächen, Demo-ViewModels, Benchmark-Screens, Demo-Modellbibliothek und Demo-Speicherung gehören nicht in die Haupt-App.
- In myDocAnizer_Mobile bleiben OCR, DMS-Prompts und Fachregeln, JSON-Parsing und Grounding, Room/Dokumente, Modellkatalog, Download- und Auswahloberfläche sowie App-Workflows. Diese Funktionen werden an die neue Engine angebunden, nicht in die Bibliothek verschoben.
- Die bestehende alte Inferenzimplementierung wird erst entfernt, wenn alle Aufrufer nachweislich auf der neuen Engine laufen.

## 2. Technischer Vergleich und erwarteter App-Impact

| Bereich | myDocAnizer_Mobile aktuell | Prototyp :llm-engine | Erwarteter Impact |
|---|---|---|---|
| Backend | JNI übergibt gpuLayers = 0; C++ setzt n_gpu_layers = 0. Die alte Laufzeit ist CPU-only. | AUTO, CPU und explizites VULKAN; tatsächliche Backend-/Layer-Daten verfügbar. | **Sehr hoch:** echter Vulkan-Pfad wird in der Haupt-App verfügbar; Offload muss am Gerät bestätigt werden. |
| Laufzeitparameter | Eng gekoppelte Festwerte; nativer Context auf 2048 begrenzt. | EngineConfig für Context, Batch/Microbatch, Threads, GPU-Layer, Flash-Attention und weitere Native-Policies. | **Hoch:** modell- und ressourcengerechte Parameter statt festem App-Nativepfad. |
| Prozessgrenze | Alte Inferenz-JNI im App-Prozess. | Privater :inference-Service per AIDL in eigenem Prozess, aber gleicher App-UID. | **Hoch:** native Abstürze und Speicherfreigabe sind vom UI-Prozess getrennt; keine UID-Sandbox und keine Garantie gegen OS-Kills. |
| Abbruch/Recovery | JNI-Abbruchsignal/Timeout vorhanden, aber innerhalb der alten Laufzeit. | Native Cancellation, getrennte Fristen, Abort-Eskalation, Reconnect und Modell-Recovery. | **Hoch:** direkter Ersatz für kritische Alt-Engine-Lifecycle-Logik. |
| Modellquelle | importModelFromUri() kopiert aktuell in modelsDir. | ModelSource.ContentUri öffnet SAF-FD direkt und übergibt ihn an :inference; FD wird geprüft. | **Hoch:** Laden von der ursprünglichen Quelle ohne Kopie, sofern Provider einen regulären seekbaren mmap-fähigen FD liefert. |
| Speicher | Eigene Modellablage und alte feste Native-Konfiguration. | ModelMemoryGuard, dynamischer Context, Prozess-Ressourcenmonitor, Entladen bei Speicherdruck. | **Hoch:** besser kontrollierbarer RAM-Bedarf; reale Spitzenwerte am Zielgerät bleiben nötig. |
| Diagnostik | Teilweise synthetische Hardware-/TPS-Schätzwerte. | Backend-, Laufzeit-, Token-, Speicher- und Prozessdiagnostik. | **Mittel/Hoch:** echte Laufzeitdaten statt Schätzungen; Demo-Diagnoseoberfläche wird nicht übernommen. |

### Bestehende LLM-Dokumentation in der Ziel-App

Die Haupt-App enthält mit `docs/LLM_NATIVE_PRODUCTION_PLAN.md` weiterhin eine Datei mit der Überschrift **„Lokale LLM-Funktionen – Live-Todo“**, Stand 27.10.2026. Sie dokumentiert wichtige App-Fachanforderungen, bildet aber eine zweite Arbeitsliste neben der neuen Engine-Migrationsliste und enthält teils ältere Status-/Nachweisangaben. Ihr Inhalt bestätigt unter anderem die festen Branchenzweige ohne Modellaufruf in `generateRulesFromPrompt()`, die direkte Heuristik in `queryDocumentAssistant()`, den gemischten Follow-up-/Fallback-Pfad, deaktivierte DMS-Grammatiken und geschätzte Hardware-TPS. Diese relevanten Fachanforderungen müssen in der Migration erhalten bleiben; veraltete Laufzeit- und Statusaussagen sind abzugleichen. Nicht zur Engine gehörende Scanner-/DMS-Aufgaben sollen nicht in die Engine-Liste übernommen werden. Die Zusammenführung der drei zentralen LLM-Dokumente der Haupt-App ist deshalb als eigener Agent-Punkt im neuen Master-TODO erfasst.

### Build- und Integrationsbefund

- Beide Projekte verwenden aktuell AGP 9.1.1, Kotlin 2.2.10, NDK 27.2.12479018 und CMake 3.22.1. Ein echter Consumer-Build fehlt noch.
- myDocAnizer_Mobile bindet derzeit nur :app ein; :llm-engine ist nicht integriert.
- Die Haupt-App pinnt llama.cpp auf 2da668617612d2df773f966e3b0ee22dc2beef7b (05.07.2026); das Engine-Modul auf a4d4f2fd4ec85ec6a0a9ece4870416c1b6cc24f5 (09.11.2026). Es sind unterschiedliche Upstream-Stände. Nicht beide nativen Laufzeiten parallel einbauen und den Pin nicht ohne Overlay-/Modelltests vereinheitlichen.
- Das Engine-Modul baut arm64-v8a und x86_64; die Haupt-App konfiguriert zusätzlich 32-Bit-ABIs. Diese ABI-Differenz muss explizit entschieden werden.
- InferenceClient nutzt für ContentUri bereits den direkten SAF-FD-Pfad (copy=false). Client-FD wird nach Binder-Übergabe geschlossen; der Service hält seine eigene FD-Kopie für den nativen Ladevorgang. Nicht jeder Provider liefert einen seekbaren regulären FD; für Pipes/Proxy-/Teil-Dateien braucht es einen ausgewiesenen Alternativpfad. Android-API-Referenz: https://developer.android.com/reference/android/content/ContentResolver#openFileDescriptor(android.net.Uri,java.lang.String).
- Android kann auch den Service-Prozess beenden. Recovery muss mit echten Prozessabbrüchen und anschließender Folgeinferenz geprüft werden.

## 3. Bestätigte Altlasten und irreführende Funktionspfade

| Fundstelle | Befund | Maßnahme |
|---|---|---|
| LlamaCppInferenceEngine.checkGpuAccelerationSupport() | Gibt konstant false zurück; Kommentar verweist auf einen CPU/NEON-only-AAR, während die App llama.cpp selbst über CMake baut. | Veralteten Pfad nach Aufruferprüfung entfernen; tatsächlichen Status aus BackendInfo beziehen. |
| LlamaCppInferenceEngine.executeInference() | Übergibt fest gpuLayers = 0; C++ erzwingt n_gpu_layers = 0. | Alte Inferenzlaufzeit durch :llm-engine ersetzen, nicht parallel reparieren. |
| LlamaCppInferenceEngine | Mischt Native-Wrapper/Lifecycle mit DMS-Prompts, OCR-Filtern, JSON-Parser, Grounding und Heuristik-Fallback. | Engine-Wrapper austauschen; DMS-Teile erhalten und in der App-Fachschicht anbinden. Datei nicht blind löschen. |
| LlmService.generateRulesFromPrompt() | Feste Branchen-if/when-Regeln, aber Ergebnisse werden mit isAiGenerated = true markiert; in dieser Funktion wird kein Modell aufgerufen. | Echte strukturierte Engine-Ausgabe mit deterministischer Validierung ergänzen oder ausdrücklich als Vorlagenmodus kennzeichnen. |
| LlmService.queryDocumentAssistant() | Ruft LlmToolExecutor.executeDocumentAssistant() direkt auf; Keyword-Heuristik statt Modellinferenz. | Bei gewünschtem KI-Chat an Engine anbinden; deterministische Datenabfragen als Tools behalten. |
| LlmService.queryDocumentAssistantWithFollowUps() | Modellinferenz bei vorhandenem Modell, sonst deterministischer LlmToolExecutor-Fallback. | Beide Pfade erhalten, Herkunft und Fallbackstatus eindeutig ausweisen. |
| LlmHardwareDetector.detectVulkanGpu() | Prüft nur FEATURE_VULKAN_HARDWARE_LEVEL; belegt keine erfolgreiche Backend-Initialisierung oder Layer-Offload. | Durch BackendInfo/Native-Preflight und Laufzeitdaten ersetzen. |
| LlmHardwareDetector.runCpuMicroBenchmark()/estimateTps() | Synthetischer CPU-Score bzw. Modell-/Geräteformel; kein GGUF-Benchmark. | Als Schätzung kennzeichnen oder später durch echte Messungen ersetzen. |

### Nicht als Dummy entfernen

- executeFallbackInference() und executeFallbackToolInference() sind bewusste deterministische Offline-/Fehler-Fallbacks. Sie können bleiben, sofern RULE_FALLBACK korrekt angezeigt wird.
- GBNF-Grammatik ist deaktiviert, weil die DMS-Grammatiken am Zielgerät vom Parser abgelehnt wurden. Nicht ohne neuen Parser-/Gerätenachweis aktivieren oder löschen.
- llama_android_stub.cpp liefert für 32-Bit-ABIs einen expliziten Fehler und ist ein bewusster ABI-Fallback, kein versehentlicher Dummy. Seine Notwendigkeit hängt von der ABI-Entscheidung ab.
- Im geprüften Quellpfad wurden keine offensichtlichen TODO-/FIXME-/NotImplementedError-Platzhalter als Implementierung gefunden. Eine Textsuche beweist aber keine ungenutzten Codepfade; Aufrufer, Tests, Build-Varianten und Laufzeit sind zu prüfen.

## 4. Aufwandsskala

Agent-Aufwand ist eine grobe Coding-Schätzung: XS bis ca. 2 h; S 2–4 h; M 0,5–1 Arbeitstag; L 1–3 Arbeitstage; XL mehr als 3 Arbeitstage. Bei 📱 DU gibt es keinen Agent-Coding-Aufwand.

Impact in der Ziel-App: H = hoch, M = mittel, L = gering, — = kein direkter Nutzen.

Disposition: BEHALTEN = notwendig; MERGEN = Bestandteil eines größeren Tasks/Testpakets; BEDINGT = nur falls der betreffende Pfad genutzt wird; SPÄTER = nach erfolgreicher Migration; ENTFÄLLT = Demo-/Altaufgabe ohne Zielnutzen; ERLEDIGT = bereits umgesetzt; ERSETZEN = bisherige Annahme ist falsch.

## 5. Einzelbewertung aller 89 offenen Checklisteneinträge

| Alt-ID | Bisheriger Punkt (Kurzform) | Entscheidung | Agent-Aufwand | App-Impact |
|---:|---|---|---:|:---:|
| 1 | Abbruchweitergabe beim Metadaten-/Importpfad | BEDINGT | S | M |
| 2 | Offene P0/P1-Geräteprüfungen allgemein | MERGEN: D1–D5 | — | H |
| 3 | CI-Fehler: veralteter EngineDiagnosticsTest-Parameter | BEHALTEN | XS | H |
| 4 | Veralteten Testaufruf korrigieren und JVM-Tests ausführen | MERGEN: #3 | XS | H |
| 5 | Testreport bei Compiler-/Taskfehlern absichern | BEHALTEN | S | M |
| 6 | Manifest-Kommentar zur Prozessisolation | ERLEDIGT: Kommentar bereits korrekt | — | — |
| 7 | Remote-GGUF-Download gegen Speichererschöpfung absichern | BEDINGT: nur falls RemoteUrl genutzt wird | M | L |
| 8 | VULKAN_STATE als historischen Snapshot kennzeichnen | SPÄTER: Dokumentationshygiene | XS | L |
| 9 | Diagnose-APK auf Demo-Abnahmesteuerung prüfen | ENTFÄLLT: Demo-UI | — | — |
| 10 | Diagnose-Button auf dem nubia öffnen | ENTFÄLLT: Demo-UI | — | — |
| 11 | Ersten CPU/Vulkan-Test in Demo-App ausführen | ERSETZEN: in Ziel-App prüfen | — | H |
| 12 | Fehlerhafte/unverständliche Modellausgaben untersuchen | BEHALTEN | M–L | H |
| 13 | Deterministische Output-Fixtures pro Zielmodellfamilie | BEHALTEN | M | H |
| 14 | Identische CPU-/Vulkan-Ausgaben am Gerät vergleichen | BEHALTEN: D1 | — | H |
| 15 | Struktur, UTF-8, Special Tokens, Finish, Mehrfachanfragen | BEHALTEN: D1 | — | H |
| 16 | Korrektheit vor Performance-Tuning erzwingen | LEITPLINIE | XS | H |
| 17 | LFM3-1.7B-Q4_K_M-Fehlerfixture untersuchen | BEDINGT: wenn Zielmodell | S | M |
| 18 | Q4_K-Prefill/Batch>1, Q5_K/Q6_K und CPU/Vulkan nachweisen | BEHALTEN: unterstützte Zielmodelle | — | H |
| 19 | Mali-Treiber-Fallback für Q4_K×Q8_1 prüfen | BEDINGT: wenn Pfad genutzt | — | M |
| 20 | Kernel-A/B über mehrere Q4_K-Modelle wiederholen | SPÄTER: erst nach Korrektheit | — | L |
| 21 | Veraltete APK/alter Workflow-Commit verwenden | ENTFÄLLT: überholter Nachweis | — | — |
| 22 | Effektiven abortGracePeriodMs-Wert am Gerät prüfen | BEHALTEN: D3 | — | H |
| 23 | Folgeinferenz nach Timeout/Abbruch/Crash/Recovery | BEHALTEN: D3 | — | H |
| 24 | Wiederholte Prozess-Kill-/Reconnect-Zyklen | MERGEN: #23/#35 | — | H |
| 25 | FD-Instrumentation für Load/Wechsel/Unload/Restart | BEHALTEN | M | H |
| 26 | Echter SAF-Provider auf dem nubia | BEHALTEN: D4 | — | H |
| 27 | Lokale und Cloud-Dokumentanbieter testen | BEDINGT: tatsächlich unterstützte Provider | — | M |
| 28 | Context-/Cache-/Cancel-/Reload-/Memory-Regressionen | BEHALTEN | M | H |
| 29 | Context-/Cache-Lifecycle auf dem nubia | BEHALTEN: D3/D5 | — | H |
| 30 | Cache nur bei synchronem Modellzustand wiederverwenden | LEITPLINIE in #28 | S | H |
| 31 | Cache-Ausgabeparität an/aus auf dem Gerät | BEDINGT: falls Cache aktiv bleibt | — | M |
| 32 | Prozesslogs byte-identisch exportieren | SPÄTER: Diagnoseexport | — | L |
| 33 | Spitzen-RAM/Native-/Java-/Graphics-PSS/Vulkan-Heap | BEHALTEN: D5 | — | H |
| 34 | Memory-Guard bei Load/Prefill/Decode/Abort/Reconnect | BEHALTEN: D5 | — | H |
| 35 | Fünf vollständige Load/Inference/Timeout/Kill/Recovery-Zyklen | MERGEN: D3/D5 | — | H |
| 36 | App-Prozessspeicher vor/nach Service-Tod messen | BEHALTEN: D5 | — | M |
| 37 | FD/Binder/Native Handles/Context/Temp-Datei-Leaks | MERGEN: #25/#26/D3 | S | H |
| 38 | Cancel während Metadatenabfrage/Kopieren/Verifikation | BEDINGT: Kopier-Fallback | S | M |
| 39 | JVM/Host-native/Instrumentation/Emulator-IPC-Tests | BEHALTEN | M | H |
| 40 | CI-/Emulatorbeleg nicht als Gerätetest ausgeben | LEITPLINIE in D1–D5 | XS | H |
| 41 | Unterstützte Modellfamilien auf nubia CPU und Vulkan | BEHALTEN: Zielmodellmatrix | — | H |
| 42 | Output/Template/Offload/Kernel/Cancel/Recovery/RAM zusammen | MERGEN: D1–D5 | — | H |
| 43 | Gerät/Build/Modell/Konfiguration/Nachweise dokumentieren | MERGEN: Gerätebericht | XS | M |
| 44 | Kompatibilitätsstatus nur anhand echter Nachweise | BEHALTEN | S | M |
| 45 | Prototyp-EngineViewModel-Vererbung umbauen | ENTFÄLLT: Demo-App | M | — |
| 46 | Prototyp-Screens in Chat/Import/Settings/Diagnostik trennen | ENTFÄLLT: Demo-UI | M | — |
| 47 | Eine gemeinsame Engine-Instanz und Owner sicherstellen | BEHALTEN: App-Owner | S | H |
| 48 | Demo-UI-State-/Event-Fluss refaktorieren | ENTFÄLLT: Demo-UI | M | — |
| 49 | Prototyp-Screen-Navigation/Lifecycle testen | ENTFÄLLT: Consumer-Owner stattdessen testen | M | — |
| 50 | InferenceClient in Komponenten aufteilen | SPÄTER: nur bei konkretem Grund | L | L |
| 51 | Binder-/Recovery-/Operation-Lifecycle auslagern | SPÄTER | L | M |
| 52 | Callback-/PID-/Request-Zustände refaktorieren | SPÄTER: erst Regressionen bewerten | M–L | M |
| 53 | AIDL/öffentliche API stabil halten | LEITPLINIE | XS | H |
| 54 | Große JNI-Datei in Module zerlegen | SPÄTER: hoher Aufwand, kein Blocker | XL | L |
| 55 | Native EngineInstance-Ownership neu strukturieren | SPÄTER: konkreter Grund nötig | L | M |
| 56 | Native Helper/Prompt/Cache/UTF-8-Code aufteilen | SPÄTER | L | L |
| 57 | JNI-Exports/Exception-Barriere/Native-Tests erhalten | LEITPLINIE | S | H |
| 58 | com.example-Paket der Demo umbenennen | ENTFÄLLT: nicht Ziel-App | M | — |
| 59 | Demo-/Bibliothekspakete reorganisieren | ENTFÄLLT: nur Bibliotheksgrenze prüfen | L | — |
| 60 | API-/ModelSource-/Flag-/Diagnoseinventar erstellen | BEHALTEN | M | H |
| 61 | Funktionen als Kern/Demo/Diagnose/Optional/Alt klassifizieren | MERGEN: #60 | S | H |
| 62 | AssetPath-/RemoteUrl-Resolver testen oder entfernen | BEDINGT: wenn vom Consumer angeboten | S | M |
| 63 | Abhängigkeiten/Rechte/Code nur evidenzbasiert entfernen | LEITPLINIE für Bereinigung | S | M |
| 64 | Fake-Binder-/Prozess-Test-Fixtures refaktorieren | SPÄTER: nur wenn Tests es erfordern | M | L |
| 65 | Große IPC-/Diagnostik-Testdateien aufteilen | SPÄTER: Wartbarkeit | M | L |
| 66 | JVM/androidTest/Host-C++-Tests korrekt trennen | BEHALTEN | S | M |
| 67 | Ungenutzte Imports als eigenständige Aufgabe entfernen | ENTFÄLLT: normale Compiler-/Lintpflege | XS | L |
| 68 | Automatische Modulabhängigkeitsregeln | BEHALTEN | M | H |
| 69 | Gradle/NDK/CMake/Submodule reproduzierbar prüfen | BEHALTEN | M | H |
| 70 | CI-Pfadfilter für Dokuänderungen optimieren | SPÄTER: CI-Kosten | S | L |
| 71 | Generierte Overlays/Diagnose-Buildartefakte separieren | BEHALTEN | S | M |
| 72 | Architektur-/Modellkompatibilitäts-/Build-Doku aktuell halten | BEHALTEN | S | M |
| 73 | Commit-/Workflow-/Artefakt-/Gerätenachweise verknüpfen | BEHALTEN: Gerätebericht | S | M |
| 74 | Nur eine aktive Umsetzungswarteschlange führen | LEITPLINIE | XS | L |
| 75 | Consumer-API/Lifecycle/ModelSource/Event-Vertrag definieren | BEHALTEN UND VORZIEHEN | M | H |
| 76 | Demo-UI/Storage/Benchmark/DMS aus Bibliothek heraushalten | ARCHITEKTURLEITPLINIE | XS | H |
| 77 | Alte/neue Parcelable-/AIDL-Kompatibilität prüfen | BEDINGT: keine externe Alt-AIDL-Nutzung belegt | S | L |
| 78 | Manifest/Service/JNI-ABI/minSdk/Coroutines/Dependencies im Consumer | BEHALTEN | M | H |
| 79 | Exakten Engine-Commit pinnen und Consumer-Build/Instrumentation | BEHALTEN | M | H |
| 80 | Engine-/Consumer-Commit/Build/Test/Artefakt dokumentieren | MERGEN: #73 | S | M |
| 81 | Output-Korrektheit pro Zielmodellfamilie und Backend | BEHALTEN: D1 | — | H |
| 82 | Q4_K-Vulkan-Decode/Mali-Offload nachweisen | MERGEN: D2 | — | H |
| 83 | Timeout/Cancel/Reconnect/Load/Cache deterministisch prüfen | MERGEN: D3/D5 | — | H |
| 84 | Fremdes Thema ohne Bezug zur Engine-Migration | ENTFÄLLT: außerhalb des Projektziels | — | — |
| 85 | Prozess-/FD-Reste und Ressourcenwachstum in Wiederholungen | MERGEN: D3–D5 | — | H |
| 86 | Demo-ViewModel-Vererbung/große Demo-Komponenten refaktorieren | ENTFÄLLT: Demo-Refactoring | L | — |
| 87 | Zweck/Owner/Tests optionaler Funktionen festlegen | MERGEN: #60 | S | M |
| 88 | Erst eigenständige Engine-Abnahme, dann Integration | ERSETZEN: Ziel-App direkt integrieren | M | H |
| 89 | Eine aktive Aufgabenliste führen | LEITPLINIE | XS | L |

## 6. Gerätepakete — Aufgaben, die den Nutzer am Zielgerät benötigen

- **D1 – Output-Korrektheit:** vereinbarte GGUF-Modelle mit identischem Prompt/Template/Context und Samplingparametern auf CPU und Vulkan. Sinnvolle Ausgabe, UTF-8, JSON-Struktur, Finish-Grund, Special Tokens und mehrere Requests prüfen.
- **D2 – Mali-Vulkan:** Q4_K-Decode und Prefill, Batch/Microbatch, tatsächliche Layer-Offload- und Graph-Backend-Zahlen, Q5_K/Q6_K soweit im Modellkatalog unterstützt, sowie Treiber-Fallback. Erst nach bestandenem D1 Performance bewerten.
- **D3 – Cancellation/Recovery:** wirksame Timeout-Konfiguration nachweisen; Generierung abbrechen, Timeout eskalieren, Inference-Service beenden, Reconnect und Folgeinferenz ausführen. Mehrere Zyklen wiederholen.
- **D4 – Modellquelle/SAF:** echte SAF-Datei aus lokalen und tatsächlich verwendeten Providern laden, wechseln und entladen. No-copy nur bei geeignetem seekbarem FD akzeptieren; andernfalls den unterstützten Alternativpfad nachvollziehbar anzeigen. FD-Besitz nach Unload/Service-Tod prüfen.
- **D5 – Speicher/Lifecycle:** Spitzenwerte für PSS, Native-/Java-Heap, Graphics-PSS und verfügbaren Vulkan-Heap erfassen; Memory-Pressure-Unload, fünf Last-/Abbruch-/Recovery-Zyklen und Ressourcenfreigabe des überlebenden App-Prozesses prüfen.

## 7. Ergebnis der Migration

Die Übernahme ist abgeschlossen, wenn:

1. myDocAnizer_Mobile ausschließlich die neue :llm-engine-Native-Laufzeit für GGUF-Inferenz verwendet und keine zweite alte llama.cpp-Laufzeit parallel baut.
2. Ein appweiter Engine-Owner existiert; Screens und Services erzeugen keine eigenen Engine-Instanzen.
3. Modellkatalog, Auswahl und letzte Modellidentität der Haupt-App erhalten bleiben; das GGUF wird möglichst direkt von seiner ursprünglichen SAF-Quelle geladen.
4. OCR-Vorverarbeitung, DMS-Prompts, First-/Second-Run-Logik, JSON-Parsing, Grounding, Zusatzfelder, Regelvalidierung und Dokumenten-Workflows weiter funktionieren.
5. Chat-Assistent und Regelgenerator klar zwischen Modellinferenz und deterministischen Tools/Fallbacks unterscheiden. Eine heuristisch erzeugte Regel darf nicht als Modelloutput ausgegeben werden.
6. CPU/Vulkan, Output-Korrektheit, Abbruch/Timeout, Service-Recovery, Speicherverhalten und SAF-Quellen in der Ziel-App verifiziert sind.
7. Erst danach der alte CPU-only JNI-/CMake-Aufbau samt altem llama.cpp-Pin entfernt wird. Nicht benötigte Demo-Funktionen werden nicht migriert.

**Status:** Diese Datei ist der Bewertungsbericht; die einzige verbindliche aktive Abarbeitungsreihenfolge steht in docs/active-todos/MASTER_TODO.md.
