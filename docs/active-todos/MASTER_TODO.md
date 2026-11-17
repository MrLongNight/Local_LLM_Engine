# Master-TODO — LLM Engine: Korrektheit, Optimierung und Produktreife

**Primäres Ziel:** Die Engine-Logik in :llm-engine wird eigenständig so weit entwickelt, korrigiert und optimiert, bis sie zuverlässig, reproduzierbar und für den vorgesehenen produktiven Einsatz geeignet ist. Der Prototyp ist Entwicklungs- und Testträger; die Demo-Oberfläche ist kein Entwicklungsziel.

**Geprüfte Codebasis:** main, HEAD 5180ce1abbb0f8d5dfe3fe490d5eb80f207936c9 (Dokumentationsstand vom 17.11.2026). Vor jeder Implementierung den aktuellen main-HEAD und alle betroffenen Dateien erneut prüfen.

> **Scope-Grenze:** Eine Analyse oder Planung der späteren Integration in myDocAnizer_Mobile findet ausdrücklich noch nicht statt. Auch mögliche Anpassungen an beiden Repositories vor dieser Integration werden erst nach einem ausdrücklichen GO untersucht. Bis dahin konzentrieren sich sämtliche aktiven Maßnahmen ausschließlich auf die Engine selbst.

> **Einzige aktive Aufgabenliste.** Keine parallelen aktiven TODO-/Abnahme-Listen. Technische Berichte und historische Nachweise dürfen Details dokumentieren, aber keine zusätzlichen Arbeitswarteschlangen eröffnen.

## Verantwortlichkeit, Aufwand und Impact

- 🛠️ **AGENT · CODE** — Codeanalyse, Implementierung, gezielte Tests, CI, Refactoring und Engine-Dokumentation.
- 📱 **DU · ENDGERÄT** — Tests, die echte Laufzeit auf dem nubia Z2462N / Android 16 / ARM64 / Mali-G57 oder echte Android-Dateianbieter voraussetzen. Diese Aufgaben sind keine Agent-Coding-Aufgaben.
- **Agent-Aufwand:** XS bis ca. 2 h · S ca. 2–4 h · M ca. 0,5–1 Arbeitstag · L ca. 1–3 Arbeitstage · XL mehr als 3 Arbeitstage.
- **Engine-Impact:** H = hoch · M = mittel · L = gering. Gemeint ist der erwartete Nutzen für Korrektheit, Zuverlässigkeit, Leistung oder Wartbarkeit der Engine.

## P0 — Inferenzkorrektheit und kritische Laufzeitfehler

### P0-01 — Fehlgeschlagenen Unit-Test-Build reparieren

- [ ] 🛠️ **AGENT · CODE/CI · XS · Impact H** — EngineDiagnosticsTest.kt übergibt weiterhin includeFullDebugTrace = false an EngineDiagnostics.buildCompactReportForTest(), obwohl die Produktionsfunktion aktuell nur lines entgegennimmt. Den veralteten Parameter entfernen oder nur bei einem belegten Testbedarf einen kompatiblen Vertrag herstellen. Danach :llm-engine:testDebugUnitTest vollständig ausführen.
- [ ] 🛠️ **AGENT · CODE/CI · S · Impact M** — Sicherstellen, dass CI Compiler-/Taskfehler als fehlgeschlagenen Lauf ausweist und fehlende JUnit-Berichte nicht als erfolgreiche Tests oder als „keine fehlgeschlagenen Assertions“ erscheinen.

### P0-02 — Ursachen fehlerhafter oder unverständlicher Modellausgaben finden

- [ ] 🛠️ **AGENT · CODE · M–L · Impact H** — Modellmetadaten, Architektur-/Tokenizer-Erkennung, Chat-Template, Special Tokens, EOS-/Finish-Behandlung, Sampler, Context-Zustand und CPU-/Vulkan-Unterschiede systematisch anhand konkreter fehlerhafter Läufe untersuchen. Keine Symptome durch willkürliche Prompt- oder Parameteränderungen kaschieren.
- [ ] 🛠️ **AGENT · CODE/TESTS · M · Impact H** — Kleine deterministische und datenschutzneutrale Korrektheits-Fixtures für die tatsächlich unterstützten Modellfamilien bereitstellen. Nur Modelle/Architekturen als unterstützt ausweisen, für die der Engine-Pfad real geprüft ist.
- [ ] 🛠️ **AGENT · CODE/TESTS · S · Impact H** — Ausgabe-Gates absichern: valides UTF-8, erwartete Antwortstruktur, keine unerwarteten Steuer-/Special-Tokens, korrekter Finish-Grund, keine leeren Scheinerfolge und definierte Behandlung abgeschnittener Ausgaben.

### P0-03 — CPU-/Vulkan-Korrektheit vor Performanceänderungen absichern

- [ ] 🛠️ **AGENT · CODE/TESTS · M · Impact H** — Vergleichs-Fixtures mit identischem Modell, Nachrichten, Template, Context, Sampler und Tokenlimit für CPU und Vulkan einrichten. Numerisch bedingte Abweichungen von Template-, Tokenizer-, Cache- oder Zustandsfehlern unterscheiden.
- [ ] 🛠️ **AGENT · CODE · S · Impact H** — Korrektheits-Gates als Voraussetzung für Backend-, Kernel-, Quantisierungs- und Default-Änderungen festlegen. Keine schnellere Konfiguration übernehmen, wenn Ausgabequalität oder Backend-Parität regressiert.

### P0-04 — Mali-G57-Q4_K-Decode-Pfad gezielt untersuchen

- [ ] 🛠️ **AGENT · CODE · M · Impact H** — Aktuelle Q4_K-Dispatch-/Policy-Auswahl und dokumentierten BASE-DMMV-Standardpfad mit dem beobachteten Mali-G57-Verhalten abgleichen. Unterstützte Kernel, Quantisierungskombinationen und Voraussetzungen aus dem tatsächlichen Code/Upstream belegen; keine spekulativen Shader- oder llama.cpp-Patches.
- [ ] 🛠️ **AGENT · CODE/TESTS · M · Impact H** — Den tatsächlichen Fallback bei nicht verfügbarer Q4_K×Q8_1-Pipeline automatisiert absichern. Fallback muss korrekte Quantisierungsparameter und einen kompatiblen Kernel wählen; Fehler dürfen nicht als erfolgreicher Vulkan-Pfad verschleiert werden.

### P0-05 — Abbruch, Timeout und Prozess-Recovery deterministisch machen

- [ ] 🛠️ **AGENT · CODE/TESTS · M · Impact H** — Native Cancellation, Timeout-Fristen, Abort-Eskalation, parallele Modelloperationen, Service-Disconnect, veraltete Callbacks und Folgeinferenz nach Prozess-Reconnect mit gezielten Regressionstests absichern.
- [ ] 🛠️ **AGENT · CODE · S · Impact H** — Den tatsächlich wirksamen Timeout-Snapshot und abortGracePeriodMs pro Request eindeutig nachvollziehbar halten. Gespeicherte Modellkonfiguration darf unbeabsichtigt keine zu kurze alte Grace Period reaktivieren.
- [ ] 🛠️ **AGENT · CODE/TESTS · S · Impact H** — Sicherstellen, dass ein abgebrochener oder abgelaufener Request niemals durch einen verspäteten Callback als erfolgreicher Abschluss überschrieben wird und dass die alte Service-PID nicht mit einer neuen verwechselt wird.

### P0-06 — Modellladen und Descriptor-Eigentum absichern

- [ ] 🛠️ **AGENT · ANDROID-TESTS · M · Impact H** — Instrumentation für erfolgreiches/fehlgeschlagenes Laden, Modellwechsel, Unload, Abbruch und Service-Neustart ausführen. Client-, Binder-, Service- und native Descriptor-Kopien sowie ihre Schließzeitpunkte prüfen.
- [ ] 🛠️ **AGENT · CODE · M · Impact H** — SAF-Descriptor-Voraussetzungen und Fehlerpfade eindeutig behandeln. Ein nicht regulärer, nicht seekbarer oder nicht mmap-fähiger Descriptor darf nicht als reguläre Modell-Datei angenommen werden. Eine unterstützte Importalternative muss klar vom direkten No-copy-Pfad getrennt bleiben.

### P0-07 — Context- und Cache-Zustand korrekt halten

- [ ] 🛠️ **AGENT · CODE/TESTS · M · Impact H** — Modellbewussten Cache und Context-Lifecycle bei Context-Shift, Abbruch, Fehler, Modellwechsel, Unload, Prozessneustart und Speicher-Eviction mit gezielten Regressionstests absichern.
- [ ] 🛠️ **AGENT · CODE · S · Impact H** — Cache-Wiederverwendung nur zulassen, wenn alle zugehörigen Modell-/KV-/Sampler-/Context-Zustände nachweislich synchron sind. Bei Unsicherheit vollständig invalidieren statt potenziell beschädigten Zustand weiterzuverwenden.

## P1 — Vulkan-Leistung, Ressourcen und robuste Modellquellen

### P1-01 — Vulkan-Bottleneck mit einem reproduzierbaren Diagnosepfad eingrenzen

- [ ] 🛠️ **AGENT · CODE/DIAGNOSTIK · M · Impact H** — Einen fokussierten Diagnosepfad für Prefill, Time-to-first-token und Decode bereitstellen. backendSyncWaitUs, Queue-Submit/Fence-Wait, Logits-Readback, Graph-Aufteilung und tatsächlich ausgelagerte Operationen pro Lauf korrelieren. Keine allgemeine Benchmark-Matrix ohne konkrete offene Hypothese aufbauen.
- [ ] 📱 **DU · ENDGERÄT · Impact H** — Auf dem Mali-G57 einen normalen, reproduzierbaren Qwen3-4B-Instruct-Q4_K_M-Vulkan-Lauf mit den festgelegten Referenzparametern durchführen und den kompakten Laufbericht samt Antwortkorrektheit bereitstellen. Keine Demo-Benchmark-Oberfläche als Voraussetzung verwenden.
- [ ] 📱 **DU · ENDGERÄT · Impact H** — Bei begründetem Kernelverdacht einen gezielten A/B-Lauf mit identischem Modell/Prompt und bestätigtem Kernel-/Offload-Nachweis durchführen. Nur erweitern, wenn das Ergebnis weitere Ursachen offenlässt.

### P1-02 — Memory-Guard und Ressourcenlebenszyklus belastbar machen

- [ ] 🛠️ **AGENT · CODE/TESTS · M · Impact H** — Modell-RAM-Schätzung, KV-/Context-Anteil, Laufzeitreserve, Guard-Grenzen, dynamische Context-Reduktion und Unload bei Speicherdruck mit Grenzfällen absichern. Schätzwerte klar von tatsächlich gemessenen Spitzenwerten trennen.
- [ ] 📱 **DU · ENDGERÄT · Impact H** — Spitzen-PSS, Native-/Java-Heap, Graphics-PSS und verfügbaren Vulkan-Heap beim Modellladen und bei Inferenz erfassen. Memory-Pressure-Unload und die Ressourcenfreigabe nach Prozessende prüfen.
- [ ] 📱 **DU · ENDGERÄT · Impact H** — Fünf kombinierte Load-/Inference-/Abort-/Timeout-/Reconnect-Zyklen ausführen und nach jedem Zyklus Prozesse, offene Deskriptoren sowie fortschreitendes Ressourcenwachstum kontrollieren.

### P1-03 — Modellquellen und Downloads robust behandeln

- [ ] 🛠️ **AGENT · CODE/TESTS · M · Impact M** — HTTPS-Download gegen unbeschränkten Speicherverbrauch absichern: Content-Length nur als Hinweis behandeln, harte konfigurierbare Dateigröße/Abbruchgrenze und freien Speicher berücksichtigen, temporäre Dateien bei Fehler/Abbruch entfernen und nur validierte GGUF-Dateien atomar veröffentlichen.
- [ ] 🛠️ **AGENT · CODE · M · Impact M** — Retry/Backoff und Resume-Verhalten für Remote-Downloads nur dann implementieren, wenn RemoteUrl als unterstützte Engine-Funktion bestehen bleibt. Range-/ETag-Konsistenz und unvollständige Dateien müssen abgesichert sein.
- [ ] 📱 **DU · ENDGERÄT · Impact H** — Direkten SAF-Ladevorgang mit mindestens einem lokalen Provider und den tatsächlich verwendeten Dokumentanbietern prüfen. No-copy nur bei regulärem seekbarem mmap-fähigem FD akzeptieren; Descriptor-Freigabe nach Unload/Service-Tod prüfen.

### P1-04 — Laufzeitstatus und Diagnostik verlässlich halten

- [ ] 🛠️ **AGENT · CODE · S · Impact M** — Backend-Status, angeforderte GPU-Layer, tatsächlich ausgelagerte Layer/Graph-Nodes, CPU-Fallback, Finish-Grund und reale Tokenmetriken strikt unterscheiden. Keine Backend-Auswahl oder angeforderte Layerzahl als Beweis für GPU-Ausführung ausgeben.
- [ ] 🛠️ **AGENT · CODE/DIAGNOSTIK · M · Impact M** — Diagnostikexport kompakt, strukturiert und größenbegrenzt halten. Redundante Ereignisse vermeiden, wichtige Fehler-/Backend-/Timeout-/Speicherereignisse priorisieren und Lauf-IDs zur Korrelation erhalten.
- [ ] 🛠️ **AGENT · CODE/TESTS · S · Impact M** — Modell-Lade- und Inferenzfortschritt müssen die tatsächlichen Phasen widerspiegeln. Keine künstlichen 0→100-Sprünge vor abgeschlossener Initialisierung oder vorliegende Ready-Zustände vor nativer Einsatzbereitschaft melden.

## P2 — Codequalität, Funktionsumfang und nachhaltige Wartbarkeit

### P2-01 — Nicht benötigte Engine-Funktionen und Altlasten evidenzbasiert bereinigen

- [ ] 🛠️ **AGENT · CODE · M · Impact M** — Öffentliche Engine-API, ModelSource-Varianten, Build-Flags, Diagnosefunktionen und native Test-Hooks inventarisieren. Jede Funktion als Kern, optional, Diagnose/Test-only oder entbehrlich klassifizieren und tatsächliche Aufrufer/Tests dokumentieren.
- [ ] 🛠️ **AGENT · CODE · S · Impact M** — AssetPath und RemoteUrl gegen echte Engine-Anforderungen und Testabdeckung prüfen. Halb unterstützte Varianten entweder vollständig absichern oder nach Prüfung der öffentlichen API gezielt zur Entfernung vorschlagen; keine stillen API-Brüche.
- [ ] 🛠️ **AGENT · CODE · M · Impact M** — TODO/FIXME/Stub-/Dummy-/konstante Rückgabepfade im Engine-Modul einschließlich JNI/CMake/Buildvarianten prüfen. Nur belegte ungenutzte oder irreführende Implementierungen entfernen; absichtliche Fallbacks und Plattformstubs behalten und eindeutig kennzeichnen.

### P2-02 — Große Komponenten nur bei konkretem Nutzen refaktorieren

- [ ] 🛠️ **AGENT · CODE · L · Impact M** — InferenceClient nach klaren Besitz-/Lifecycle-Grenzen aufteilen, falls die aktuellen Koppelungen weitere Fehlerbehebung oder zuverlässige Tests konkret behindern. Fassade, Zustandsbesitz und öffentliche API stabil halten.
- [ ] 🛠️ **AGENT · CODE · XL · Impact L** — Native JNI-Implementierung nur dann in fachlich getrennte Einheiten zerlegen, wenn dadurch ein konkreter Wartungs-/Fehlergrund gelöst wird. JNI-Exports, Exception-Barriere, EngineInstance-Ownership und Upstream-Submodul unverändert absichern.

### P2-03 — Build, Tests und technische Dokumentation konsistent halten

- [ ] 🛠️ **AGENT · CODE/CI · M · Impact H** — Reproduzierbarkeit von Gradle-, NDK-, CMake-, ABI- und rekursivem llama.cpp-Submodul-Build sicherstellen. Host-native, JVM- und Android-Instrumentationstests in der passenden Ausführungsebene halten.
- [ ] 🛠️ **AGENT · CODE/TESTS · S · Impact M** — Test-Fixtures und Testdateien nur dort vereinfachen oder aufteilen, wo echte Duplikate, falsche Testebenen oder konkret erschwerte Diagnose vorliegen. Bestehende Assertions nicht nur aus Stilgründen umschreiben.
- [ ] 🛠️ **AGENT · CODE/DOKU · S · Impact M** — Architektur-, Build-, Modellkompatibilitäts- und Diagnostikdokumentation nach tatsächlichen Engine-Änderungen aktualisieren. Historische CI-/Gerätebelege klar vom aktuellen Status trennen.

## Einmalige Geräte-Abnahme — keine parallelen Sammel-Checklisten

Die folgenden fünf Pakete sind voneinander abgegrenzt. Dieselbe Prüfung wird nicht zusätzlich in einer allgemeinen „alle Punkte abnehmen“-Aufgabe wiederholt.

- **D1 – Ausgabequalität und Modellverhalten:** vereinbarte unterstützte GGUF-Modelle; CPU/Vulkan mit identischen Eingaben; Template, Tokenizer, Special Tokens, UTF-8, Ausgabeform, Finish-Grund und Folgeanfragen.
- **D2 – Mali-Vulkan-Kernel und Performance:** ein reproduzierbarer Referenzlauf; tatsächlicher Offload/Graph-Backend; Q4_K-Decode/Prefill und nur bei konkretem Befund ein gezielter A/B-Kernelvergleich. Performance erst nach bestandenem D1 werten.
- **D3 – Abbruch und Recovery:** Timeout-Konfiguration; kooperativer Abbruch; Abort-Eskalation; gezieltes Beenden des :inference-Prozesses; Reconnect und erfolgreiche Folgeinferenz in fünf kombinierten Zyklen.
- **D4 – Dateideskriptoren und Modellquellen:** realer SAF-Provider; direkter FD-Transfer; geeignete/ungeeignete Descriptor-Arten; Unload, Modellwechsel und Prozessende; keine doppelte FD-/Modellkopie.
- **D5 – Speicher und Lifecycle:** Load-/Inference-Spitzenwerte; Memory-Guard; dynamischer Context; Prozessende und Ressourcenfreigabe; kein fortschreitendes RAM-/FD-Wachstum.

## Kriterien für „Engine fertig“

Die Engine gilt erst dann als ausreichend fertig für den nächsten Projektabschnitt, wenn:

1. JVM-, Host-native und Android-Instrumentation-Builds/Tests für den aktuellen Stand erfolgreich sind.
2. Jede ausdrücklich unterstützte Modellfamilie auf den vorgesehenen Backendpfaden sinnvolle, korrekte und reproduzierbare Ausgaben liefert.
3. CPU/Vulkan-Auswahl, tatsächlicher Offload und Fallbacks nachvollziehbar und korrekt sind.
4. Abbruch, Timeouts, Service-Abstürze, Reconnect und Folgeinferenz deterministisch funktionieren.
5. Modellladen, Descriptor-Eigentum, Context/Cache und Memory-Guard keine Daten-/Ressourcenfehler verursachen.
6. Die auf dem Zielgerät gemessene Leistung und der Ressourcenbedarf für die vorgesehenen Modelle nachvollziehbar dokumentiert und akzeptabel sind.
7. Keine bekannten kritischen Platzhalter, irreführenden Erfolgszustände oder ungenutzten parallelen Laufzeitpfade übrig sind.

**Nicht Teil dieser Liste:** Demo-Screens, Demo-ViewModel-Vererbung, Demo-Navigation, Demo-Modellbibliothek, Demo-Speicherpräferenzen sowie jede Prüfung oder Planung der späteren Integration in ein anderes Repository. Diese Themen werden nicht bearbeitet, bevor sie ausdrücklich neu beauftragt werden.
