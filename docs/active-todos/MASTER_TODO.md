# Master-TODO — Engine-Migration nach myDocAnizer-Mobile

**Ziel:** Die im Repository entwickelte Engine-Laufzeit aus dem Modul :llm-engine ersetzt die bisherige LLM-Engine-Logik in MrLongNight/myDocAnizer_Mobile. Local_LLM_Engine ist der Entwicklungsprototyp und Testträger; seine Demo-App :app ist kein eigenständiges Ziel und wird nicht in die Haupt-App übernommen.

**Geprüfte Basis:**
- Engine-Prototyp: main, Commit 5180ce1abbb0f8d5dfe3fe490d5eb80f207936c9
- Haupt-App: main, Commit 772caa5b6df2921fab1cdbba5f2cca6b487d4f3d
- llama.cpp-Pin des Engine-Moduls: a4d4f2fd4ec85ec6a0a9ece4870416c1b6cc24f5

> **Einzige aktive Aufgabenliste.** Die vollständige Einzelbewertung der vorherigen 89 offenen Punkte und die Code-Altlastenprüfung liegen in [docs/audits/2026-11-17-myDocAnizer-migration-audit.md](../audits/2026-11-17-myDocAnizer-migration-audit.md). Das Audit ist ein Prüfbericht, keine zweite Aufgabenwarteschlange.

## Verantwortlichkeit und Aufwand

- 🛠️ **AGENT · CODE** — Analyse, Implementierung, Refactoring, Tests, CI und Dokumentation.
- 📱 **DU · ENDGERÄT** — reale Ausführung auf nubia Z2462N / Android 16 / ARM64 / Mali-G57 oder mit einem echten SAF-/Cloud-Provider. CI und Emulator ersetzen diese Prüfung nicht.
- Bei gemischten Punkten sind Codevorbereitung und Gerätetest getrennte Aufgaben.
- Aufwand ist eine grobe Coding-Schätzung: **XS** bis 2 h, **S** 2–4 h, **M** 0,5–1 Tag, **L** 1–3 Tage, **XL** mehr als 3 Tage. Für Gerätetests steht „—“; sie sind keine Agent-Coding-Stunden.
- App-Impact: **H** hoch, **M** mittel, **L** gering.

## P0 — Integrationsgrundlage und Engine-Wechsel

### P0-01 — Verifizierten Test-Baselinefehler beheben

- [ ] 🛠️ **AGENT · CODE/CI · XS · Impact H** — EngineDiagnosticsTest.kt verwendet weiterhin includeFullDebugTrace = false, obwohl EngineDiagnostics.buildCompactReportForTest() aktuell nur lines entgegennimmt. Den veralteten Parameter entfernen oder nur dann einen API-Vertrag ergänzen, wenn ein echter Testfall ihn benötigt. Danach :llm-engine:testDebugUnitTest und :app:testDebugUnitTest vollständig ausführen; JUnit-XML für beide Module kontrollieren.
- [ ] 🛠️ **AGENT · CODE/CI · S · Impact M** — CI-Auswertung so prüfen, dass Compiler-/Taskfehler eindeutig als fehlgeschlagener Testlauf ausgewiesen werden und fehlende JUnit-Berichte nicht als „keine Assertions fehlgeschlagen“ gelten.

### P0-02 — Consumer-Vertrag und Ziel-App-Aufrufer festlegen

- [ ] 🛠️ **AGENT · CODE · M · Impact H** — Alle derzeitigen Aufrufer der alten Engine in myDocAnizer_Mobile inventarisieren: Dokument-First-/Second-Run, DMS-Extraktion, Chat-Assistent, Regelgenerator, Benchmarks, Modellimport/-auswahl und Persistenz. Für jeden Aufrufer den neuen LocalLlmEngine-Request, das Ergebnis-/Event-Mapping und den Fallback definieren.
- [ ] 🛠️ **AGENT · CODE · M · Impact H** — Den Consumer-Vertrag für einen appweiten Engine-Owner, Modellidentität/-quelle, EngineConfig, Streaming, Progress, Cancel/Timeout, Status, Fehler und Close festlegen. Keine Compose-, Room- oder DMS-Typen in :llm-engine einführen.
- [ ] 🛠️ **AGENT · CODE/DOKU · S · Impact M** — Die App-Dokumente `docs/LLM_NATIVE_PRODUCTION_PLAN.md`, `docs/LLM_INTEGRATION_DOCUMENTATION.md` und `docs/LLM_FUNKTIONEN_UND_PROZESSE_DOKUMENTATION.md` gegen diese Migration abgleichen. Noch nötige App-Fachanforderungen übernehmen, veraltete Engine-Annahmen korrigieren und die bisherige separate LLM-Live-TODO-Liste als nicht mehr eigenständige Arbeitswarteschlange kennzeichnen. Reine Scanner-/DMS-Arbeiten außerhalb des Engine-Wechsels bleiben dort separat.

### P0-03 — Ausschließlich das Bibliotheksmodul in myDocAnizer einbinden

- [ ] 🛠️ **AGENT · CODE/BUILD · M · Impact H** — Engine-Repository mit geprüftem Commit 5180ce1abbb0f8d5dfe3fe490d5eb80f207936c9 als gepinnte Abhängigkeit aufnehmen und ausschließlich :llm-engine in settings.gradle.kts einbinden. Die Prototyp-Demo-App nicht einbinden. Rekursives llama.cpp-Submodul und Build-Konfiguration im Consumer-Build nachweisen.
- [ ] 🛠️ **AGENT · CODE/BUILD · M · Impact H** — Gradle-/Kotlin-/AGP-/NDK-/CMake-Kompatibilität im tatsächlichen Haupt-App-Build verifizieren. Die Baselines sind aktuell weitgehend gleich (AGP 9.1.1, Kotlin 2.2.10, NDK 27.2.12479018, CMake 3.22.1); ABI-Filter prüfen: :llm-engine baut derzeit ARM64/x86_64, die Haupt-App besitzt zusätzlich 32-Bit-Filter.

### P0-04 — Eine einzige native Laufzeit und einen einzigen Engine-Owner verwenden

- [ ] 🛠️ **AGENT · CODE · L–XL · Impact H** — In myDocAnizer_Mobile einen appweiten Besitzer für LocalLlmEngine implementieren und alle ViewModels/Services über diesen Owner anbinden. Keine Engine pro Bildschirm oder Anfrage erzeugen. Lifecycle-Close nur am tatsächlichen Owner.
- [ ] 🛠️ **AGENT · CODE/BUILD · M · Impact H** — Die alte LlamaCppInferenceEngine-JNI-Laufzeit in der Haupt-App aus dem aktiven Aufrufpfad nehmen. Den Consumer-Build so umstellen, dass nur die native Bibliothek aus :llm-engine eingebunden wird und nicht versehentlich zwei llama.cpp-Versionen bzw. zwei JNI-Laufzeiten parallel gebaut oder geladen werden.
- [ ] 🛠️ **AGENT · CODE · L · Impact H** — Bestehende Modelloperationen, Chat-Requests und Inferenz-Events zentral über den neuen Owner routen. Doppeltes Laden und parallele native Inferenzpfade verhindern.

### P0-05 — DMS-Fachlogik erhalten und an die neue Engine anbinden

- [ ] 🛠️ **AGENT · CODE · L · Impact H** — DMS-Prompts, OCR-Vorfilter, Department-Auswahl, First-/Second-Run-Logik, JSON-Parser, Grounding, Zusatzfelder und Ordnerpfad-Regeln aus der alten Engine-Fassade in der App-Fachschicht erhalten bzw. gezielt separieren. Die wiederverwendbare Engine bleibt modell- und appagnostisch.
- [ ] 🛠️ **AGENT · CODE · M · Impact H** — Sämtliche Dokument-Klassifikationspfade auf Requests/Results der neuen Engine abbilden und Fehler/Abbruch/Timeout explizit in App-Ergebnisse übersetzen. Kein unbemerkter Erfolg durch Fallback nach einem abgebrochenen Request.

### P0-06 — Modellquelle und Modellidentität ohne unnötige Kopie erhalten

- [ ] 🛠️ **AGENT · CODE · L · Impact H** — Die Modellverwaltung der Haupt-App behalten, aber den Modellladepfad auf ModelSource umstellen. Für ContentUri den vorhandenen direkten SAF-FD-Weg der Engine verwenden (copy=false), die URI als Modellidentität speichern und das Descriptor-Lifecycle über Binder korrekt halten. Nur Provider, die keinen regulären seekbaren/mmap-fähigen FD bereitstellen, benötigen einen explizit behandelten Alternativpfad.
- [ ] 🛠️ **AGENT · CODE · M · Impact H** — Letztes ausgewähltes Modell und seine wiederherstellbare Quelle/Konfiguration in der Haupt-App erhalten. Wiederöffnung der SAF-Quelle nach Prozessneustart mit gespeicherten URI-Berechtigungen testen; keine Modellkopie oder Umbenennung ohne technischen Grund.
- [ ] 🛠️ **AGENT · CODE · S · Impact M** — Die bisherigen Modellimport-/Kopierpfade auf Doppelverarbeitung prüfen. Modellkatalog und App-Downloadlogik nicht zusätzlich durch eine zweite Demo-Modellbibliothek ersetzen.

### P0-07 — Modell- und Backend-Konfiguration sauber übertragen

- [ ] 🛠️ **AGENT · CODE · M · Impact H** — Bestehende App-Modellparameter auf EngineConfig abbilden: Context, Batch/Microbatch, CPU-Threads, BackendPreference, GPU-Layer, Sampler und Timeout. Keine stillen Festwerte wie den bisherigen gpuLayers = 0-Aufruf in der App übernehmen.
- [ ] 🛠️ **AGENT · CODE · S · Impact H** — BackendInfo, tatsächliche aktive Backend-Auswahl und geladene Modellmetadaten in den App-Status übersetzen. Eine Vulkan-Auswahl oder ein angeforderter Layer-Wert darf nicht als bestätigter GPU-Offload angezeigt werden.

### P0-08 — KI-Funktionen und Fallbacks fachlich korrekt verdrahten

- [ ] 🛠️ **AGENT · CODE · M · Impact H** — generateRulesFromPrompt() untersuchen und den aktuellen festen Branchen-if/when-Generator ersetzen oder klar als Vorlagenmodus ausweisen. Echte KI-Regelvorschläge müssen über :llm-engine laufen; Kotlin bleibt für Schema-Validierung, Konfliktprüfung und Commit in die Regelverwaltung zuständig.
- [ ] 🛠️ **AGENT · CODE · M · Impact H** — queryDocumentAssistant() und queryDocumentAssistantWithFollowUps() auf einen eindeutigen Ablauf aus Dokumentkontext, Modellantwort und deterministischen Tools umstellen. LlmToolExecutor darf strukturierte Datenabfragen ausführen, aber eine Heuristikantwort nicht als Modellgenerierung ausgeben.
- [ ] 🛠️ **AGENT · CODE · S · Impact H** — Regel-/Tool-Fallbacks explizit als Fallback kennzeichnen und sicherstellen, dass Cancel/Timeout/native Fehler nicht als normale erfolgreiche LLM-Antwort erscheinen.

## P1 — Korrektheit und reale Zuverlässigkeit in der Haupt-App

### P1-01 — Modell-Ausgabekorrektheit für die Zielmodellmatrix sichern

- [ ] 🛠️ **AGENT · CODE/TESTS · M · Impact H** — Synthetische, deterministische Fixtures für tatsächlich unterstützte Modellfamilien ergänzen. Chat-Template, Tokenizer, Special Tokens, UTF-8, JSON-Struktur, EOS/Finish-Grund, Mehrfachanfragen und Context-Neustart prüfen.
- [ ] 📱 **DU · ENDGERÄT · Impact H** — Die identischen Fixtures bzw. festgelegten Testprompts mit identischer GGUF-Datei und Konfiguration auf CPU und Vulkan ausführen. Fehlerhafte Ausgabe von numerischen Backendunterschieden, falschem Template und vorzeitigem EOS trennen.

### P1-02 — Mali-G57-Vulkanpfad belegen, erst danach optimieren

- [ ] 🛠️ **AGENT · CODE/TESTS · M · Impact H** — Prüfen, ob der aktuelle Mali-Q4_K-Policy-/Fallbackpfad für die von myDocAnizer unterstützten Modelle korrekt kompiliert und diagnostiziert wird. Keine Default-Änderung allein aus theoretischen TPS-Schätzungen.
- [ ] 📱 **DU · ENDGERÄT · Impact H** — Q4_K-Decode/Prefill, Batch>1, tatsächlich ausgelagerte Layer, Graph-Node-Verteilung sowie Q5_K/Q6_K nur für vorhandene Zielmodelle auf dem Mali-G57 prüfen. Den Treiber-Fallback bei nicht verfügbaren Pipelines nachweisen.

### P1-03 — Cancel, Timeout, Prozessabbruch und Recovery nachweisen

- [ ] 🛠️ **AGENT · CODE/TESTS · M · Impact H** — Regressionen für native Cancellation, Timeout-Fristen, Abort-Eskalation, Service-Disconnect, veraltete Callbacks, Modell-Neuladen und Folgeinferenz in JVM-/Instrumentation-Tests abdecken.
- [ ] 📱 **DU · ENDGERÄT · Impact H** — Auf dem nubia aktive Generierung abbrechen, Timeout auslösen, Inference-Service-Prozess beenden, Reconnect und Folgeinferenz prüfen. Mindestens fünf wiederholte Zyklen durchführen und den tatsächlichen abortGracePeriodMs-Wert dokumentieren.

### P1-04 — SAF-/Descriptor-Lifecycle auf echten Providerquellen prüfen

- [ ] 🛠️ **AGENT · ANDROID-TESTS · M · Impact H** — Descriptor-Instrumentation für direkten SAF-FD, fehlgeschlagenes Laden, Modellwechsel, Unload, Abbruch und Service-Reconnect ausführen. Client-/Service-FD-Eigentum, Schließzeitpunkt und Fehlerpfade testen.
- [ ] 📱 **DU · ENDGERÄT · Impact H** — Ein GGUF direkt von einem lokalen SAF-Provider und von tatsächlich genutzten Cloud-/Dokumentenprovidern laden, ohne Kopie, sofern der Provider einen seekbaren regulären FD liefert. Andernfalls Alternativpfad/Fehlermeldung prüfen und anschließend FD-Freigabe bestätigen.

### P1-05 — Memory-Guard und Ressourcenlebenszyklus real prüfen

- [ ] 🛠️ **AGENT · CODE/TESTS · M · Impact H** — Tests für Modell-RAM-Schätzung, dynamischen Context, Guard-Grenzen, Unload bei Speicherdruck, Abbruch und Reconnect ausführen. Cache-/Context-Zustand nach Fehler und Prozesswechsel sicher invalidieren.
- [ ] 📱 **DU · ENDGERÄT · Impact H** — Spitzen-PSS, Native-/Java-Heap, Graphics-PSS und Vulkan-Heap soweit auslesbar messen. Memory-Pressure-Entladen sowie fünf Load-/Inference-/Kill-/Reconnect-Zyklen prüfen; kein wachsendes Ressourcen- oder FD-Leck.

### P1-06 — Hardwarestatus und Leistungswerte nicht als Messwerte ausgeben, wenn sie Schätzungen sind

- [ ] 🛠️ **AGENT · CODE · S · Impact M** — Nach Migration checkGpuAccelerationSupport() und dessen veralteten CPU-AAR-Kommentar aus dem alten Enginepfad entfernen. LlmHardwareDetector.detectVulkanGpu() durch echte Backend-Diagnostik ergänzen/ersetzen. Synthetische CPU-Scores und estimateTps() eindeutig als Schätzung kennzeichnen oder nicht mehr für Performanceanzeigen verwenden.

### P1-07 — Test- und Kompatibilitätsnachweise an den Consumer binden

- [ ] 🛠️ **AGENT · CODE/CI · M · Impact H** — JVM-, Host-native, Android-Instrumentation und tatsächlichen myDocAnizer_Mobile-Consumer-Build separat ausführen. Bibliotheksrevision, Consumer-Commit, ABI, Modellkonfiguration und vorhandene Artefakte nachvollziehbar dokumentieren.
- [ ] 🛠️ **AGENT · CODE/DOKU · S · Impact M** — Modell-Kompatibilitätsstatus ausschließlich anhand der tatsächlichen Engine-/Gerätenachweise aktualisieren.

## P2 — Nach erfolgreichem Engine-Wechsel aufräumen

- [ ] 🛠️ **AGENT · CODE · M · Impact H** — Nach nachgewiesener Consumer-Nutzung den alten CPU-only JNI-/CMake-Aufbau aus myDocAnizer_Mobile entfernen: LlamaCppInferenceEngine-Native-Methoden, llama_android.cpp, alten CMake-Zielpfad und das nicht mehr benötigte alte llama.cpp-Submodul. DMS-Prompts/Parser/Fallbacks vorher separat erhalten und sämtliche Referenzen suchen.
- [ ] 🛠️ **AGENT · CODE · M · Impact M** — Ein Funktionsinventar von :llm-engine erstellen: Kern, optionale Modellquelle, Diagnose/Test-only, Demo-only und veraltet. AssetPath, RemoteUrl und optionale Diagnosepfade nur behalten, wenn Consumer-Aufrufer oder ein konkreter Testzweck existieren.
- [ ] 🛠️ **AGENT · CODE/TESTS · M · Impact H** — Architekturgrenze automatisiert absichern: :llm-engine darf keine Compose-, UI-, Room- oder DMS-Abhängigkeit erhalten. JVM-, Android- und Host-C++-Tests in der passenden Testebene halten.
- [ ] 🛠️ **AGENT · CODE · XL · Impact L** — Große InferenceClient-/JNI-Dateien nur dann weiter aufteilen, wenn die Migration einen konkreten Wartungs- oder Fehlergrund zeigt. Keine großflächigen Refactorings als Vorbedingung für den Engine-Wechsel.

## Gemeinsame Architekturregeln

- Nur :llm-engine aus dem Prototyp in die Haupt-App integrieren; niemals die Prototyp-Demo-App.
- Keine Änderungen am öffentlichen Kotlin-Vertrag, AIDL-/Parcelable-Layout, JNI-Symbolen oder ABI ohne konkrete Notwendigkeit und passende Regressionstests.
- Das llama.cpp-Submodul bleibt unverändert; Android-/Mali-Anpassungen gehören in kontrollierte Build-Overlays.
- Korrektheit vor Performance. Kein Vulkan-/Offload-/TPS-Versprechen ohne tatsächlichen Laufzeitnachweis.
- App-spezifische DMS-Logik bleibt in myDocAnizer_Mobile; die Engine bleibt generisch.
- Dateien, API-Varianten und Abhängigkeiten nur entfernen, wenn Aufrufer-, Build- und Testsuche die Entbehrlichkeit bestätigt.
