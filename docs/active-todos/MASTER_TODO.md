# Master-TODO — LLM Engine: Korrektheit, Optimierung und Produktreife

**Primäres Ziel:** Die Engine-Logik in `:llm-engine` wird eigenständig so weit entwickelt, korrigiert und optimiert, bis sie zuverlässig, reproduzierbar und für den vorgesehenen produktiven Einsatz geeignet ist. Der Prototyp ist Entwicklungs- und Testträger; seine Demo-Oberfläche ist kein Entwicklungsziel.

**Geprüfte Codebasis:** Vor jeder Implementierung den aktuellen `main`-HEAD und alle betroffenen Dateien erneut prüfen. Historische Commit-SHAs in diesem Dokument sind nur dann relevant, wenn eine Aufgabe ausdrücklich einen Vergleich verlangt.

> **Scope-Grenze:** Eine Analyse oder Planung der späteren Integration in `myDocAnizer_Mobile` findet ausdrücklich noch nicht statt. Auch mögliche Anpassungen an beiden Repositories werden erst nach einem ausdrücklichen GO untersucht. Bis dahin betreffen sämtliche aktiven Maßnahmen ausschließlich die Engine selbst.

> **Einzige aktive Aufgabenliste:** Dieses Dokument ist die einzige Quelle für offene Aufgaben, Prioritäten und Reihenfolge. Es gibt keinen separaten Umsetzungsplan, keine zweite Roadmap und keine zusätzlichen aktiven Prüf-Checklisten.

## Verbindliche Session-Größe

**Jede einzelne Checkbox ist ein eigenständiger, in einer Agent-Session abschließbarer Auftrag.** Die Überschriften gruppieren nur fachlich zusammengehörige Sessions; sie sind keine Sammelaufträge.

- **XS:** bis etwa 1 Stunde · **S:** etwa 1–2 Stunden · **M:** etwa 2–4 Stunden.
- Größere Vorhaben werden vor der Implementierung in mehrere Checkboxen geteilt. Keine Aufgabe soll mehrere voneinander unabhängige Ursachen, Subsysteme oder Refactorings in einer Session erledigen lassen.
- Jede Session liefert ein klar benanntes Ergebnis: gezielte Codeänderung oder abgeschlossene Diagnose, passende Tests, Ergebnis und gegebenenfalls eine knappe Aktualisierung dieses Master-TODOs.
- Eine Diagnose-Session implementiert nicht nebenbei mehrere vermutete Fixes. Ergibt die Diagnose mehrere unabhängige Ursachen, werden daraus vor der Implementierung getrennte Aufgaben im Master-TODO.
- Nach Abschluss einer Session wird nur die zugehörige Checkbox aktualisiert. Keine parallelen Session-Pläne oder Statusdateien anlegen.
- **Kontext sparsam laden:** Root-`AGENTS.md`, `docs/AGENTS.md`, dieses Master-TODO und nur die unmittelbar aufgabenrelevanten technischen Dokumente/Quellstellen lesen. `docs/archive/` ist standardmäßig nicht zu laden. Archivdateien nur öffnen, wenn die konkrete Aufgabe einen historischen Nachweis oder Vergleich benötigt; sie sind keine aktuelle Wahrheit und keine Aufgabenquelle.

## Verantwortlichkeit und Impact

- 🛠️ **AGENT · CODE** — Analyse, Implementierung, gezielte Tests, CI und notwendige Engine-Dokumentation.
- 📱 **DU · ENDGERÄT** — Tests, die echte Laufzeit auf dem nubia Z2462N / Android 16 / ARM64 / Mali-G57 oder reale Android-Dateianbieter voraussetzen. Diese Aufgaben sind keine Agent-Coding-Aufgaben.
- **Engine-Impact:** H = hoch · M = mittel. Gemeint ist der erwartete Nutzen für Korrektheit, Zuverlässigkeit, Leistung oder Wartbarkeit.
- **Abhängigkeiten** stehen direkt an der Aufgabe. Nicht abhängige Sessions dürfen unabhängig erledigt werden; die Reihenfolge innerhalb P0/P1/P2 bleibt maßgeblich.

---

## P0 — Inferenzkorrektheit und kritische Laufzeitfehler

### P0-01 — Fehlgeschlagenen Unit-Test-Build reparieren

- [x] **P0-01a** 🛠️ AGENT · CODE/CI · XS · Impact H — Den veralteten Parameter `includeFullDebugTrace = false` aus `EngineDiagnosticsTest.kt` entfernt (Commit `96100a0`). CI-Run #669 hat `:llm-engine:testDebugUnitTest` vollständig ausgeführt und den Kotlin-Testcode erfolgreich kompiliert; anschließend scheiterten sieben unabhängige Report-Assertions. Diese Folgeprüfung ist in P0-01c erfasst.
- [x] **P0-01b** 🛠️ AGENT · CODE/CI · S · Impact M — Abgeschlossen: Gradle-Taskfehler bleiben ein fehlgeschlagener Job; die immer ausgeführte JUnit-Prüfung weist fehlende/ungültige XML-Berichte, leere Test-Suites sowie Failures/Errors explizit zurück. Der erfolgreiche Lauf #675 bestätigte gültige Berichte für beide Module (Commit `c407f76`; ursprüngliche Report-Gates in `45d7f0c`).
- [x] **P0-01c** 🛠️ AGENT · DIAGNOSE/CODE/TESTS · S · Impact H — Abgeschlossen: Die sieben Report-Testfehler wurden auf sechs veraltete Abschnitts-/Feldformat-Erwartungen und einen echten Verlust alleinstehender PASS-Gate-Nachweise zurückgeführt. Tests an die tatsächlichen nummerierten Abschnitte und getrennten strukturierten Felder angepasst; `EngineDiagnosticsReportBuilder` erhält PASS-Gate-Details, sofern keine passende Matrix-/Abnahmeresultzeile sie bereits abbildet. Verifiziert in Unit-CI-Lauf #675 auf Commit `c407f76`: 117 `:llm-engine`-Tests + 23 App-Tests, jeweils 0 Failures/Errors; JUnit-Reportvalidierung und drei Host-Native-Tests ebenfalls erfolgreich.

### P0-02 — Fehlerhafte oder unverständliche Modellausgaben

- [ ] **P0-02a** 🛠️ AGENT · DIAGNOSE · M · Impact H — **BLOCKIERT: konkreter fehlerhafter Zielgeräte-Lauf fehlt.** Die statische Quellverfolgung ist erfolgt: GGUF-Metadaten laufen über `nativeGetModelMetadata`/`NativeModelMetadataParser` in `ModelInfo`; `nativeProcessChat` rendert Jinja → Legacy → nur ohne explizites Modell-Template Fallback, tokenisiert mit den GGUF-BOS/EOS-Grenzen, initialisiert den Request-Sampler und endet über EOG/MAX_TOKENS/CANCEL/ERROR in `LocalLlmEngineImpl`. Der normale Debug-Build setzt `TEXT_DIAGNOSTICS_ENABLED=false`; damit fehlen im Export der genaue Template-/Tokenizer-Metadatensatz und Prompt-/Token-/Antworttext. Der Modell-SHA liegt bei regulären Dateien nur in `ModelInfo` (nicht als Digest im Diagnoseereignis); bei direkten FD-Quellen wird er absichtlich übersprungen. APK-CI-Lauf #44181372207 prüfte nur das kleine `tiny1m`-GGUF auf Recovery, nicht semantische Antwortkorrektheit. **Keine Codeänderung/Root-Cause-Behauptung ohne reproduzierten fehlerhaften Lauf.** Für den Abschluss werden ein konkreter Prompt mit Soll-/Ist-Ausgabe, exakter GGUF-SHA/Dateigröße und der Export dieses Laufs vom Zielgerät benötigt.
- [x] **P0-02b** 🛠️ AGENT · CODE/TESTS · M · Impact H — Abgeschlossen: Die JNI-Nachrichtenübergabe ist durch eine gezielte Mapper-Fixture für Reihenfolge und unveränderte Nachrichtentexte (einschließlich literal enthaltener `<|im_end|>`-Schreibweise) abgesichert. Der native Prompt-Pfad und ein Host-Test verwenden dieselbe Hilfsfunktion, die Template-eigene Qwen3-ChatML-Kontrolltoken als Special Tokens und Nachrichtenteile ausdrücklich als Literaltext tokenisiert; zusätzlich werden Qwen3-BOS-/offener-Assistant-Prompt-Grenzen und sämtliche nativen Finish-Codes samt Cancel-Override geprüft. Die Fixture verwendet die verifizierte Qwen3-ChatML-Referenzform, führt aber keinen echten GGUF-Jinja-Render oder Zielgeräte-Modelllauf aus. Unit-CI #677 auf Commit `0f62707`: 119 `:llm-engine`- und 23 App-Tests, 0 Failures/Errors; Host-Native-Tests und JUnit-Reportprüfung erfolgreich.
- [x] **P0-02c** 🛠️ AGENT · CODE/TESTS · M · Impact H — Abgeschlossen: JNI und Host-Fixture verwenden denselben generischen Builder für `common_chat_templates_inputs`; Jinja und Legacy erhalten die Thinking-Flag unverändert. Die Qwen3.5-dense-ChatML-Fixture prüft Special-Token-/Literaltext-Trennung, offenen Assistant-Prompt ohne synthetisches EOS, architecture-default GDN (beide Pfade unfused) samt expliziten Overrides sowie vollständige Deaktivierung von Prefix-Reuse/Context-Shift bei Hybrid-/Recurrent-State. Die Kotlin-Fixture prüft alle nativen Finish-Codes und den Cancel-Override. Kein Modellname wurde in generische Engine-Logik eingebaut. Host-Tests belegen die verwendeten Policy-Helper; Android-CI kompiliert den tatsächlichen JNI-Pfad. Kein echter GGUF-Jinja-Render und kein Zielgeräte-Modelllauf – diese bleiben Teil der späteren Geräteabnahme.
- [x] **P0-02d** 🛠️ AGENT · CODE/TESTS · M · Impact H — Abgeschlossen: Eine eigene Gemma-4-Text-Fixture sichert die upstream-belegte Jinja-Rollenform (`assistant` → `model`), die Trennung von Template-Kontrolltoken und literalem Nachrichtentext sowie die offene Assistant-Generation mit genau einem BOS und ohne synthetisches EOS ab. Der Host-Test verwendet die produktiven Prompt-Tokenisierungs-/Boundary-Helper; die Kotlin-Fixture prüft die kleingeschriebene GGUF-Architektur `gemma4`, geordnete Nachrichtenübergabe und alle nativen Finish-Codes samt Cancel-Override. Grundlage ist der unverändert gepinnte llama.cpp-Stand `a4d4f2f`. **Keine echte GGUF-Jinja-Ausführung, keine tatsächlichen Gemma-4-BPE-Merges/Vokabularprüfung und keine Zielgerätefreigabe**; diese bleiben separate Nachweise. `gemma4-assistant` bleibt ausdrücklich P0-02e.
- [ ] **P0-02e** 🛠️ AGENT · CODE/TESTS · M · Impact H — Die eigenständige `gemma4-assistant`-Architektur mit einer separaten Fixture prüfen. Assistant-Graph und gewöhnliche Gemma-4-Textarchitektur nicht als identisch behandeln.
- [ ] **P0-02f** 🛠️ AGENT · CODE/TESTS · S · Impact H — Gemeinsame Ausgabegates für valides UTF-8, unerwartete Steuer-/Special-Tokens, leere Scheinerfolge, abgeschnittene Ausgaben und erlaubte Finish-Gründe absichern.
- [ ] **P0-02g** 🛠️ AGENT · CODE · S · Impact H — Den in P0-02a nachgewiesenen ersten konkreten Fehler korrigieren und nur die dazugehörigen Regressionstests ergänzen. Werden mehrere unabhängige Ursachen gefunden, vor deren Umsetzung je Ursache eine eigene Checkbox in diesem Master-TODO anlegen.

### P0-03 — Testablauf A: Funktion & Performance-Baseline (ASAP)

- [ ] **P0-03a** 🛠️ AGENT · CODE/TESTS · M · Impact H — Testablauf A kurzfristig implementieren: ein schneller, fester Funktionslauf mit integrierter Performance-Baseline für CPU und Vulkan (sofern verfügbar). Zunächst wenige kurze, reproduzierbare Prüfaufgaben nutzen und pro Backend dieselben Modellbytes, Eingaben und kompatiblen effektiven Parameter verwenden. Ausgabe/Inhalt und Finish-Grund prüfen; vollständige Testeingaben und Modellausgaben speichern. Mindestens Modell-SHA/Größe, Engine-Version, effektive Konfiguration, angefordertes/tatsächliches Backend samt Graph-Verteilung, Ladezeit, Prefill, TTFT, Output-Tokens, Decode-Rate sowie verfügbaren RAM-/Speicher-Peak erfassen. Mit einer explizit gespeicherten, zuletzt akzeptierten Baseline vergleichen und Abweichungen sichtbar machen; zunächst keine komplexe Statistik oder frei konfigurierbare Testmatrix bauen. Fehlende bzw. nicht verfügbare Backendwerte klar kennzeichnen. Was sich im praktischen Einsatz als unnötig oder fehlend erweist, wird anschließend gezielt angepasst.
- [ ] **P0-03b** 🛠️ AGENT · CODE/TESTS · S · Impact H — Einen expliziten Korrektheits-Gate für Backend-/Kernel-/Quantisierungs-/Default-Änderungen ergänzen. Numerisch erklärbare Unterschiede von Template-, Tokenizer-, Cache- oder Zustandsfehlern unterscheiden; keine Performance-Konfiguration ohne bestandenen Gate übernehmen.

### P0-04 — Mali-G57-Q4_K-Decode-Pfad

- [ ] **P0-04a** 🛠️ AGENT · CODE/DIAGNOSE · S · Impact H — Nur die aktuelle Q4_K-Dispatch-/Policy-Auswahl im Android-Overlay gegen den gepinnten llama.cpp-Quellpfad prüfen. Den tatsächlich für Mali-AUTO gewählten Kernel und alle Q8_1-/Integer-Dot-Voraussetzungen anhand konkreter Quellstellen dokumentieren.
- [ ] **P0-04b** 🛠️ AGENT · CODE/TESTS · M · Impact H — Den Fallback bei nicht verfügbarer Q4_K×Q8_1-Pipeline automatisiert absichern. Korrekte Quantisierungsparameter und kompatiblen Kernel nachweisen; ein fehlender Vulkan-Pfad darf nicht als erfolgreicher GPU-Pfad erscheinen.

### P0-05 — Abbruch, Timeout und Prozess-Recovery

- [ ] **P0-05a** 🛠️ AGENT · CODE/TESTS · S · Impact H — Den tatsächlich wirksamen Timeout-Snapshot und `abortGracePeriodMs` für einen Request eindeutig nachvollziehbar machen. Testen, dass gespeicherte Modellkonfiguration keine veraltete zu kurze Grace Period reaktiviert.
- [ ] **P0-05b** 🛠️ AGENT · CODE/TESTS · M · Impact H — Den nativen kooperativen Cancel-Pfad und seine Timeout-Eskalation isoliert absichern. Prüfen, dass laufende native Arbeit beendet wird und kein Handle-/Context-Zustand unkontrolliert weiterverwendet wird.
- [ ] **P0-05c** 🛠️ AGENT · CODE/TESTS · M · Impact H — Den IPC-Terminalzustand gegen verspätete Token-/Progress-/Completion-Callbacks absichern. Ein abgebrochener oder abgelaufener Request darf nie nachträglich als Erfolg erscheinen; alte Service-PIDs dürfen nicht neuen Verbindungen zugeordnet werden.
- [ ] **P0-05d** 🛠️ AGENT · ANDROID-TESTS · M · Impact H — Einen gezielten Instrumentationstest für Service-Tod, Client-Reconnect und genau eine erfolgreiche Folgeinferenz ergänzen. Keine UI- oder Demo-Navigationstests in diese Engine-Abnahme aufnehmen.

### P0-06 — Modellladen und Descriptor-Eigentum

- [ ] **P0-06a** 🛠️ AGENT · CODE/TESTS · M · Impact H — Den Eigentums-/Schließlebenszyklus eines Modell-FDs vom Client über Binder und Service bis zur nativen Kopie auf einen Load-/Unload-Pfad zurückverfolgen und mit einem gezielten Test absichern.
- [ ] **P0-06b** 🛠️ AGENT · CODE · S · Impact H — SAF-Descriptor-Voraussetzungen eindeutig validieren: reguläre Datei, Seekability und mmap-Fähigkeit. Nicht unterstützte Provider-Deskriptoren mit einem eindeutigen Fehler statt irreführendem No-copy-Erfolg behandeln.
- [ ] **P0-06c** 🛠️ AGENT · CODE/TESTS · M · Impact H — Fehlerfälle für Modellladen, Abbruch während des Ladens und Unload testen. Nach jedem Fehler-/Unload-Pfad müssen alle Client-, Service- und nativen FD-Kopien geschlossen sein; eine ausdrücklich unterstützte Importalternative getrennt testen.

### P0-07 — Context- und Cache-Zustand

- [ ] **P0-07a** 🛠️ AGENT · CODE/TESTS · S · Impact H — Cache-Identität auf Modellwechsel und alle relevanten Prompt-/Sampler-/Context-Merkmale begrenzen. Eine Cache-Wiederverwendung mit nicht übereinstimmender Identität durch gezielte Tests verhindern.
- [ ] **P0-07b** 🛠️ AGENT · CODE/TESTS · M · Impact H — Den normalen Attention-KV-Prefix-Pfad einschließlich Context-Shift, Tokenpositionskonsistenz und partieller Cache-Entfernung prüfen. Bei nicht sicher entfernbaren Suffixen muss der vollständige Clear-Pfad greifen.
- [ ] **P0-07c** 🛠️ AGENT · CODE/TESTS · M · Impact H — Den vollständigen Memory-Clear für hybride/recurrente Architekturen bei neuer Anfrage, Fehler, Abbruch, Modellwechsel und Unload absichern. Keine teilweise State-Wiederverwendung annehmen, wenn Upstream keinen vollständigen Rollback garantiert.

---

## P1 — Leistung, Ressourcen, Diagnostik und systematische Modellbewertung

### P1-01 — Vulkan-Bottleneck reproduzierbar diagnostizieren

- [ ] **P1-01a** 🛠️ AGENT · CODE/DIAGNOSTIK · M · Impact H — Pro Inferenzlauf Prefill, TTFT und Decode getrennt sowie Queue-Submit/Fence-Wait, Backend-Sync, Logits-Readback und Graph-Backend-Verteilung mit einer gemeinsamen Run-ID erfassen.
- [ ] **P1-01b** 🛠️ AGENT · CODE/DIAGNOSTIK · S · Impact H — Einen kompakten Report für genau einen Diagnose-Lauf erstellen, der die gemessenen Phasen korreliert und fehlende Werte ausdrücklich kennzeichnet. Keine allgemeine Benchmark-Matrix ergänzen.
- [ ] **P1-01c** 🛠️ AGENT · DIAGNOSE · S · Impact H — Einen konkreten Mali-G57-Referenzlauf anhand der erfassten Phasenwerte auswerten und genau den dominanten, belegten Engpass benennen. In dieser Session keine Kernel-/Thread-/Batch-Defaults ändern; Messlücken werden als Folgeaufgaben notiert.

### P1-02 — Memory-Guard und Ressourcenlebenszyklus

- [ ] **P1-02a** 🛠️ AGENT · CODE/TESTS · S · Impact H — Die RAM-Schätzung des ModelMemoryGuard mit Grenzfällen für Dateigröße, Context, Laufzeitreserve und verfügbarem Speicher testen. Schätzwerte klar von gemessenen Spitzenwerten trennen.
- [ ] **P1-02b** 🛠️ AGENT · CODE/TESTS · M · Impact H — Context-Reduktion und Ablehnung bei Speicherdruck anhand kleiner, großer und grenzwertiger Modelle deterministisch testen. Den gewählten effektiven Context und Ablehnungsgrund nachvollziehbar machen.
- [ ] **P1-02c** 🛠️ AGENT · CODE/TESTS · M · Impact M — Freigabe der engine-eigenen Speicher-/Native-Ressourcen bei Unload, fehlgeschlagenem Load und Reconnect testen. Prozessweite Android-/Treiberwerte nicht als ausschließlich engine-eigenen Speicher ausgeben.

### P1-03 — Modellquellen und Downloads

- [ ] **P1-03a** 🛠️ AGENT · CODE/TESTS · M · Impact M — Für HTTPS-Downloads eine harte, konfigurierbare Dateigrößenobergrenze und eine Prüfung des freien Speichers implementieren. `Content-Length` nur als Hinweis verwenden; Streaming darf die Grenze nicht umgehen.
- [ ] **P1-03b** 🛠️ AGENT · CODE/TESTS · S · Impact M — Temporäre Download-Dateien bei Fehler, Abbruch und ungültigem GGUF zuverlässig bereinigen. Nur vollständig validierte Dateien atomar veröffentlichen; vorhandene valide Modelle nicht beschädigen.
- [ ] **P1-03c** 🛠️ AGENT · CODE/TESTS · M · Impact M — Retry/Backoff und Resume nur für den Fall implementieren, dass `RemoteUrl` als Engine-Funktion beibehalten wird. Range-/ETag-Konsistenz, Server ohne Range-Support und abweichende Teildateien gezielt testen.

### P1-04 — Laufzeitstatus und Diagnostik

- [ ] **P1-04a** 🛠️ AGENT · CODE/TESTS · M · Impact M — Backend-Auswahl, verfügbare Backend-Fähigkeit, angeforderte GPU-Layer, tatsächliche Graph-/Layer-Zuordnung und CPU-Fallback getrennt im Ergebnis darstellen.
- [ ] **P1-04b** 🛠️ AGENT · CODE/TESTS · M · Impact M — Den Diagnostikexport größenbegrenzen und redundante Ereignisse reduzieren. Wichtige Fehler-, Backend-, Timeout- und Speicherereignisse sowie Run-IDs müssen erhalten bleiben.
- [ ] **P1-04c** 🛠️ AGENT · CODE/TESTS · S · Impact M — Modelllade- und Inferenzfortschritt an echte Laufzeitphasen binden. Keine künstlichen 0→100-Sprünge und keinen Ready-Zustand vor nativer Einsatzbereitschaft melden.
- [x] **P1-04d** 🛠️ AGENT · CODE/DIAGNOSTIK · M · Impact H — Abgeschlossen: Jede Inferenz protokolliert eine Run-ID mit Modellname/Dateigröße/verifizierter SHA-256 (bei direktem SAF-FD ausdrücklich als nicht verfügbar markiert), Architektur, Backend und effektiv geladener Engine-Konfiguration einschließlich Sampler-Overrides/Timeout. Native Laufzeitereignisse erfassen nun auch die tatsächlich verwendete Jinja-/Legacy-/Fallback-Templatewahl, explizites Template, Tokenizer, BOS/EOS-Grenzen, Thinking-Flag sowie effektive Samplerwerte. **Jede erzeugte LLM-Antwort wird vollständig und Run-ID-korreliert im Log gespeichert**, auch bei Abbruch/Fehler als partielle Ausgabe; der Export enthält diese Textabschnitte. Der explizite Konfigurations-Smoke-Test speichert zusätzlich vollständige Eingabe, Sollwert und Ist-Ausgabe. Normale Chat-Eingaben/gerenderte Prompts und Token-Pieces werden nicht mitgeschrieben. Einzelne Text-Payloads sind auf 64 Ki UTF-16-Zeichen begrenzt und gekennzeichnet; die bestehenden 500-Zeilen-/512-KiB-Prozesspuffer und der 128-KiB-Export bleiben die globale Retention-Grenze. Kein separater Diagnose-Inferenzlauf.

### P1-05 — Testablauf B: Modell-Qualifikation (Platzhalter)

- [ ] **P1-05a** 🛠️ AGENT · KONZEPT/IMPLEMENTIERUNG · M · Impact H — Einen vorläufigen Standard-Testablauf zur Vorauswahl der verfügbaren und zusätzlich aufgenommenen GGUF-Kandidaten erstellen. Unterschiedliche Modellfamilien, Quantisierungsstufen, Community-Modelle und neue Varianten sollen damit mit realistischen, aber zunächst überschaubaren Aufgaben auf Ausgabequalität/-umfang, Laufzeit und Ressourcenbedarf geprüft werden. Ergebnis soll zeigen, welche Modelle ungeeignet sind, nur mit Einschränkungen infrage kommen oder für den großen Vergleich 2026 qualifiziert sind. Die ersten Ergebnisse sollen zugleich Hinweise liefern, welche Szenarien und Prüfumfänge für Testablauf C sinnvoll sind. Details werden erst bei Beginn dieser Aufgabe festgelegt.

### P1-06 — Testablauf C: Großer LLM-Vergleich 2026 (Platzhalter)

- [ ] **P1-06a** 🛠️ AGENT · KONZEPT/IMPLEMENTIERUNG · L · Impact H — Einen umfangreichen, automatisierbaren Vergleich der für Testablauf C qualifizierten Modelle und ihrer geeigneten Konfigurationen durchführen. Realistische, ausreichend breite Testszenarien sollen Qualität, Zuverlässigkeit, Ausgabeumfang, Geschwindigkeit und Ressourcenbedarf vergleichen und später auch den Einfluss relevanter LLM-Parameter untersuchen. Szenarioauswahl, Anzahl, Gewichtung, Parameterumfang und Auswertungsmethode werden bewusst erst anhand der Erkenntnisse aus Testablauf B festgelegt; ein nächtlicher Lauf ist vorgesehen, sofern Umfang und Geräteschutz dies zulassen.
---

## P2 — Codequalität und nachhaltige Wartbarkeit

### P2-01 — Nicht benötigte Engine-Funktionen evidenzbasiert erkennen

- [ ] **P2-01a** 🛠️ AGENT · CODE/DIAGNOSE · M · Impact M — Öffentliche Engine-API, `ModelSource`-Varianten, Build-Flags, Diagnosefunktionen und native Test-Hooks inventarisieren. Für jeden Eintrag tatsächliche Aufrufer, Tests und Abhängigkeiten erfassen; noch nichts entfernen.
- [ ] **P2-01b** 🛠️ AGENT · CODE/DIAGNOSE · S · Impact M — Ausschließlich `AssetPath` anhand echter Aufrufer und Engine-Anforderungen bewerten. Ergebnis: behalten und absichern oder einen konkreten Entfernungsvorschlag mit API-/Kompatibilitätsfolgen im Master-TODO formulieren.
- [ ] **P2-01c** 🛠️ AGENT · CODE/DIAGNOSE · S · Impact M — Ausschließlich `RemoteUrl` anhand echter Aufrufer und Engine-Anforderungen bewerten. Download-Sicherheitsaufgaben aus P1-03 berücksichtigen; keine zweite Remote-Download-Implementierung schaffen.
- [ ] **P2-01d** 🛠️ AGENT · CODE/DIAGNOSE · M · Impact M — Kotlin-/JNI-Test-Hooks, Stubs und konstante Rückgabepfade im Engine-Modul inventarisieren. Nur belegte, unerreichbare oder irreführende Pfade für eine separate Entfernung vorschlagen; absichtliche Plattform-Fallbacks behalten.
- [ ] **P2-01e** 🛠️ AGENT · CODE/DIAGNOSE · S · Impact M — CMake-/Buildvarianten und native Plattform-Stubs prüfen. Unbenutzte Pfade nur nach Beleg und mit dokumentierten ABI-/Build-Auswirkungen zur Entfernung vorschlagen.

### P2-02 — Große Komponenten nur bei nachgewiesenem Nutzen refaktorieren

- [ ] **P2-02a** 🛠️ AGENT · CODE/DIAGNOSE · M · Impact M — Für `InferenceClient` einen Zuständigkeits-/Lebenszyklusplan erstellen: Verbindung, Request-Korrelation, Timeout/Cancel, Callback-Zustand und Ressourcenbesitz. Keine Produktionsdatei in dieser Analyse-Session verschieben.
- [ ] **P2-02b** 🛠️ AGENT · CODE · M · Impact M — Nur eine in P2-02a nachweislich unabhängige `InferenceClient`-Verantwortung extrahieren. Öffentliche API, Binder-Vertrag, Prozessnamen und Zustandsbesitz stabil halten; weitere Extraktionen werden separat geplant.
- [ ] **P2-02c** 🛠️ AGENT · CODE/DIAGNOSE · M · Impact M — Die große JNI-Implementierung nach Model-Load/Ownership, Template-/Prompt-Aufbereitung, Token-Generierung und Terminal-/Diagnosepfad kartieren. JNI-Exports, Exception-Barriere und EngineInstance-Ownership dokumentieren; noch nichts verschieben.
- [ ] **P2-02d** 🛠️ AGENT · CODE · M · Impact M — Nur eine konkrete, durch P2-02c belegte native Verantwortlichkeit extrahieren. JNI-Symbole, ABI, Exception-Barriere und den gepinnten llama.cpp-Unterbaum unverändert halten; keinen Komplett-Refactor durchführen.

### P2-03 — Build-, Test- und technische Dokumentationskonsistenz

- [ ] **P2-03a** 🛠️ AGENT · CODE/CI · S · Impact M — Gradle-/JDK-/SDK-Versionen, Build-Cache und fehlende Wrapper-Komponenten auf einen reproduzierbaren CI-/lokalen Ablauf abgleichen. Nur den JVM-/Gradle-Werkzeugpfad bearbeiten.
- [ ] **P2-03b** 🛠️ AGENT · CODE/CI · S · Impact M — NDK-/CMake-/ABI- und rekursiven llama.cpp-Submodul-Build separat auf Reproduzierbarkeit prüfen. Den Upstream-Pin nicht verändern.
- [ ] **P2-03c** 🛠️ AGENT · CODE/TESTS · S · Impact M — Vorhandene Tests den Ebenen JVM, Host-native und Android-Instrumentation zuordnen. Nur konkrete doppelte oder falsch ausgeführte Tests korrigieren; keine pauschale Testumstrukturierung.
- [ ] **P2-03d** 🛠️ AGENT · CODE/DOKU · S · Impact M — Die betroffenen technischen Referenzen nach tatsächlichen Engine-Änderungen aktualisieren. Nur aktuelle, belegte Architektur-/Build-/Modell-/Diagnostikfakten eintragen; historische Nachweise im Archiv belassen.

---

## Zielgeräte-Sessions — unabhängige Nachweise

Diese Sessions benötigen das echte nubia Z2462N / Android 16 / ARM64 / Mali-G57. Emulator- und CI-Ergebnisse ersetzen sie nicht.

- [ ] **D1a** 📱 DU · ENDGERÄT · Impact H — Qwen3-4B-Instruct-2507 Q4_K_M auf CPU und Vulkan mit identischen Eingaben prüfen. Template, Tokenizer, Special Tokens, UTF-8, Ausgabe, Finish-Grund und eine Folgeanfrage kontrollieren. Exakte Modellbytes/SHA und Laufparameter protokollieren.
- [ ] **D1b** 📱 DU · ENDGERÄT · Impact H — Qwen3.5-4B dense auf CPU und Vulkan prüfen. GDN-CPU-Fallback, übrige Graph-Verteilung, hybride/recurrente State-Behandlung, Ausgabe, Finish-Grund und Folgeanfrage kontrollieren.
- [ ] **D1c** 📱 DU · ENDGERÄT · Impact H — Ein konkretes Gemma-4-Text-GGUF laden und dessen Template, korrekte kurze Ausgabe, Finish-Grund, CPU/Vulkan-Verteilung und Folgeanfrage verifizieren.
- [ ] **D1d** 📱 DU · ENDGERÄT · Impact H — Ein konkretes Gemma-4-Assistant-GGUF separat laden und dessen Assistant-Graph, Template, Ausgabe und Finish-Verhalten prüfen. Nicht aus D1c auf Assistant-Kompatibilität schließen.
- [ ] **D2** 📱 DU · ENDGERÄT · Impact H — Erst nach bestandenem D1a einen reproduzierbaren Qwen3-4B-Instruct-Q4_K_M-Leistungslauf durchführen: tatsächlicher Offload, Graph-Verteilung, Q4_K-Decode/Prefill, TTFT, Decode-Rate und thermischen Zustand erfassen. Kernel-A/B nur bei einer konkreten Diagnosehypothese.
- [ ] **D3** 📱 DU · ENDGERÄT · Impact H — Timeout und kooperativen Abbruch/Eskalation prüfen, den `:inference`-Prozess gezielt beenden und Reconnect/Folgeinferenz in fünf kombinierten Zyklen ausführen. Gleichzeitig PSS, Native-/Java-Heap, Graphics-PSS, Vulkan-Heap soweit verfügbar, offene FDs und Ressourcenfreigabe erfassen.
- [ ] **D4** 📱 DU · ENDGERÄT · Impact H — GGUF über einen realen lokalen SAF-Provider laden, wechseln und entladen. Einen direkten No-copy-Pfad nur bei regulärem seekbarem mmap-fähigem FD akzeptieren; andernfalls den klar ausgewiesenen Import-Fallback prüfen. FD-Eigentum und Schließzeitpunkte nach Unload/Service-Tod kontrollieren.

---

## Kriterien für „Engine fertig“

Die Engine gilt erst dann als ausreichend fertig für den nächsten Projektabschnitt, wenn:

1. JVM-, Host-native und Android-Instrumentation-Builds/Tests für den aktuellen Stand erfolgreich sind.
2. Jede ausdrücklich als unterstützt ausgewiesene Modellarchitektur auf den vorgesehenen Backendpfaden sinnvolle, korrekte und reproduzierbare Ausgaben liefert.
3. CPU/Vulkan-Auswahl, tatsächlicher Offload und Fallbacks nachvollziehbar und korrekt sind.
4. Abbruch, Timeouts, Service-Abstürze, Reconnect und Folgeinferenz deterministisch funktionieren.
5. Modellladen, Descriptor-Eigentum, Context/Cache und Memory-Guard keine Daten-/Ressourcenfehler verursachen.
6. Die auf dem Zielgerät gemessene Leistung und der Ressourcenbedarf für die vorgesehenen Modelle nachvollziehbar dokumentiert und akzeptabel sind.
7. Keine bekannten kritischen Platzhalter, irreführenden Erfolgszustände oder ungenutzten parallelen Laufzeitpfade übrig sind.

**Nicht Teil dieser Liste:** Demo-Screens, Demo-ViewModel-Vererbung, Demo-Navigation, Demo-Modellbibliothek, Demo-Speicherpräferenzen sowie jede Prüfung oder Planung der späteren Integration in ein anderes Repository. Diese Themen werden nicht bearbeitet, bevor sie ausdrücklich neu beauftragt werden.
