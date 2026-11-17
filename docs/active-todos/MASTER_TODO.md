# Master-TODO — Local LLM Engine

**Zweck:** Zentrale, einzige aktive Arbeitsliste. Die Reihenfolge ist bewusst festgelegt: Zuerst wird die eigenständige LLM-Engine korrekt und zuverlässig gemacht. Danach werden Code und Architektur bereinigt. Erst anschließend wird die Integration in myDocAnizer-Mobile angepasst und eingefroren.
**Repository:** `MrLongNight/Local_LLM_Engine` · **Branch:** `main`
**HEAD zum Zeitpunkt der Neuordnung:** `529008a90f0c578af74be53bbfad0fd18bf80d30`
**Ziel-Anwendung (ausschließlich in der letzten Phase):** `MrLongNight/myDocAnizer_Mobile`

> **Einzige maßgebliche Quelle:** Dies ist die einzige aktive Aufgabenliste. Architekturunterlagen, Integrationsleitfäden und historische Berichte dürfen Fakten und Schnittstellenverträge dokumentieren, aber keine parallelen Umsetzungslisten erzeugen.

## Audit-Kontrolle — 2026-11-15

- [x] Alle 55 bereits vorhandenen Implementierungs-Checklisteneinträge mit Status [x] anhand der Implementierung und der benannten Tests am Code-HEAD `07b56cd6a3d7417016b1771ae84d11202419ef38` geprüft (dieser Audit-Kontrollpunkt selbst ausgenommen). Ergebnisse: `docs/active-todos/COMPLETED_ITEMS_AUDIT.md`.
- [x] Die widersprüchliche Dokumentation zur Mali-Q4_K-AUTO-Auswahl in P0-02 korrigiert: Der aktuelle Quellcode wählt BASE DMMV. Die fehlerhafte PASS-Zählung bei natürlichem EOG wurde behoben und durch JVM-Tests abgesichert (`9f9afb6`, `c5131fa`); ebenso die Lücke bei der Prüfung des Finish-Grunds (`a73240f`).
- [ ] 🛠️ **AGENT · CODE/CI** — Die separate P1-03-Regression zur Abfrage von Metadaten bei Abbruch sowie die direkte Weitergabe des Abbruchs abschließen, wie im Audit dokumentiert.
- [x] Die JVM- und Android-APK-/Instrumentation-Workflows für den aktuellen HEAD getrennt erneut ausführen und dokumentieren: Unit-Tests, Lauf `43679806775`, bestanden; APK- und Process-Isolation-Instrumentation, Lauf `43679806820`, auf Commit `5a87d8f90b24f36c5ba893ba14eea27e50cc73f1` bestanden. Die CMake-Konfiguration und der Build für ARM64 und x86_64 waren erfolgreich. Debug-APK-Artefakt `14148461029` (37.010.982 Bytes; SHA-256 `821f4aaa60bf6034dcca1aa203c7201dcf244655a8e3f6665c3fe439b28b018e`) und Instrumentation-APK-Artefakt `14148281622` (24.341.340 Bytes; SHA-256 `a9f44b1a2311a024093598f9754e4adecb55b35b7bfc13e66f88f51d38f9e77c`) wurden hochgeladen. Host-native Tests bleiben, soweit zutreffend, ein separat zu verfolgender Verifikationspunkt.
- [ ] 📱 **DU · ENDGERÄT** — Die noch offenen Prüfungen auf realen Geräten aus P0/P1 abschließen. Quellcodeprüfung und CI ersetzen keine Abnahme auf echter Hardware.

## Repository-Nachprüfung — 2026-11-17

**Geprüfter HEAD:** `590dc68030ff37035d8cc5d424c49a609b127cc8` (main)  
**Umfang:** Repository-/Dateibaum, Build- und CI-Konfiguration, zentrale Kotlin-/AIDL-/JNI-/CMake-Pfade, Dokumentationsabgleich und aktuelle GitHub-Actions-Ergebnisse. Kein physischer Gerätetest und keine neue Performance-Messung.

### Verifizierte CI-Lage auf diesem HEAD

- [ ] 🛠️ **AGENT · CODE/CI** — **Unit-Test-Workflow fehlgeschlagen:** Run `43812816059`, Job `133118054466`, Commit `590dc68030ff37035d8cc5d424c49a609b127cc8`. Der Schritt `Run all JVM unit tests` ist fehlgeschlagen. Die nachfolgenden Host-C++-Utility-Tests liefen erfolgreich. Das hochgeladene Testreport-Artefakt `14201625065` enthält 23 bestandene App-Tests, aber keine `:llm-engine`-JUnit-XML-Berichte. Die Ursache ist im Job-Log eindeutig: `:llm-engine:compileDebugUnitTestKotlin` scheitert in `EngineDiagnosticsTest.kt:228`, weil der Aufruf von `EngineDiagnostics.buildCompactReportForTest(...)` den nicht mehr existierenden Parameter `includeFullDebugTrace` übergibt. Die Produktionsfunktion akzeptiert aktuell nur `lines`. Das ist eine konkrete Test-/API-Signatur-Regression, kein fehlgeschlagener Assertion-Test.
- [x] **Android-APK-Workflow erfolgreich:** Run `43812815972` auf demselben HEAD, Conclusion `success`. Debug-APK-Artefakt `14201925203` (SHA-256 `1093571e43525c46af6becd88ca4ce883a59619d0f7a9dd84fb6d880987983b2`) und Instrumentation-APK-Artefakt `14201690793` (SHA-256 `9538406606da60eac060c505c663d4742f7ddc2e7fed7f6ade8f8624f9999f01`) wurden hochgeladen. Das belegt den CI-Build und den im Workflow ausgeführten Instrumentation-Lauf, nicht die Abnahme auf dem nubia/Mali-G57-Zielgerät.
- [x] Host-native Hilfstests `prompt_cache_utils`, `utf8_conversion_utils` und `gdn_fusion_policy` waren im fehlgeschlagenen Unit-Workflow erfolgreich. Das ersetzt weder JNI-/Vulkan-Laufzeitprüfungen noch die fehlenden Engine-JUnit-Ergebnisse.

### Neue Maßnahmen aus dem Code-/Dokumentationsabgleich

#### P1 — CI-Regression und fehlende Testnachweise

- [ ] 🛠️ **AGENT · CODE/CI** — Den veralteten Aufruf in `EngineDiagnosticsTest.kt:228` korrigieren: `includeFullDebugTrace = false` entfernen oder die gewünschte Test-Semantik explizit und kompatibel in der Report-API wiederherstellen. Danach beide JVM-Test-Tasks erneut ausführen und sicherstellen, dass JUnit-XML für `:llm-engine` tatsächlich erzeugt und als Artefakt hochgeladen wird.
- [ ] 🛠️ **AGENT · CODE/CI** — Testreport-Erzeugung so absichern, dass ein Task-Konfigurations-/Kompilierungsfehler nicht als „keine fehlgeschlagenen Assertions“ missverstanden wird. Job-Fehler und Assertion-Fehler getrennt ausweisen.

### Zurückgestellte Play-Store-Freigabe — aktuell kein Fehler und kein Prototyp-Blocker

Die vollständigen Debug-Textlogs sind in der aktuellen Entwicklungs-/Prototyp-Phase bewusst für die Fehleranalyse vorgesehen. Das ist derzeit **kein Datenschutzkonflikt und kein offener P1-Punkt**. Vor einer öffentlichen Play-Store-Veröffentlichung muss die Debug-Textprotokollierung entweder vollständig aus dem Produkt entfernt/deaktiviert oder DSGVO-konform angepasst werden. Diese Freigabeentscheidung ist ein späteres Release-Gate und blockiert die aktuelle Prototyp-Entwicklung nicht.

#### P2 — Dokumentations-/Wartbarkeitsthemen

- [ ] 🛠️ **AGENT · CODE/CI** — Den Kommentar in `llm-engine/src/main/AndroidManifest.xml` präzisieren: `android:process=":inference"` erzeugt einen separaten Prozess, aber `android:isolatedProcess="false"` bedeutet ausdrücklich keine Isolierung mit eigener UID. Die Architektur-Dokumentation beschreibt diese Grenze bereits korrekt.
- [ ] 🛠️ **AGENT · CODE/CI** — Remote-GGUF-Download auf Speichererschöpfungsrisiko prüfen: Der HTTPS-Downloadpfad streamt in eine temporäre Datei, prüft aber vor dem Kopieren weder verfügbaren Speicher anhand von Content-Length noch einen Mindestreservebetrag. Bei unbekannter/fehlender Content-Length bleibt die Größe unbeschränkt. Eine robuste, providerunabhängige Begrenzungs-/Abbruchstrategie definieren und testen.
- [ ] 🛠️ **AGENT · CODE/CI** — Die Dokumentation `docs/VULKAN_STATE.md` als historischen V1-Snapshot kennzeichnen; ihr ursprünglicher geprüfter Commit ist älter als der aktuelle HEAD. Die Architektur-Baseline wurde auf diesen Audit-HEAD aktualisiert.

### Bereits gegengeprüfte Punkte — kein aktueller Fehlernachweis

- `TimeoutConfig` verwendet aktuell `abortGracePeriodMs=3000` als Standard; der frühere 300-ms-Wert ist nicht mehr der aktuelle Standard. Die Inference-Timeout-Policy verwendet `System.nanoTime()` für monotone Stall-Messung.
- Die Bibliotheksmanifest-Deklaration enthält den privaten `:inference`-Service mit `exported=false`; der Service läuft in einem getrennten Prozess unter derselben App-UID.
- Der direkte SAF-Pfad prüft den tatsächlich übertragenen Deskriptor auf regulären Dateityp, Seek-Fähigkeit, GGUF-Magic und mmap-Fähigkeit. JNI dupliziert den FD und verwendet `llama_model_load_from_file_ptr`; das ist kein belegter Fehler im aktuellen Code.
- `ModelMemoryGuard` dokumentiert seine Schätzung als Näherung. Die Schätzung ist kein gemessener Peak-RAM-Nachweis und darf nicht als präzise Vulkan-/Treiber-Speicherprognose interpretiert werden.
- Die CMake-Konfiguration hält das llama.cpp-Submodul auf dem dokumentierten Commit und legt Android-Anpassungen in generierten Build-Overlays ab. Kein Upstream-Patch wurde in dieser Nachprüfung vorgenommen.

---

## Aufgaben-Zuständigkeit und visuelle Kennzeichnung

- 🛠️ **AGENT / CODE** — Implementierung, Codeanalyse, Dokumentation, JVM-/Host-Tests, CI und Emulator-Instrumentation. Das ist bei offenen Aufgaben die Standardzuständigkeit.
- 📱 **DU / ENDGERÄT** — erfordert Installation/Ausführung auf einem echten Android-Endgerät (insbesondere nubia Z2462N / Mali-G57), reale Provider/Dateiauswahl oder einen Geräte-Logexport. Diese Punkte sind ausdrücklich markiert und nicht als Coding-Aufgaben für den Agenten gemeint.
- 🏪 **PLAY-STORE-FREIGABE** — spätere Release-Voraussetzung; aktuell kein Prototyp-Blocker.

## Reihenfolge der Umsetzung und verbindliche Regeln

1. **P0 — Fehler bei der Korrektheit:** Falsche/verstümmelte Modellausgaben, Fehler bei Backend-/Kernel-Auswahl, Inferenz- und Ladefehler, beschädigter Cache/Zustand sowie Fehler bei Abbruch und Wiederherstellung.
2. **P1 — Laufzeitzuverlässigkeit und Nachweise:** Datenschutzgerechte Diagnostik, Ressourcenstabilität, automatisierte Regressionstests und Verifikation der korrigierten Engine auf realen Geräten.
3. **P2 — Code- und Architektur-Refactoring:** Komplexität reduzieren und klare Verantwortungsgrenzen schaffen, ohne das Verhalten zu verändern.
4. **P3 — Bereinigung und technische Leitplanken:** Funktionsinventar, Abhängigkeiten und Build-Hygiene, Testorganisation sowie Dokumentation der eigenständigen Engine.
5. **P4 — Integration in myDocAnizer-Mobile (zuletzt):** Schnittstellenvertrag für die Zielanwendung definieren/einfrieren, Kompatibilitätsanpassungen durchführen und den tatsächlichen nachgelagerten Build erst nach Abnahme von P0–P3 ausführen.

Es wird ausschließlich auf dem bestehenden Branch `main gearbeitet. Vor jeder Änderung HEAD und die betroffenen Blob-SHAs erneut einlesen; wenn möglich, erwarteten HEAD als Schreibschutz/Lease verwenden. Kleine, einzeln überprüfbare Commits erstellen. Das festgelegte llama.cpp-Submodul muss unverändert bleiben. Keine DI-Frameworks, zusätzlichen Abhängigkeiten, NPU-Backends, spekulativen Abstraktionen oder unbelegten Änderungen an Performance-Standardeinstellungen einführen. Während P0–P3 öffentliche Kotlin-/Java-APIs, AIDL-/Parcelable-Strukturen, Manifeste, Prozessnamen, JNI-Exports und ABI-Unterstützung erhalten, außer ein Fehler lässt sich nachweislich nicht anders beheben und eine separate Kompatibilitätsänderung ist dokumentiert. Korrektheit hat Vorrang vor Performance. JVM-Tests, Host-native Tests, APK-Builds, Emulator-Instrumentation und Prüfungen auf realen Geräten getrennt ausweisen. Niemals einen erfolgreichen APK-/Actions-Lauf behaupten, wenn der tatsächlich erfolgreiche Job und das Artefakt nicht geprüft wurden.

---

## P0 — Zuerst Korrektheit und Laufzeitfehler der Engine beheben

### P0-01 — Korrekte Modellausgaben über die unterstützten Modellfamilien hinweg wiederherstellen und absichern

### Neuester Gerätelog und blockierter erster Abnahmetest — 15.11.2026, 19:32

- Der neue Export enthält keinen Lauf des ersten CPU/Vulkan-Abnahmetests: Es gibt keine CORRECTNESS_GATE_RESULT- oder Matrix-Profilzeilen.
- Beide tatsächlich protokollierten Modellladungen forderten CPU an (backendPreference=CPU) und lagerten folgerichtig 0/26 bzw. 0/25 Layer nach Vulkan aus. Die beiden Graph-Snapshots enthalten 100 % CPU-Nodes und 0 Vulkan-Nodes. Das ist kein Vulkan-Fehlernachweis.
- Der erste CPU-Lauf wurde nach 125 Tokens mit finishReason=CANCELLED (4,34 tok/s) beendet; der zweite CPU-Lauf endete nach 136 Tokens regulär mit STOP (5,73 tok/s). Beide Logs enthalten keine Antworttexte, daher ist die Ausgabekorrektheit damit nicht beurteilbar.
- Mali-G57 wird erkannt und meldet int dot=1, fp16=1; der native Vulkan-Profiler war ausgeschaltet. Es gibt keine GPU-Operationszeiten. Der verfügbare RAM lag während der erfassten Inferenzphasen bei mindestens ungefähr 2,25 GB; thermische Samples fehlen.
- Der erste UI-Abnahmetest war im installierten APK deaktiviert, weil TEXT_DIAGNOSTICS_ENABLED=false gebaut wurde. Das war eine nicht freigegebene Änderung und verhinderte die vom UI angebotene CPU/Vulkan-Korrektheitsprüfung.

**Korrektur der Debug-Diagnostik:**
- [x] Debug-Builds standardmäßig mit vollständiger Text-/Token-Diagnostik konfigurieren; Release bleibt ohne diese Diagnoseinstrumentierung.
- [x] Manual-APK-Workflow auf localLlmSyntheticTokenDiagnostics=true umstellen und den nativen Compile-Schalter fail-fast verifizieren.
- [x] Debug-Logs um Originalnachrichten, gerenderten Prompt, Prompt-Token-IDs, Token-Auswahl/Logits, sichtbare Tokenstücke und vollständige Modellantwort ergänzen.
- [x] Im Diagnose-Debug-Modus weder native/Kotlin-Logs rotieren noch Ereignisduplikate, lange Zeilen oder den vollständigen Rohlog-Export abschneiden.
- [ ] 🛠️ **AGENT · CI/BUILD** — Den neuen Actions-Lauf samt APK-Artefakt verifizieren und anhand BuildConfig/Workflow-Nachweis sicherstellen, dass der Abnahmetest im erzeugten Diagnose-APK aktiviert ist.
- [ ] 📱 **DU · ENDGERÄT** — Genau dieses APK auf dem nubia installieren, den Abnahmetest-Bereich öffnen und bestätigen, dass der erste CPU/Vulkan-Abnahmetest-Button tatsächlich angezeigt und ausführbar ist.
- [ ] 📱 **DU · ENDGERÄT** — Den ersten CPU/Vulkan-Abnahmetest mit dem Diagnose-APK ausführen und anhand der vollständigen Ausgabe-Traces die eigentliche Ausgabekorrektheit beurteilen. Die darin enthaltenen Vulkan-Geschwindigkeiten sind wegen CPU-sichtbarer Top-5-Logits nicht als normale Produktions-Performance zu werten.

### Untersuchungsstand — 2026-11-15

- Die hochgeladenen Abnahme-Daten vom 14.11.2026 für LFM3 1.7B Q4_K_M melden wiederholt `EXPECTED_OUTPUT_MISMATCH` sowohl für die CPU-Referenz als auch für den Vulkan-Kandidaten. Das ist kein Beleg für einen ausschließlich Vulkan-bedingten Fehler. Die Daten unterscheiden noch nicht zwischen einem Problem bei der Modellausgabe und einer ungeeigneten Fixture mit einer exakt erwarteten Antwort.
- Der letzte Gerätelauf vom 14.11.2026 verwendete auf CPU und Vulkan `maxPredictTokens=16` (jeweils 16 generierte Tokens). Das aktuelle Source-Gate wurde inzwischen auf ein Sicherheitslimit von 32 Tokens angehoben. Die älteren Mismatch-Ergebnisse prüfen daher nicht das aktuelle Gate. Der damalige Export stammt außerdem von vor den am 15.11.2026 ergänzten inhaltsfreien Diagnosen zur Ausgabeform. Er kann deshalb nicht zeigen, ob das Modell eine leere Antwort, einen abgeschnittenen Anfang, fehlerhaften Text oder eine falsche Zahlenfolge geliefert hat.
- Der gemeinsame Gate-Prompt ist jetzt eine reine ASCII-Textkopieraufgabe (`Copy exactly this text and output nothing else: 322, 324, 326, 328, 330`) statt einer Rechen-/Fortsetzungsaufgabe. Damit wird die Fähigkeit zu rechnen oder zu schlussfolgern als Störfaktor bei der Prüfung der Backend-Inferenz entfernt. Das verbessert die Isolation der Test-Fixture, ist aber **weder eine Korrektur der Modellausgabe noch ein Nachweis semantischer Korrektheit** (`63b5770`, `24a162e`).
- Das neueste Geräteprotokoll des Nutzers vom 15.11.2026 enthält zwei abgeschlossene CPU-Chat-Läufe (jeweils 17 generierte Tokens, Finish-Grund `STOP`, ungefähr 5,56 Tokens/s). Es belegt abgeschlossene Inferenz in genau diesen Läufen. Da die exportierte Zusammenfassung den Antworttext nicht enthält, lässt sich aus Durchsatz und Finish-Grund allein keine semantische Korrektheit ableiten.
- Die bisherige Prompt-Metadatenspur erfasste Template-Modus, BOS/EOS und Token-Anzahl, ließ aber Modellarchitektur, Modellname, Tokenizer-Modell und Thinking-Modus aus. Diese Felder wurden nun zu den inhaltsfreien nativen Prompt-Metadaten hinzugefügt. Fehlgeschlagene Abnahmezeilen enthalten außerdem eine Klassifizierung von Ausgabeform, Länge und Wiederholungen. Der nächste Log-Export kann dadurch leere, zu kurze, fehlerhafte und zusätzliche Ausgaben unterscheiden, ohne Antworttexte zu speichern.
- **Die Ursache ist weiterhin ungeklärt.** Diese Instrumentierungsänderung darf nicht als Korrektur der Modellausgabe verstanden werden; P0-01 darf deshalb nicht als abgeschlossen markiert werden.

**Problem:** Bei Qwen3.5 und anderen Modellpfaden traten falsche oder verstümmelte Ausgaben auf. Ein erfolgreicher Modell-Load oder ein nicht leerer Token-Stream reicht nicht als Nachweis korrekter Inferenz.

- [x] Den vollständigen Generierungspfad nachverfolgen: Request/Nachrichten → Auswahl und Rendering des Chat-Templates → Tokenisierung/BOS/EOS → Prompt-Auswertung → Sampler/Token-Auswahl → Detokenisierung/Streaming → Finish-Grund. Quellcode-Trace und offene Befunde: `docs/active-todos/P0-01-generation-path-audit.md`.
- [ ] 🛠️ **AGENT · CODE/CI** — Ermitteln, warum betroffene Modelle unverständliche Ausgaben liefern oder Ausgabequalität verlieren. Modell-Metadaten, Architekturerkennung, Tokenizer-/Template-Auswahl, Behandlung von Special Tokens, Sampler-Konfiguration, Context-Zustand sowie Unterschiede zwischen CPU und Vulkan untersuchen. Fehler nicht durch Prompt-Formulierungen oder willkürliche Parameteränderungen kaschieren.
- [ ] 🛠️ **AGENT · CODE/CI** — Deterministische Korrektheits-Fixtures für jede unterstützte Modellfamilie ergänzen: Qwen3, Qwen3.5 (Dense/MoE, soweit unterstützt), Gemma 4 und gewöhnliche Attention-Modelle. Die Prompt-Fixtures müssen synthetisch und datenschutzneutral sein.
- [x] Die Umwandlung von Rollen/Inhalten an der JNI-Grenze über die gemeinsame, auf dem Host testbare UTF-16→UTF-8-Hilfsfunktion führen. BMP-Zeichen, gültige/ungültige Surrogates und eingebettete NUL-Bytes in Host-native Tests abdecken.
- [x] Null- und außerhalb des gültigen Bereichs liegende Sampler-Token-IDs vor EOG-Abfrage oder `llama_decode` zurückweisen. Nur numerische ID, Vokabulargröße und Run-ID protokollieren; Host-native Grenzwerttests ergänzen (`d2ac290`). Das ist eine defensive Validierung, aber keine bestätigte Erklärung für das aktuelle Symptom leerer Ausgaben.
- [x] Die UTF-8-Normalisierung des Token-Streams in die Host-testbare native Hilfsfunktion verschieben. Multibyte-Codepoints abdecken, die zwischen Token-Callbacks aufgeteilt werden, ebenso fehlerhaftes/überlanges UTF-8.
- [x] Das konkurrierende Kotlin-seitige Limit für sichtbare Chunks entfernen. Die native Token-ID-Zählung bestimmt jetzt `MAX_TOKENS` und gibt ein noch offenes UTF-8-Suffix vor Stream-Ende aus (`8f94fe3`).
- [x] Ein noch offenes UTF-8-Suffix bei Token-Limit, kooperativem Abbruch, Kotlin-Coroutine-Abbruch und terminalem Fehler als U+FFFD abschließen. Kotlin führt vor jedem terminalen Stream-Ende einen letzten nativen Schritt aus. Native/Kotlin-Übergabe am Stream-Ende synchron halten; einen lokalen Compile-Smoke-Test für unvollständige 2-/3-/4-Byte-Präfixe ergänzen.
- [ ] 📱 **DU · ENDGERÄT** — CPU- und Vulkan-Ausgaben mit identischer GGUF-Datei, identischen Nachrichten, identischem Template, Context, Sampler und identischen Generierungslimits vergleichen. Unterschiede durch numerisches Backend-Verhalten von fehlerhaften Templates, ungültigen Token-IDs, vorzeitigem EOS und beschädigtem Zustand trennen.
- [ ] 📱 **DU · ENDGERÄT** — Erwartete Struktur/Marker, gültiges UTF-8, nicht leere sinnvolle Ausgabe, korrekten Finish-Grund und das Ausbleiben unerwarteter Steuer-/Special-Tokens prüfen. Mehrere Gesprächsrunden und wiederholte Anfragen einbeziehen.
- [x] Den Android-Tiny-GGUF-Recovery-Smoke-Test so erweitern, dass nach Prozessabbruch und erneutem Laden sichtbarer, nicht leerer Text sowie ein regulärer Finish-Grund `STOP`/`MAX_TOKENS` verlangt werden (`23a65a9`). Das deckt Prozesslebenszyklus und Vorhandensein einer Ausgabe ab, nicht die Korrektheit ganzer Modellfamilien.
- [x] Den Recovery-Test mit echter GGUF-Datei um eine zweite aufeinanderfolgende Inferenz im wiederhergestellten Prozess erweitern. Sichtbare Ausgabe und eine andere native Run-ID verlangen, um veralteten Sampler-/KV-/Terminal-Zustand zu erkennen (`34f330f`). Auch das bleibt ein Test für Lebenszyklus und wiederholte Requests, keine Korrektheitsabnahme ganzer Modellfamilien.
- [ ] 🛠️ **AGENT · CODE/CI** — Korrektheits-Gates ausführen, bevor Performance-Standardeinstellungen geändert werden. Keine Geschwindigkeitsverbesserung akzeptieren, wenn dadurch die Ausgabekorrektheit oder Parität verloren geht.
- [x] Eine datenschutzneutrale Klassifizierung der Korrektheitsausgabe für das synthetische Gate ergänzen: `EMPTY`, `PREFIX_TOO_SHORT`, `EXPECTED_PREFIX`, `EXACT_SEQUENCE`, `SEQUENCE_MISMATCH`, `NON_SEQUENCE_TEXT` und `EXTRA_SUFFIX`, zusätzlich zur Anzahl der Nicht-Leerzeichen-Zeichen. Gespeichert werden nur Kategorie und Anzahl; der generierte Antworttext bleibt im Arbeitsspeicher und wird nie in Diagnosen geschrieben. JVM-Regressionstests decken alle Kategorien ab (`4c68e28`).
- [x] Die Token-Auswahlspur des terminalen EOG-Tokens vor der Kotlin-Null-/Terminal-Prüfung erfassen. Die Spur vor jedem nativen Token-Versuch zurücksetzen, damit Schritte ohne Sampling keine veraltete Spur erneut als Duplikat ausgeben (`9bf5f3e`, `d460522`). Das korrigiert nur die Zuordnung der Diagnoseereignisse und belegt nicht die Korrektheit der Modellausgabe.
- [x] Die selbst verursachte Abschneidung im Korrektheits-Gate beheben: 16 generierte Tokens konnten die Antwort mit fünf Zahlen bei Qwen-/LFM-Tokenizern, die Ziffern einzeln tokenisieren, nicht zuverlässig vollständig ausgeben. Sicherheitslimit auf 32 erhöhen, vollständige exakte Antwort verlangen und natürliches frühes EOG nach der Antwort akzeptieren; CPU-/Vulkan-Trace-Längen müssen übereinstimmen. JVM-Tests prüfen Abschneidung, frühes Stoppen und unterschiedliche Trace-Längen (`2279d37`, `faeea29`, `752e26e`, `11db00b`, `dcd85d7`, `dcb6b4f`, `1f02efb`).
- [x] Die Zählung im Gate nach Zulassung eines natürlichen frühen EOG korrigieren: `Evaluation.comparedSteps` meldet beim CPU-only-PASS und bei CPU-/Vulkan-Vergleichsfehlern die tatsächliche Trace-Länge statt des Sicherheitslimits von 32 Tokens. Der Abnahmebericht leitet das angezeigte Gate-Limit ebenfalls aus der gemeinsamen Konstante ab, statt eine veraltete Angabe von 16 Tokens zu behalten (`2c6b1ae`, `77b0407`, `5f7ce1e`).
- [x] Die PASS-Zählung für gepaarte CPU-/Vulkan-Läufe korrigieren: Wenn beide Traces vor dem 32-Token-Limit natürlich enden, muss `candidateSteps.size` gemeldet werden. Regressionstest für einen gepaarten PASS mit natürlichem EOG nach 25 Schritten (`9f9afb6`, `c5131fa`).
- [x] Für die Annahme im Korrektheits-Gate ein natürliches EOG verlangen: PASS nur, wenn `InferenceResult.finishReason == STOP`. `MAX_TOKENS`, `CANCELLED`, `ERROR` und sämtliche Timeout-Gründe ablehnen, auch wenn Antwort und Token-Trace ansonsten übereinstimmen. Den tatsächlichen Finish-Grund im inhaltsfreien Gate-Ergebnis speichern. JVM-Regressionstest für alle Enum-Werte außer `STOP` (`a73240f`).
- [ ] 🛠️ **AGENT · CODE/CI** — Das kompakte Abnahmeergebnis vom 14.11.2026 für LFM3 1.7B Q4_K_M untersuchen: Sowohl CPU-Referenz als auch Vulkan-Kandidat scheiterten an der synthetischen Prüfung der erwarteten Fortsetzung mit `EXPECTED_OUTPUT_MISMATCH`. Da auch die CPU-Referenz scheiterte, belegt dieser Lauf keine Vulkan-spezifische Regression. Die Werte 5,51 gegenüber 3,37 Tokens/s dürfen nicht als akzeptierter Performancevergleich behandelt werden. Bei der nächsten Ausführung anhand der neuen Ausgabeform-Diagnose feststellen, ob die Prompt-/Ausgabeerwartung oder die Modellausgabe falsch ist; Antworttext nicht speichern.

**Abnahmekriterium:** Jede vorgesehene Modellfamilie besteht wiederholbare Ausgabekorrektheitstests auf CPU. Vulkan erfüllt auf unterstützten Geräten denselben Korrektheitsvertrag. Ursachen und weiterhin nicht unterstützte Kombinationen sind ausdrücklich dokumentiert.

### P0-02 — Den Mali-G57-Q4_K-Decode-Kernel-Pfad prüfen und korrigieren

**Aktueller Befund aus der Quellcodeprüfung (15.11.2026):** Mali Q4_K Batch-1 AUTO wählt aktuell den Basis-DMMV-Pfad: `local_llm_mali_auto_prefers_mmvq` ist im generierten Vulkan-Overlay ausdrücklich auf `false` gesetzt. Der abgesicherte Q8_1-/Integer-Dot-MMVQ-Pfad bleibt für explizite diagnostische A/B-Auswahl verfügbar. Andere GPU-Hersteller behalten die festgelegte Upstream-AUTO-Heuristik `ggml_vk_should_use_mmvq()`. Die MMVQ-Laufzeitdaten vom 14.11.2026 sind historische Daten und dürfen nicht als Nachweis der aktuellen Mali-AUTO-Auswahl dargestellt werden. Das festgelegte llama.cpp-Submodul ist unverändert.

**Gerätedaten (14.11.2026):**
- Mali-G57 meldet `int dot=1`, `fp16=1`, `bf16=0`, Warp-Größe 16, 32 KiB Shared Memory und keine Matrix-Cores.
- Das Laufzeitprotokoll für Q4_K-Decode enthält `selected=MMVQ_INTEGER_DOT`, `reason=intDot_q4k_batch1`, `q8_1Eligible=1`, `quantizeY=1` und `yKernelType=q8_1`; beim Prefill wird `Q8_1_MATMUL` protokolliert.
- Ein Sweep mit sechs Q4_K-Pfadläufen auf LFM3 1.7B Q4_K_M (20 gemessene Tokens je Lauf, Context 2048, Batch 128, 2 Threads) ergab für BASE_DMMV 4,14 und 3,92 Tokens/s, für MMVQ_INTEGER_DOT 2,71 und 2,80 Tokens/s sowie bei AUTO-Wiederholung 3,65 Tokens/s. Der Laufzeitpfad wurde als verifiziert markiert. In diesen Läufen war MMVQ etwa 40–48 % langsamer als BASE_DMMV. Das ist ein starkes Performance-Ergebnis für genau diese Modell-/Gerätekombination, aber noch keine Korrektheitsabnahme für alle Q4_K-Modelle.
- Beim Laden von LFM3 wurden 25/25 Modell-Layer auf Vulkan ausgelagert. Die Vulkan-Memory-Budget-Erweiterung wurde nicht unterstützt. Der Heap-Wert von 7,78 GB ist daher eine Schätzung der Heap-Größe/des freien Speichers und kein verlässlicher, vom Treiber gemeldeter freier Speicherwert.

- [x] Die Auswahl gegen das festgelegte llama.cpp `a4d4f2f` vergleichen. Q8_1-Eingabe-, Layout-, Alignment- und Präzisionsvoraussetzungen beibehalten und nur die Upstream-Shape-Heuristik umgehen (`a0f1cee`). CMake bricht die Konfiguration jetzt ab, wenn diese Schutzbedingungen verschwinden.
- [x] 📱 **ENDGERÄT · NACHWEIS VORHANDEN (14.11.2026)** — Auf dem Integer-Dot-fähigen Mali-G57 den Laufzeitnachweis für Q4_K Batch-1 `MMVQ_INTEGER_DOT` mit `reason=intDot_q4k_batch1`, `quantizeY=1` und `yKernelType=q8_1` erfassen.
- [x] 📱 **ENDGERÄT · NACHWEIS VORHANDEN (14.11.2026)** — Wiederholte, den einzelnen Läufen zugeordnete Q4_K-Durchsatzvergleiche zwischen MMVQ und BASE_DMMV mit tatsächlichem Dispatch-Nachweis durchführen. Im aktuellen LFM3-1.7B-Sweep ist MMVQ langsamer. Die Produktions-Standardeinstellung unverändert lassen, bis Korrektheit/Ausgabeparität nachgewiesen ist.
- [x] Im Vulkan-A/B-Runner die Nachweise für den Fallback bei nicht verfügbarem Q4_K×Q8_1-Pipeline-Pfad validieren: Nur eine ausdrücklich gemeldete BASE-Ausführung mit Integer-Dot-/Q8_1-Eignung, `quantizeY=0` und einem Nicht-Q8_1-Fallback-Kernel akzeptieren. Widersprüchliche oder fälschlich als MMVQ bezeichnete Nachweise ablehnen. Der tatsächliche Gerätetest, dass der festgelegte Treiber diesen Fallback ausführt, ist separat offen.
- [x] Die Fallback-Nachweisprüfung in `Q4kPipelineFallbackPolicy` auslagern und JVM-Regressionstests für gültigen Fallback, fehlende Integer-Dot-/Q8_1-Eignung, widersprüchliche Quantisierungs-/Kernel-Angaben, falschen ausgewählten Pfad und normalen Dispatch ohne Fallback ergänzen.
- [x] Die CMake-Konfigurationsprüfung für das generierte Vulkan-Overlay um den expliziten Marker `q8_1_pipeline_unavailable_fallback` erweitern. Sicherstellen, dass er vor dem Zurücksetzen von `quantize_y` protokolliert wird. Upstream-`llama.cpp` bleibt unangetastet.
- [x] Die Upstream-Vulkan-Decode-Auswahl und das generierte Overlay durchgehend nachverfolgen. Im festgelegten `ggml_vk_mul_mat_vec`-Pfad beeinflusst `ggml_vk_should_use_mmvq()` nur die anfängliche `quantize_y`-Heuristik; danach wendet das Overlay die abgesicherte Q4_K-Batch-1-Regel an. Die einzige spätere Änderung an `quantize_y` ist der Null-Q8_1-Pipeline-Fallback, der vor dem Wechsel zum Basiskernel ausdrücklich protokolliert wird. Der Laufzeitnachweis wird nach dieser Fallback-Entscheidung ausgegeben.
- [x] Die festgelegte Upstream-MMVQ-Heuristik für AUTO auf Nicht-Mali-Geräten wiederherstellen. Das Q4_K-Overlay überschreibt AUTO nur bei `vendor_id == 0x13B5` (Mali) und erfüllten Q8_1-/Integer-Dot-Voraussetzungen. Nicht-Mali-AUTO behält das Upstream-`quantize_y`. Explizites MMVQ-A/B bleibt verfügbar, explizites BASE bleibt getrennt. Die Prüfung des generierten Quellcodes verlangt den Vendor-Bereichsmarker (`2f1fa1e`).
- [x] Die vorgesehenen Geltungsbereiche der Pfade durch Quellcode-Audit prüfen und absichern: Q4_K-F16-Prefill nur für Batch>1; Q4_K-Basis-/Mali-AUTO-Decode-Override nur für Batch-1; Q5_K-/Q6_K-Basis-Overrides nur für ihre jeweiligen Batch-1-Tensortypen; explizites MMVQ bleibt an Q8_1-Voraussetzungen gebunden; die festgelegte Upstream-MMVQ-Heuristik bleibt für AUTO auf Nicht-Mali-Geräten erhalten. Das Android-Overlay verändert ausschließlich das generierte `ggml-vulkan.cpp`; CPU-Code und festgelegtes llama.cpp-Submodul bleiben unangetastet.
- [ ] 📱 **DU · ENDGERÄT** — Diese Pfade zur Laufzeit auf dem Gerät nachweisen, einschließlich Q4_K-Prefill/Batch>1, Q5_K/Q6_K, CPU und explizitem A/B-Basis-Override.
- [x] 🛠️ **AGENT · CODE/TESTS** — Deterministische CMake-Scope-Prüfungen für das Source-Overlay und JVM-Policy-Tests für AUTO, explizites BASE, explizites MMVQ, Fallback bei fehlender Pipeline sowie Mali-/Nicht-Mali-Gründe ergänzen. Den Validator korrigieren: Nicht-Mali-AUTO muss den festgelegten Upstream-Grund `upstream` akzeptieren und darf nicht fälschlich den Mali-spezifischen Grund `intDot_q4k_batch1` verlangen. Upstream llama.cpp unangetastet lassen.
- [ ] 📱 **DU · ENDGERÄT** — Auf dem Mali-G57 den tatsächlichen Treiber-Fallback bei nicht verfügbarer Q4_K×Q8_1-Pipeline auslösen und anhand des Laufzeitlogs bestätigen, dass BASE mit `quantizeY=0` und einem Nicht-Q8_1-Kernel gewählt wurde.
- [x] Unbekannte Q4_K-Dispatch-Pfadnamen auch im AUTO-Modus zurückweisen. AUTO darf nur anhand von Nachweisen zwischen den beiden bekannten nativen Pfaden (BASE DMMV oder MMVQ Integer-Dot) auswählen. Der JVM-Regressionstest weist unbekannte Pfadnamen ausdrücklich zurück.
- [ ] 📱 **DU · ENDGERÄT** — Den Kernel-A/B-Vergleich auf Qwen3-4B Q4_K_M und mindestens einem weiteren Q4_K-Modell mit Nachweis der Ausgabekorrektheit wiederholen. Ermitteln, ob die MMVQ-Regression allgemein gilt oder von Architektur/Modell abhängt.

**Abnahmekriterium:** Der vorgesehene Pfad ist durch tatsächliche Laufzeitauswahl und Korrektheitsnachweise belegt, oder die Auswahl wird anhand eines reproduzierbaren Fehlerfalls korrigiert. Eine angeforderte Konfiguration oder ein passender Quellcode-String allein zählt nicht als Nachweis.

### P0-03 — Ursache der Vulkan-Durchsatz- und Synchronisationsregression finden

**Problem:** Aktuelle Messungen auf Mali-G57 zeigen, dass Vulkan beim Decode/Prefill den erwarteten Vorteil nicht erreicht und nahe an CPU liegen oder langsamer sein kann. Im Quellcode ist bereits umfangreiche Diagnostik vorhanden. Der neueste hochgeladene Gerätebericht liefert nun einen deutlichen Hinweis auf einen Synchronisationsengpass sowie einen wiederholbaren Performance-Unterschied zwischen Q4_K-Kernelpfaden.

**Gemessene Gerätedaten (14.11.2026):**
- Native Messwerte für PID 10321 zeigen CPU-Decode p50 von ungefähr 181–185 ms bei einem `backendSyncWait`-p50 von 1 µs. Vulkan-Decode liegt bei ungefähr 354–361 ms, davon entfallen ungefähr 339–345 ms auf `backendSyncWait`. Damit liegen rund 95–96 % des gemessenen Vulkan-Decode-Zeitfensters innerhalb von `llama_synchronize()`; die CPU-Synchronisationswartezeit ist vernachlässigbar.
- Vulkan-`decodeSubmit` liegt bei p50 ungefähr bei 15–16 ms und erklärt damit nicht das Synchronisationsfenster von 339–345 ms. Das grenzt das Problem deutlich ein. `llama_synchronize()` umfasst allerdings mehr als eine reine Fence-Wartezeit: Einreichungsabschluss, Fence-Warten/Polling und verzögerte Kopiervorgänge können ebenfalls beitragen.
- Der Bericht bestätigt die Mali-G57-Gerätefunktionen und die Auslagerung von 25/25 Layern bei LFM3 1.7B. Ein älterer Graph-Snapshot enthält 72 Vulkan-Nodes, 0 CPU-Nodes und 2 nicht zugeordnete Nodes von insgesamt 74. Dieser Graph-Snapshot ist noch nicht dem neuesten Q4_K-Sweep-Lauf zugeordnet.
- Der Q4_K-Sweep liefert gemessene Hinweise darauf, dass MMVQ Integer-Dot bei LFM3 1.7B langsamer ist als BASE_DMMV. Die genauen wiederholten Durchsatzwerte stehen unter P0-02.
- Das neueste CPU-/Vulkan-Abnahme-Gate um 23:03 scheiterte auf beiden Backends an der Prüfung der erwarteten Ausgabe. Die Werte 5,51 gegenüber 3,37 Tokens/s sind deshalb keine akzeptierte kontrollierte Performance-Baseline.
- Der Vulkan-GPU-Operations-Profiler war in den gemeldeten Release-Läufen deaktiviert; es liegen keine Zeitstempel einzelner GPU-Operationen vor. Die Vulkan-Memory-Budget-Erweiterung wurde nicht unterstützt, die Heap-Werte sind daher Schätzungen.

**Im Quellcode umgesetzt (aber noch nicht vollständig auf dem Gerät abgenommen):**
- [x] Den Scheduler-Graph-Nachweis mit Zuordnung zu einzelnen Läufen ergänzen: Graph-Nodes nach CPU/Vulkan/anderen/nicht zugeordneten Backends zählen, einschließlich GDN-/SSM-Op-Klassen. Das generierte `llama-context.cpp`-Overlay erfasst unterschiedliche Graph-Layouts.
- [x] Kompakte Vulkan-Fähigkeitsausgabe (FP16, Integer-Dot, Subgroup und verwandte Gerätefunktionen) erhalten und Vulkan-Heap-/Budget-Informationen abfragen.
- [x] Laufzeitnachweise für Q4_K-Prefill-/Decode-Pfade ergänzen, einschließlich Q8_1-Eignung, `quantizeY`, ausgewählter Kernel-Familie und explizitem Grund für Fallback bei nicht verfügbarer Pipeline.
- [x] Metriken für Prompt-Template/Tokenisierung/Prefill von Sampling pro Token, Decode-Submit und dem Zeitfenster von `llama_synchronize()` trennen. Das Synchronisationsfenster ausdrücklich nicht als reine GPU-Ausführungszeit bezeichnen.
- [x] Zeitmessung für Map/Copy/Sync beim Decode-Logits-Readback sowie optionales GPU-Timestamp-Profiling pro Operation ergänzen. Der GPU-Profiler ist beim Build und zur Laufzeit optional und muss von profilerfreien Durchsatzmessungen getrennt bleiben.
- [x] Die Busy-Poll-Schleife an der Mali-Vendor-ID-Stelle im Upstream-Vulkan-Code durch blockierendes `waitForFences` im generierten Android-Overlay ersetzen; für Nicht-Mali-Geräte das Upstream-Polling beibehalten. Das ist ein möglicher Fix, aber der hochgeladene Bericht isoliert dessen Vorher-/Nachher-Effekt nicht.
- [x] Laufzeit-Callbacks für Vulkan-Queue-Submit und Fence-Wartezeit mit Zuordnung zum einzelnen Lauf ergänzen und in die nativen Token-Step-Metriken aufnehmen. Das Quellcode-Audit gegen das festgelegte Upstream-`a4d4f2f` fand vier Queue-Submit-Stellen: die beiden `ggml_vk_submit()`-Zweige und zwei direkte Compute-Queue-Submissions in `ggml_vk_synchronize()`. Alle vier sind instrumentiert. Die Fence-Zeitmessung deckt die fast fertige Fence, die normale Graph-Fence (einschließlich blockierendem Mali-Warten/Nicht-Mali-Polling), den serialisierten Synchronize-Wait, den serialisierten Graph-Split-Wait und den nur für den Profiler verwendeten Fence-Wait ab. Die CMake-Konfigurationsprüfung verlangt die neuen Pfadmarker. `vkQueueSubmitUs` und `vkFenceWaitUs` messen Host-seitige Aufruf-/Wartezeiten, nicht die Ausführungszeit von GPU-Shadern. Die Verifikation auf realer Hardware ist noch offen (`5c578fb`, `b2c339a`, `246b54c`, `0afa383`).
- [x] Die vorhandene Sampler-Kette des festgelegten llama.cpp für normale, nicht-diagnostische Inferenz beim Vulkan-Kontext registrieren, damit unterstützte Sampler-Stufen im Backend-Graph ausgeführt werden können und nicht bei jedem Token die vollständigen Vocabulary-Logits zur CPU zurückgelesen werden müssen. Die Kette vor Austausch/Entladen wieder lösen. CPU-Sampling bleibt auf der CPU und in Builds mit `TEXT_DIAGNOSTICS`, damit Top-5-Traces vollständige Logits behalten. Die Geschwindigkeitsauswirkung auf Mali ist noch nicht gemessen. Damit wird ein bekannter Transferpfad entfernt, aber nicht behauptet, dass dadurch das separate GPU-Synchronisationsproblem gelöst ist.

**Eine noch ausstehende Geräteentscheidung — keine separate Benchmark-Matrix:**
- [ ] 📱 **DU · ENDGERÄT** — Die Standard-Debug-APK aus Workflow-Lauf `43698646578` (Commit `2096f1b`) für genau einen normalen Qwen3-4B-Instruct-Q4_K_M-Vulkan-Chat mit den etablierten Einstellungen verwenden (Context 2048, Batch 128, Microbatch 128, 37 GPU-Layer). **Verifizierter Build-Nachweis (15.11.2026):** Der APK-Build-Job war erfolgreich; Standard-Debug-Artefakt `14156021001` und Instrumentation-APK-Artefakt `14155921425` wurden hochgeladen. Der Workflow hat ausdrücklich geprüft, dass `TEXT_DIAGNOSTICS_ENABLED=false` ist und `LOCAL_LLM_ENABLE_TEXT_DIAGNOSTICS` nicht in den nativen Build kompiliert wurde. Dieser Build enthält daher den normalen Vulkan-Backend-Sampling-Pfad. Das ältere Artefakt `14153973027` nicht verwenden, da dort `TEXT_DIAGNOSTICS` aktiviert war. Artefakt `14156021001` installieren, einen normalen Qwen3-Chat mit den genannten Einstellungen ausführen, die tatsächliche Antwort prüfen und kompakte Diagnosen exportieren: Tokens/s, Prefill/TTFT, `backendSyncWaitUs`, `vkQueueSubmitUs`, `vkFenceWaitUs`, Phasen des Logits-Readbacks, Graph/Offload, Q4_K-Dispatch und `LOCAL_LLM_BACKEND_SAMPLING`. Dieser einzelne Lauf entscheidet über die nächste Performance-Untersuchung. Keine separate Baseline, Fence-A/B-Prüfung, Profiler- oder Multi-Modell-Matrix, sofern das Ergebnis nicht zwei plausible Fixes offenlässt.

**Abnahmekriterium:** Eine gemessene Ursache erklärt die Regression. Jeder Fix wird durch wiederholbare CPU-/Vulkan-Messungen auf dem Gerät ohne Profiler und bei unveränderter Ausgabekorrektheit belegt. Andernfalls bleibt das sichere Verhalten bestehen und die ungelöste Treiber-/Backend-Einschränkung wird dokumentiert.

### P0-04 — Abbruch, Timeout, Prozessabbruch und Wiederherstellung deterministisch machen

- [x] Den Abbruchpfad vollständig nachverfolgen: Abbruch des Collectors/der Coroutine erreicht `InferenceClient`, Request-ID-geprüftes Binder/AIDL-`cancelInference`, `InferenceService`, `LocalLlmEngineImpl`, `NativeEngineHandle.nativeCancel` und das JNI-Atomic-Flag. Der Service weist veraltete Request-IDs zurück. llama.cpp-`abort_callback` wird nur für CPU installiert, weil die festgelegte `llama.h`-Dokumentation ihn als CPU-only beschreibt. Vulkan erkennt den Abbruch daher zwischen Token-Schritten und ist darauf angewiesen, dass der Watchdog des isolierten Prozesses den Prozess hart beendet, falls ein laufender Decode nicht zurückkehrt.
- [ ] 📱 **DU · ENDGERÄT** — Den tatsächlich wirksamen Wert von `abortGracePeriodMs` aus der Konfiguration des jeweiligen Laufs prüfen. Der Source-Standardwert ist 3.000 ms, aber `LastModelStore` speichert die exakte Timeout-Konfiguration zum Ladezeitpunkt und kann einen älteren/eigenen Wert von 300 ms wiederherstellen. `InferenceClient` protokolliert die wirksamen Werte pro Request und deren Herkunft in `INFERENCE_TIMEOUT_POLICY`. Der JVM-Regressionstest `inferenceUsesRememberedModelsAbortGraceWhenRequestHasNoOverride` prüft inzwischen, dass die gespeicherten 250 ms Grace Period einen Client-Standardwert von 20 ms überschreiben, wenn der Request keinen eigenen Wert vorgibt (`58784cd`). Im hochgeladenen Export vom 14.11.2026 fehlt `INFERENCE_TIMEOUT_POLICY`; der tatsächliche Wert für diesen Lauf ist daher nicht nachgewiesen. Nicht anhand einer einzelnen Token-Zeitmessung anpassen.
- [x] Die tatsächliche Service-PID beim Dispatch des Binder-Requests erfassen und diese unveränderliche Request-PID für Timeout, expliziten Abbruch und Eskalation beim Schließen des Collectors verwenden. Ein Reconnect darf den Abbruch eines alten Requests nicht an einen Ersatz-Service umleiten.
- [x] Regressionstest ergänzen, der die gemeldete Service-PID während eines Requests ändert und sicherstellt, dass Abbruch/Prozessbeendigung nicht an den Ersatzprozess gesendet werden.
- [x] Eskalation nach Prefill-Timeout bei Austausch der Service-PID abdecken; Lebensfähigkeitsprüfungen bleiben an die ursprüngliche Request-PID gebunden.
- [x] Bestätigen, dass zuerst kooperativer Abbruch versucht wird und danach ein harter Prozessabbruch ausschließlich die ursprüngliche Inference-Service-PID trifft, niemals einen Ersatzprozess. Der blockierte native Android-Instrumentationstest prüft jetzt die gespeicherten Ereignisse für kooperativen Abbruch und Eskalation in der richtigen Reihenfolge und kontrolliert die ursprüngliche PID.
- [x] Sicherstellen, dass terminale Ereignisse genau einmal ausgegeben werden und Streams geschlossen werden: `duplicateAndLateTerminalCallbacksAreIgnoredAfterCompletion` löst nach dem ersten Abschluss verspätete Fehler-/Abbruch-/doppelte Abschluss-Callbacks aus und prüft genau ein terminales Ereignis sowie einen finalen Zustand ungleich Inferring. Bestehende Timeout-Tests stellen weiterhin sicher, dass Timeout-Fehler niemals als erfolgreiche Abschlüsse gelten. Prozessabbruch-/veraltete-PID-Abdeckung bleibt separat.
- [x] Den Abbruch des Collectors von `streamChat` als echten nativen Abbruch behandeln: Request-ID während des kooperativen Abbruchs behalten und nach Ablauf der konfigurierten Grace Period an die ursprüngliche Service-PID eskalieren. Regressionstest mit nicht kooperativem Fake-Service ergänzen.
- [x] Fake-Service-Regressionstests für Reconnect zu einer Ersatz-PID, EAGER-Neuladen desselben Modells und eine erfolgreiche Folgeinferenz ergänzen (`eagerReconnectReloadsSameModelAndAllowsFollowUpInference`).
- [x] Fake-Service-Regressionstest ergänzen, der eine erfolgreiche Folgeinferenz nach kooperativem Abbruch belegt (`followUpInferenceSucceedsAfterCooperativeCancellation`).
- [ ] 📱 **DU · ENDGERÄT** — Folgeinferenz nach eskaliertem Abbruch, Timeout und nativem Absturz prüfen; außerdem wiederholte echte Prozessabbruch-/Reconnect-Zyklen auf Android durchführen. Der bestehende einzelne Prozess-Kill-Recovery-Test reicht als Nachweis für reale Geräte nicht aus.
- [x] Fake-Service-Regressionstests für Abbruch während Modell-Load, Prefill (einschließlich explizitem Abbruch vor `Started`), Decode, Stream-Collection und Reconnect ergänzen; Folgeinferenz nach eskaliertem Abbruch prüfen. Die gezielten Tests befinden sich in `InferenceIpcTest` und im Android-Prozess-Kill-Test.
- [ ] 📱 **DU · ENDGERÄT** — Wiederholte echte Prozessabbruch-/Reconnect-Zyklen auf Android durchführen und Folgeinferenz nach eskaliertem Abbruch, Timeout und nativem Absturz prüfen. Die Fake-Service-Testreihe ersetzt keinen Nachweis auf realer Hardware.

**Abnahmekriterium:** Keine hängenden Requests, doppelten terminalen Ereignisse, Prozessbeendigungen mit veralteter PID oder beschädigten Modellzustände nach Abbruch. Nach jedem getesteten Wiederherstellungspfad muss eine folgende Inferenz erfolgreich sein.

### P0-05 — Laden des ausgewählten Modells und Besitzverhältnisse der Dateideskriptoren korrigieren

**Quellcode-Audit:** Der Service akzeptiert und behält bereits einen per Binder übertragenen Dateideskriptor, prüft in `ModelSourceResolver` reguläre Datei-/Seek-/mmap-Fähigkeit und übergibt den service-lokalen Pfad `/proc/self/fd/N` an JNI. Der Fehler lag in `InferenceClient.loadModelInternal()`: Der Zweig für `ContentUri` kopierte das Dokument vor der Binder-Übergabe in den privaten Speicher. Das normale Laden einer einzelnen Datei nutzte daher nie den Deskriptor-Pfad.

- [x] Den normalen `ContentUri`-Ladezweig durch `ContentResolver.openFileDescriptor(uri, "r")` ersetzen und genau diesen ursprünglichen Deskriptor über die bestehende Binder-Methode übertragen.
- [x] Die Provider-URI als Modellidentität im Arbeitsspeicher und als Recovery-Quelle beibehalten und den Anzeigenamen des Providers verwenden. Der explizite Sammelimport bzw. Import in die Modellbibliothek bleibt ein separater Kopiervorgang.
- [x] Deskriptoren ohne bekannte Größe vor der Speicherprüfung zurückweisen; eine ausdrückliche Einschränkung des Providers/mmap anzeigen und nicht stillschweigend kopieren.
- [ ] 🛠️ **AGENT · ANDROID-INSTRUMENTATION** — Die vorhandene Descriptor-Instrumentation für fehlgeschlagenes Laden, Modellwechsel, Entladen sowie Service-Abbruch/Wiederherstellung ausführen und die offenen FD-Zahlen pro Service-PID auswerten. Der Quellcode-Besitz ist: Der Client schließt seine PFD-Kopie nach der Binder-Übergabe; Service-/native Kopien bleiben nur aktiv, solange mmap verwendet wird.
- [ ] 📱 **DU · ENDGERÄT** — Auf dem nubia dieselben Lade-/Wechsel-/Unload-/Recovery-Pfade mit dem realen SAF-Provider ausführen und den exportierten FD-Nachweis bereitstellen.
- [x] Die Größenprüfung der Instrumentation korrigieren: Die `statSize` des Provider-PFD mit den bereitgestellten GGUF-Bytes vergleichen; nicht mit `llama_model_size` (Tensor-/Modell-Payload-Größenrechnung). Dieselbe Fixture in beiden möglichen Cache-Kontexten des Test-Providers bereitstellen.
- [ ] 📱 **DU · ENDGERÄT** — Lokalen Dokument-Provider und repräsentative Cloud-/Dokumenten-Provider testen, einschließlich Entladen/Neuladen und anschließender Inferenz.

**Abnahmekriterium:** Das Laden aus der ursprünglichen Quelle funktioniert durchgehend, soweit unterstützt. Nicht unterstützte Deskriptoren schlagen sichtbar und deterministisch fehl. Es gibt keine undichten Deskriptoren oder versteckten Kopiervorgänge.

### P0-06 — Korrektheit des modellbewussten Caches und des Context-Zustands nachweisen

**Aktueller Implementierungsbefund:** Bei gewöhnlichen Attention-Modellen wird, sofern unterstützt, der gemeinsame Token-Präfix wiederverwendet. Hybride/rekurrente Modelle fallen derzeit auf das Löschen des Zustands zurück. Diesen sicheren Fallback beibehalten, bis alle relevanten Zustandskomponenten nachweislich wiederverwendet werden können.

- [x] Das genaue festgelegte llama.cpp-Revisionverhalten für gewöhnliche Attention-, Sliding-Window-, rekurrente/SSM- und Convolution-Zustände prüfen. LFM3 und Qwen3.5 als hybride/rekurrente Pfade mit sicherem Löschen klassifizieren; gewöhnliche/SWA-only-Modelle als bedingte KV-Präfix-Pfade. Nachweis: `docs/active-todos/P0-06-memory-state-audit.md`.
- [x] Cache-Identität und Invalidierung für geladenes Modell/Dateiidentität, Adapter, Tokenizer/Vokabular, gerendertes Chat-Template, BOS/EOS, System-Prompt, Context-Einstellungen und Session definieren. Das Audit dokumentiert aktuelle Invalidierungsgrenzen und den noch offenen Sonderfall einer nicht an erster Stelle stehenden Systemnachricht beim Context-Shift.
- [x] Host-native Token-Präfixtests für angehängte Gesprächsrunden und Invalidierung bei Änderungen an System-Prompt, gerendertem Template/BOS-Grenze oder Tool-Nachrichten-Tokens ergänzen. Der exakte Präfixvergleich deckt auch Umschreibungen/Löschungen ab dem ersten geänderten Token ab.
- [ ] 🛠️ **AGENT · CODE/TESTS** — Lebenszyklus-Regressionstests für Context-Shifts, Abbruch/Fehler, Modell-Neuladen, Prozess-Neustart und Eviction bei Speicherdruck ergänzen und auf JVM-/Instrumentation-Ebene ausführen.
- [ ] 📱 **DU · ENDGERÄT** — Auf dem nubia Context-Shift, Abbruch, Modell-Neuladen, Prozess-Neustart und Speicherdruck-Eviction ausführen und die anschließende Inferenz sowie den Ressourcenstatus prüfen.
- [x] Nicht zusammenhängende/verschachtelte Systemnachrichten absichern: Wenn Systemnachrichten keinen zusammenhängenden führenden Block bilden, muss Context-Shifting den vollständigen aktuellen Prompt erhalten, statt eine geschützte Systemnachricht versehentlich zu entfernen. Host-native Tests decken führende, fehlende und verschachtelte Systemrollen ab.
- [x] Vor Wiederverwendung des Präfixes und Context-Shifting `cached_tokens.size() == current_position` erzwingen. Bei Abweichung den vollständigen KV-/Cache-Zustand löschen. Host-Tests für kürzere, längere und negative Positionszustände ergänzen.
- [ ] 🛠️ **AGENT · CODE/CI** — Wiederverwendung nur aktivieren, wenn alle Modellzustandskomponenten synchron bleiben. Bei Unsicherheit über die Zustandsintegrität immer auf vollständiges Löschen zurückfallen.
- [ ] 📱 **DU · ENDGERÄT** — Gleichwertigkeit der Ausgaben mit aktiviertem/deaktiviertem Cache nachweisen, bevor Performance gemessen wird. Speicher begrenzen und bei Entladen/Neuladen, Prozessneustart, fehlgeschlagener/abgebrochener Inferenz und Speicherdruck-Eviction invalidieren.

**Abnahmekriterium:** Keine Wiederverwendung veralteter oder sitzungsübergreifender Zustände. Jede unterstützte Architektur besitzt entweder nachgewiesene Zustandswiederverwendung oder einen dokumentierten sicheren Fallback mit vollständigem Löschen.

---

## P1 — Laufzeitzuverlässigkeit und Verifikation

### P1-01 — Vollständige Debug-Diagnostik für die Prototyp-Fehleranalyse

**Befund:** Für die aktuelle Prototyp-Fehleranalyse müssen echte Modellinputs und -ausgaben sichtbar sein. Die vollständigen Text-/Token-Traces sind deshalb in ausdrücklich aktivierten Debug-Diagnosebuilds beabsichtigt. Der Release-Build bleibt ohne diese Diagnoseinstrumentierung. Die spätere Play-Store-Freigabe ist separat als Release-Gate dokumentiert und derzeit kein Entwicklungsblocker.

- [x] Debug-Gradle-Standard auf localLlmSyntheticTokenDiagnostics=true umstellen; Release erzwingt weiterhin TEXT_DIAGNOSTICS_ENABLED=false und LOCAL_LLM_ENABLE_TEXT_DIAGNOSTICS=OFF.
- [x] EngineDiagnostics.testText() speichert vollständige Inhalte in Debug-Diagnosebuilds; lange Nutzdaten werden verlustfrei in mehrere Ereignisse aufgeteilt.
- [x] Im nativen Diagnosepfad Originalnachrichten, final gerenderten Prompt, Prompt-Token-IDs, Token-ID-/Top-5-Logits, Tokenstücke und finale Assistentenantwort protokollieren.
- [x] Im Diagnose-Debug-Modus Logrotation, Ereignislimit, Deduplizierung, Zeilenkürzung und Exportlimit deaktivieren; vollständige Rohlogs im Export zusätzlich zum strukturierten Überblick ausgeben.
- [ ] 📱 **DU · ENDGERÄT** — Auf dem Gerät prüfen, dass beide Prozessdateien vollständig und byte-identisch exportiert werden und der Android-Dokumentanbieter lange Mehrzeilenlogs unverändert übernimmt.

**Abnahmekriterium:** Debug-Diagnosebuilds enthalten vollständige, laufkorrelierte Rohdaten ohne stille Kürzung oder Inhaltsfilter. Release enthält weiterhin keine Prompt-/Antwort-Texttraces.

### P1-02 — Speicher- und Ressourcenverwaltung bei wiederholten Fehlern und Wiederherstellung stabilisieren

- [ ] 📱 **DU · ENDGERÄT** — Spitzen-Speicherbedarf beim Modell-Load, nativen Heap, Java-Heap, PSS, Graphics PSS und Vulkan-Heap/-Budget (soweit verfügbar) prüfen.
- [ ] 📱 **DU · ENDGERÄT** — Verhalten des Memory-Pressure-Guards während Modell-Load, Prefill, Decode, Abbruch und Reconnect bestätigen.
- [ ] 📱 **DU · ENDGERÄT** — Mindestens fünf Zyklen aus Laden/Inferenz/Timeout/Prozessbeendigung/Verbindung-Wiederherstellung auf nubia Z2462N / Android 16 / ARM64 / Mali-G57 durchführen.
- [ ] 📱 **DU · ENDGERÄT** — Speicher vom überlebenden App-Prozess aus vor und nach dem Tod des Service messen. Der beendete Service kann die Freigabe seiner eigenen Ressourcen nicht selbst nachweisen.
- [ ] 📱 **DU · ENDGERÄT** — Dateideskriptoren, Binder-Referenzen, native Handles, Backend-Kontexte und temporäre Dateien nach jedem Pfad prüfen.

**Abnahmekriterium:** Keine Ressourcenlecks und kein fortschreitendes Speicherwachstum über wiederholte Zyklen. Prozessisolation und Bereinigung sind auf Hardware nachgewiesen.

### P1-03 — Weitergabe von Abbrüchen beim Modellimport korrigieren

**Befund:** `ModelImportViewModel.importModelsFromSaf()` fing früher pro Datei `Exception` ab, ohne `CancellationException` erneut auszulösen. Das explizite erneute Auslösen ist inzwischen implementiert; Lebenszyklus- und Bereinigungstests sind noch offen.

- [x] `CancellationException` in der Sammelimport-Schleife für SAF vor der Behandlung wiederherstellbarer Fehler pro Datei erneut auslösen.
- [x] Sicherstellen, dass ein Abbruch weitere Dateien stoppt, bereits abgeschlossene Importe erhält, die Modellliste nach Teilerfolg aktualisiert, unvollständige temporäre Dateien im Resolver entfernt und den UI-Zustand genau einmal zurücksetzt.
- [ ] 🛠️ **AGENT · CODE/CI** — Tests für Abbruch während Berechtigungs-/Metadatenabfrage, Kopieren, Verifikation und zwischen Dateien ergänzen. Audit-Hinweis: Die Abfrage des Anzeigenamens beim Sammelimport umschließt aktuell `ContentResolver.query()` mit `runCatching`. Eine Abbruch-Exception wird dadurch vorübergehend in den Fallback-Namen umgewandelt und erst beim unmittelbar folgenden `ensureActive()` bemerkt. Einen deterministischen Test für einen Abbruch während einer blockierten Metadatenabfrage ergänzen und erwägen, den breiten Catch durch eine explizite Weitergabe von `CancellationException` zu ersetzen.

**Abnahmekriterium:** Ein abgebrochener Import darf nicht als Folge gewöhnlicher Dateifehler weiterlaufen oder Erfolg melden.

### P1-04 — Regressionstests und Geräteabnahme der eigenständigen Engine abschließen

**Nachgetragene Gerätetests des Nutzers (auf Grundlage bisheriger Rückmeldungen; nicht als ungetestet behandeln):**
- **31.10.2026, Build `0f469db`, nubia Z2462N:** Der Nutzer meldete 7 Testläufe. `TEST_1_OK`, `TEST_2_DMS`, `TEST_3_JSON` und CPU-Chat erfolgreich; Extraktion und ein CPU-Chat-Lauf endeten mit TIMEOUT; Vulkan-Chat erfolgreich mit 3,53 Tokens/s. Das belegt funktionierende CPU-/Vulkan-Inferenz für genau diese Läufe, nicht fehlerfreie Unterstützung aller Profile.
- **31.10.2026, Build `6f9b94f`, nubia Z2462N:** Der Nutzer meldete 9 Profiltests. Vulkan-Extraktion erfolgreich (88 Tokens, JSON-Inhalt); weitere Vulkan-Tests waren inhaltlich fehlerhaft bzw. scheiterten durch MAX_TOKENS, CPU-Profile überwiegend TIMEOUT. Diese Fehler sind konkrete, bereits bekannte Einschränkungen und dürfen nicht als fehlende Testdaten geführt werden.
- **07.11.2026:** Der Nutzer meldete zwei Fehler beim Laden des SAF-Quellmodells `msf%3A1000012405`: `/proc/self/fd/123` nicht vorhanden/nicht lesbar; Mali-G57 wurde erkannt, kein RAM-Engpass.
- **08.11.2026:** Der Nutzer meldete, dass der Ladevorgang anschließend `MODEL_LOAD_STARTED` und 70 % erreichte; danach Verbindungsabbruch des Dienstes bei ungefähr 4,3 GB Native Heap und 274 MB verfügbarem RAM. Die CPU-Auswahl lud 0/37 Layer auf die GPU; Recovery startete erneut, ein Abschluss wurde nicht bestätigt.
- **09.11.2026:** Der Nutzer bestätigte ausdrücklich, dass die App auf dem nubia Z2462N sowohl mit CPU als auch Vulkan lief. Das ist ein positiver physischer Gerätetest und widerlegt die pauschale Einstufung „keine Gerätetests des Nutzers“. Er ersetzt nicht automatisch die noch offenen Abnahmen für Modellfamilien, Kernel, Recovery und Mehrfachzyklen.
- **14.11.2026:** Die aktuelle TODO enthält bereits konkrete Mali-G57-/LFM3-Laufzeit- und Kernel-Sweep-Messwerte in P0-02/P0-03. Das sind Daten von realer Hardware; die CPU-/Vulkan-Korrektheits-Gates dieses Laufs scheiterten jedoch auf beiden Backends und sind daher keine bestandene Abnahme der Ausgabekorrektheit.

**Regel zur Statusbewertung:** Eine vom Nutzer ausgeführte Prüfung mit anschließender Rückmeldung zählt als Testnachweis. Wenn der Nutzer für einen konkreten Build/Funktionsumfang keine Probleme meldet oder den Erfolg ausdrücklich bestätigt, ist genau dieser Umfang als nutzerseitig bestanden zu dokumentieren; nicht getestete Randfälle bleiben separat offen. Bereits gemeldete Fehler werden als bekannte Fehler geführt und nicht erneut als „fehlende Testdaten“ etikettiert. Keine pauschale Hochstufung auf vollständige Modell-/Backend-Kompatibilität ohne entsprechenden Testumfang.

- [ ] 🛠️ **AGENT · CODE/CI** — JVM-Logiktests, Host-native Tests, Android-Instrumentation und Emulator-IPC-/Prozesstests getrennt ausführen und Ergebnisse/Artefakte dokumentieren.
- [ ] 📱 **DU · ENDGERÄT** — Die abschließenden Prüfungen auf realer Hardware separat ausführen; CI und Emulator-Ergebnisse nicht als Geräteabnahme verbuchen.
- [ ] 📱 **DU · ENDGERÄT** — Auf nubia Z2462N / Android 16 / ARM64 / Mali-G57 jede Modellfamilie, die unterstützt werden soll, getrennt mit CPU und Vulkan validieren.
- [ ] 📱 **DU · ENDGERÄT** — Ausgabekorrektheit, Prompt-/Template-Korrektheit, Finish-Grund, tatsächliches Offloading und Graph-Node-Zuordnung, Q4_K-Kernel-Auswahl, Abbruch, Prozessabbruch, Wiederherstellung und Speicherbereinigung prüfen.
- [ ] 📱 **DU · ENDGERÄT** — Exaktes Gerät, Build, Modell, Konfiguration und laufbezogene Nachweise dokumentieren. Quellcode-Tests, Emulator-Nachweise und Prüfungen auf realer Hardware klar unterscheiden.
- [ ] 🛠️ **AGENT · CODE/CI** — Modell-Kompatibilitätsstatus nur anhand direkter Nachweise aktualisieren. Keine allgemeinen Performance- oder Produktionsreife-Aussagen allein aufgrund von CI.

**Abnahmekriterium:** Fehler der eigenständigen Engine sind entweder behoben und verifiziert oder anhand von Nachweisen ausdrücklich als nicht unterstützt/ungeklärt eingestuft.

---

## P2 — Code und Architektur refaktorisieren, nachdem die Korrektheit stabil ist

Strukturelle Änderungen müssen das Verhalten erhalten. Jedes Refactoring einzeln buildbar/testbar halten und von Verhaltensänderungen trennen.

### P2-01 — Tiefe ViewModel-Vererbungskette durch Komposition ersetzen

**Zusätzlicher Quellcode-Befund (15.11.2026):** `ChatViewModel.sendMessage()` erstellt jeden Request korrekt aus dem vollständigen Gespräch im Arbeitsspeicher (`system` + alle bisherigen User-/Assistant-Nachrichten + die neue User-Nachricht). `LocalLlmEngineImpl.streamChat()` leitet die Rollen und Inhalte in ihrer Reihenfolge an JNI weiter. Der native Prompt-Pfad darf den ältesten Gesprächsverlauf nur dann entfernen, wenn die Modell-Context-Grenze es erfordert. Das Chat-Transkript ist ein einfaches `MutableStateFlow`, das dem ViewModel gehört; eine dauerhafte Speicherung/Wiederherstellung von Sessions ist in diesem Pfad nicht vorhanden. Es handelt sich während der Lebensdauer des ViewModels um echten Multi-Turn-Context, aber nicht um eine persistente Gesprächs-/Session-Funktion. Bei der Refaktorierung müssen die eindeutige Zuständigkeit für Chat/Session und ein Regressionstest für den geordneten Aufbau von Multi-Turn-Requests berücksichtigt werden. Nicht mit der separaten Arbeit zur Modellausgabe-Korrektheit in P0-01 vermischen.

- [ ] 🛠️ **AGENT · CODE/CI** — Die bestehende, bildschirmseitige `EngineViewModel`-Fassade zunächst beibehalten; Funktionsverhalten hinter zusammengehörige interne Controller verschieben, die von der Fassade zusammengesetzt werden.
- [ ] 🛠️ **AGENT · CODE/CI** — Chat/Session, Modellauswahl/-laden, expliziten Import, Einstellungen, Diagnostik/Benchmarks und Engine-Lebenszyklus trennen.
- [ ] 🛠️ **AGENT · CODE/CI** — Genau eine Engine-Instanz und einen anwendungsweiten Besitzer beibehalten; keine Engine pro Bildschirm erzeugen.
- [ ] 🛠️ **AGENT · CODE/CI** — Eindeutige Zuständigkeit für Zustände sowie einen einseitigen State-/Event-Fluss definieren. Vererbungsbedingte Zustandsweitergabe und veraltete Imports/Kommentare entfernen.
- [ ] 🛠️ **AGENT · CODE/CI** — Tests für Start-/Wiederherstellungsreihenfolge, gemeinsame Zuständigkeit, funktionsübergreifende Sperren, Navigation und Lifecycle-Close-Verhalten ergänzen.

**Abnahmekriterium:** Es gibt keine tiefe Vererbungskette zwischen Features mehr. Zuständigkeiten sind eindeutig und Aufrufstellen bleiben kompatibel.

### P2-02 — IPC-Client aufteilen

**Befund:** `InferenceClient.kt` umfasst ungefähr 1.455 Zeilen bzw. 70 KB und vereint Binding, Binder-Callbacks, PID/Liveness, Reconnect, Modell-Wiederherstellung, Serialisierung von Ladevorgängen, Streams, Watchdogs und Abbruch.

- [ ] 🛠️ **AGENT · CODE/CI** — `InferenceClient` als Fassade beibehalten und gleichzeitig zusammengehörige Komponenten für Binding, Prozesslebenszyklus, Recovery, serialisierte Modelloperationen sowie Stream-/Timeout-Verarbeitung auslagern.
- [ ] 🛠️ **AGENT · CODE/CI** — Einen eindeutigen Besitzer für Binder-Referenzen, aktive Request-IDs, Jobs, Mutexes, Callbacks und Snapshots geladener Modelle definieren.
- [ ] 🛠️ **AGENT · CODE/CI** — Veraltete Callbacks, alte PIDs, wiederholte terminale Callbacks und Schließen während eines Reconnects ausdrücklich modellieren.
- [ ] 🛠️ **AGENT · CODE/CI** — AIDL und öffentliche API stabil halten; die P0-Regressionstests unverändert übernehmen.

**Abnahmekriterium:** IPC-Verantwortlichkeiten sind unabhängig testbar, ohne doppelte veränderliche Zustandsverwaltung.

### P2-03 — Native JNI-Implementierung nach Verantwortlichkeiten aufteilen

**Befund:** `local_llm_engine_jni.cpp` umfasst ungefähr 2.384 Zeilen bzw. 104 KB.

- [ ] 🛠️ **AGENT · CODE/CI** — Engine-/Backend-Lebenszyklus, Modellladen, Prompt/Template/Cache, Inferenz/Decode, Metriken/Diagnostik und Test-Hooks in gezielte native Dateien aufteilen.
- [ ] 🛠️ **AGENT · CODE/CI** — Besitz und Synchronisierung von `EngineInstance` eindeutig halten; gemeinsame Hilfsfunktionen in schmalen internen Headern bündeln.
- [ ] 🛠️ **AGENT · CODE/CI** — Alle JNI-Exports und Java-Native-Deklarationen erhalten. Keine C++-Exception darf JNI überqueren.
- [ ] 🛠️ **AGENT · CODE/CI** — Native Utility-Tests und Grenzfalltests für Laden, Inferenz, Abbruch und Zerstörung beibehalten. Das llama.cpp-Submodul muss unverändert bleiben.

**Abnahmekriterium:** Dieselbe JNI-Grenze und dasselbe Laufzeitverhalten; nativer Besitz und Bereinigung sind leichter zu prüfen.

### P2-04 — CMake und generierte Android-Overlays modularisieren

**Befund (15.11.2026):** Die ursprüngliche Root-`CMakeLists.txt` hatte 1.707 Zeilen bzw. 91.953 Bytes. Commit `7440933` reduziert sie auf einen 27-zeiligen Orchestrator und verschiebt die bestehenden Konfigurations-/Overlay-/Target-Blöcke unverändert in sechs zuständigkeitsbezogene Module unter `cmake/`.

- [x] Android-Toolchain-Flags/ABI-Auswahl, Upstream-Build-/Vulkan-Konfiguration, Android-Vulkan-Kompatibilität, Graph-/GDN-Diagnostik, native Target-Verdrahtung und Prüfung von Scheduler-Races in fokussierte CMake-Module aufteilen. Die Konfiguration des Vulkan-GPU-Profilers bleibt im Android-Vulkan-Modul.
- [x] Die Root-`CMakeLists.txt` als reine, geordnete Orchestrierung beibehalten. Bestehende CMake-Funktionskörper, Targets, Compiler-Flags und ABI-Einstellungen bei der Auslagerung erhalten.
- [x] Fail-fast-Upstream-Anker und ausschließlich im Build-Verzeichnis erzeugte Dateien beibehalten. Das festgelegte llama.cpp-Submodul bleibt unverändert.
- [x] CMake-Konfiguration/-Build und APK-Paketierung für ARM64 und x86_64 verifizieren. Lauf `43679806820` bestand sowohl `configureCMakeDebug[arm64-v8a]`, `configureCMakeDebug[x86_64]`, `buildCMakeDebug[arm64-v8a]` als auch `buildCMakeDebug[x86_64]`. Danach wurden die installierbare Debug-APK und die Instrumentation-APK erstellt; auch die Emulator-Prozessisolation-Instrumentation war erfolgreich. Artefakte: Debug `14148461029`, Instrumentation `14148281622`. Die Prüfung des festgelegten llama.cpp-Submoduls war erfolgreich.

**Abnahmekriterium:** Gleiche Ausgaben/Compiler-Konfiguration mit kleineren, leichter überprüfbaren CMake-Komponenten; das Submodul bleibt unverändert.

### P2-05 — Diagnostik-Implementierung aufteilen

**Befund (15.11.2026):** `EngineDiagnostics.kt` ist jetzt eine 143-zeilige API-Fassade. Speicherung/Rotation/Zusammenführung mehrerer Prozesse/Export, Metrik-Parsing und Aufbau kompakter Berichte liegen in separaten Komponenten.

- [x] Den Standard-/Release-Event-Vertrag beibehalten: Normale Betriebs-/Release-Diagnostik enthält keine Prompts, Dokumentinhalte, Token-Teilstücke oder Modellantworten. Die separat aktivierbare vollständige Textdiagnostik bleibt bis zur Prototyp-Fehleranalyse in Debug-Diagnosebuilds verfügbar; vor Play-Store-Veröffentlichung gilt das gesonderte Release-Gate.
- [x] Speicherung der Prozesslogs, Rotation pro Prozess, deterministische Zusammenführung prozessübergreifender Logs und verifizierten URI-Export in `EngineDiagnosticsStorage` auslagern.
- [x] Parsing nativer/Kotlin-Token-Steps, Vulkan-Operationen und Zeit-/Perzentil-Hilfsfunktionen in `EngineDiagnosticsMetrics` auslagern.
- [x] Direkte Parser-Tests für aktuelle und ältere Sync-Felder, Run-Zuordnung, Vulkan-Metriken und fehlerhafte Zeilen ergänzen. Bestehende Android-Prozess-Instrumentation prüft weiterhin die Dateispeicherung im App-Prozess.
- [x] Aufbau kompakter Berichte, Ressourcenaggregation und UTF-8-Exportbegrenzung in `EngineDiagnosticsReportBuilder` verschieben. `EngineDiagnostics` bleibt die API-Fassade (nach dem Auslagern 143 Zeilen).
- [x] Begrenzte Kotlin-Diagnose-Event-Hüllen der Version 1 mit prozesslokalen Event-IDs, PID und automatischer Zuordnung zu Run/Request definieren. Event-Details auf 4.096 Zeichen begrenzen und mehrzeilige Werte normalisieren; Legacy-/Native-Parsing für historische und native Formate beibehalten. Tests für Run-/Request-Zuordnung, Test-PID 0/unbekannt, Detailbegrenzung und Speicherung in einer einzelnen Zeile ergänzen.

**Abnahmekriterium:** Zusammengehörige Diagnostik-Komponenten mit begrenzten, datenschutzfreundlichen Exporten und unabhängigen Tests.

### P2-06 — Demo- und Bibliothekspakete nach Zuständigkeit organisieren

**Befund (15.11.2026):** Die App besitzt jetzt eigene Pakete für `navigation`, `chat`, `modelimport`, `settings` und `diagnostics`. Screens, `AppTab`, Feature-ViewModels, Modellbibliothek-/Import-Zustand, Lifecycle-/Benchmark-ViewModels sowie Benchmark-Pläne/-Policies wurden in den Commits `f76e6f5` und `964ba94` verschoben. Das gemeinsame `EngineViewModel` bleibt die App-Fassade. Der Namespace `com.example` und die interne Paketorganisation der Bibliothek sind noch offen.

- [x] Demo-Chat, Modellbibliothek/-import, Einstellungen, Diagnostik/Benchmarks und Navigation in Feature-Pakete aufteilen. Screens/Navigation in `f76e6f5` verschoben; Progress-/Model-Card-Imports in `e8944f4` und `7365270` korrigiert; ViewModels/Pläne in `964ba94` verschoben. JVM-Tests in `43679806775` bestanden; Android-APK-Build und Emulator-Prozessisolation-Instrumentation in `43679806820` bestanden (Artefakte `14148461029` und `14148281622`).
- [ ] 🛠️ **AGENT · CODE/CI** — `com.example` erst ersetzen, nachdem Manifeste, generiertes BuildConfig, Instrumentation, Saved State und Deep Links geprüft wurden.
- [ ] 🛠️ **AGENT · CODE/CI** — Interne Bibliothekskomponenten nach Lifecycle, Modellquelle, Speicher-/Ressourcen-Policy, Diagnostik und IPC organisieren; verbraucherseitige Typen getrennt halten.
- [x] Abhängigkeitsrichtung `:app → :llm-engine` erzwingen. Das Gradle-Bibliotheksmodul hängt nur von Android Core/Coroutines ab und besitzt keine Abhängigkeit zu App/UI/Benchmark/DMS-Modulen.

**Abnahmekriterium:** Paket- und Modulgrenzen bilden Zuständigkeiten ab; die Bibliothek bleibt unabhängig nutzbar.

## P3 — Bereinigung und technische Leitplanken

### P3-01 — Optionale und veraltete Funktionen erfassen

- [ ] 🛠️ **AGENT · CODE/CI** — Jede öffentliche API, jede `ModelSource`-Variante, UI-Aktion, Build-Flag, native Diagnose-Schnittstelle und jeden Benchmark-Sweep mit Besitzer, Aufrufern, Tests und Wartungsaufwand erfassen.
- [ ] 🛠️ **AGENT · CODE/CI** — Funktionen als Engine-Kern, Demo-only, Diagnose/Test-only, optional oder veraltet klassifizieren.
- [ ] 🛠️ **AGENT · CODE/CI** — Fehlende Resolver-Tests für `AssetPath`/`RemoteUrl` ergänzen oder deren bewusste Entfernung vorschlagen. Keine nur halb unterstützten Funktionen behalten.
- [ ] 🛠️ **AGENT · CODE/CI** — Abhängigkeiten, Berechtigungen und Code erst entfernen, wenn Referenz-/Test-/Dokumentationssuchen ihre Entbehrlichkeit belegen. Öffentliche APIs dürfen nur nach ausdrücklicher Entscheidung entfernt werden.

### P3-02 — Tests bereinigen, ohne ihr Verhalten zu verändern

- [ ] 🛠️ **AGENT · CODE/CI** — Wiederverwendbare Fake-Binder-/Prozess-Fixtures mit explizitem Reset und klarer Zuständigkeit auslagern.
- [ ] 🛠️ **AGENT · CODE/CI** — Große IPC- und Diagnostik-Testsuiten nach Zuständigkeit aufteilen. Zuerst Testmethoden unverändert verschieben; Assertions nur in einem separaten, konkreten Fehlerbehebungs-Commit ändern.
- [ ] 🛠️ **AGENT · CODE/CI** — Reine Logik in JVM-Tests, Aussagen über Prozesse/Binder in `androidTest` und Verhalten nativer Hilfsfunktionen in Host-C++-Tests halten.
- [ ] 🛠️ **AGENT · CODE/CI** — Tatsächlich ungenutzte Imports und Überreste des Auslagerns mit compilerbewusster Analyse entfernen, nicht allein anhand einer Textsuche.

### P3-03 — Architektur- und Build-Leitplanken ergänzen

- [ ] 🛠️ **AGENT · CODE/CI** — Automatisierte Prüfungen für Modul-Abhängigkeitsrichtung, Lecks öffentlicher APIs und verbotene App-/Compose-/DMS-Referenzen in `:llm-engine` ergänzen.
- [ ] 🛠️ **AGENT · CODE/CI** — Abhängigkeiten, Reproduzierbarkeit des Gradle-Wrappers, NDK-/CMake-Festlegung und saubere rekursive Submodul-Builds überprüfen.
- [ ] 🛠️ **AGENT · CODE/CI** — CI-Pfadfilter korrekt halten; reine Dokumentationsänderungen dürfen keine teuren nativen APK-Jobs auslösen.
- [ ] 🛠️ **AGENT · CODE/CI** — Generierte Overlays und Diagnostik außerhalb versionierter Produktionsquellen halten.

### P3-04 — Dokumentation der eigenständigen Engine aktuell halten

- [ ] 🛠️ **AGENT · CODE/CI** — Architektur-, Modellkompatibilitäts-, Build- und Diagnostik-Dokumentation nach akzeptierten Änderungen aktualisieren.
- [ ] 🛠️ **AGENT · CODE/CI** — Nachweise und Einschränkungen an konkrete Commits, Workflow-IDs, Artefakte und Gerätekonfigurationen binden.
- [ ] 🛠️ **AGENT · CODE/CI** — Sicherstellen, dass es keine zweite aktive Implementierungs-Checklist gibt.

---

## P4 — Integration in myDocAnizer-Mobile (bewusst als letzter Schritt)

**Keine Umsetzungszeit in Anpassungen für die Zielanwendung investieren, solange Korrektheit der eigenständigen Engine oder grundlegendes Refactoring noch offen sind.** Der Integrationsleitfaden darf Anforderungen dokumentieren, ist aber keine aktive Umsetzungswarteschlange.

### P4-01 — Minimalen stabilen Schnittstellenvertrag für die Zielanwendung definieren

- [ ] 🛠️ **AGENT · CODE/CI** — Erst nach Abnahme von P0–P3 Zuständigkeit/Lebenszyklus der Engine, Modellquelle/-identität, Konfiguration, Streaming-Fortschritt/terminalen Events, Abbruch, Diagnostik-Export und Close-Semantik definieren.
- [ ] 🛠️ **AGENT · CODE/CI** — Demo-UI, Benchmark-Orchestrierung, Demo-Speicherung und DMS-Konzepte aus der wiederverwendbaren Bibliothek heraushalten.
- [ ] 🛠️ **AGENT · CODE/CI** — Kompatibilität alter/neuer Parcelable-/AIDL-Strukturen prüfen, einschließlich angehängter Felder, Enum-Fallbacks und fehlerhafter/abgeschnittener Legacy-Daten.

### P4-02 — Tatsächliche Zielanwendung anpassen und verifizieren

- [ ] 🛠️ **AGENT · CODE/CI** — Manifest-Merge der Zielanwendung, nicht exportierten `:inference`-Service, JNI-/Native-ABI-Paketierung, minSdk, Coroutine-Lebenszyklus und Dependency-Auflösung prüfen, ohne `:app` zu importieren.
- [ ] 🛠️ **AGENT · CODE/CI** — Den akzeptierten Engine-Commit in myDocAnizer-Mobile festlegen und den tatsächlichen nachgelagerten Build samt Instrumentation erst in dieser Phase ausführen.
- [ ] 🛠️ **AGENT · CODE/CI** — Exakte Engine-Revision, Consumer-Commit, Build-Ergebnis, Testergebnis und erzeugtes Artefakt dokumentieren. Eine Demo-APK ist kein Integrationsnachweis.

**Abnahmekriterium:** myDocAnizer-Mobile verwendet die eigenständige Engine über einen minimalen, stabilen Schnittstellenvertrag, ohne von der Prototyp-App abhängig zu sein.

---

## Projektweite Definition von „fertig“

- [ ] 📱 **DU · ENDGERÄT** — Die Korrektheit der Modellausgabe besteht für jede als unterstützt ausgewiesene Modellfamilie und jedes Backend.
- [ ] 📱 **DU · ENDGERÄT** — Die Q4_K-Vulkan-Decode-Auswahl und die tatsächliche Ausführung auf Mali-G57 sind belegt oder Einschränkungen der Unterstützung ausdrücklich dokumentiert.
- [ ] 📱 **DU · ENDGERÄT** — Timeouts, Abbruch, Prozessabbruch/Reconnect, Modellladen und Cache-Zustand verhalten sich deterministisch.
- [ ] 🏪 **PLAY-STORE-GATE · VOR VERÖFFENTLICHUNG** — Vor der ersten öffentlichen Veröffentlichung Debug-Textlogging vollständig aus dem Produkt entfernen/deaktivieren oder DSGVO-konform anpassen. Die aktuelle Entwicklungs-/Debug-Diagnostik darf bis dahin für die Fehleranalyse bestehen bleiben.
- [ ] 📱 **DU · ENDGERÄT** — Wiederholte Zyklen auf realen Geräten zeigen keine zurückbleibenden Prozesse/Deskriptoren und kein fortschreitendes Ressourcenwachstum.
- [ ] 🛠️ **AGENT · CODE/CI** — Tiefe Vererbung und übergroße Komponenten mit gemischten Zuständigkeiten wurden ohne Verhaltensregressionen refaktorisiert.
- [ ] 🛠️ **AGENT · CODE/CI** — Jede beibehaltene optionale Funktion besitzt einen Verantwortlichen, Tests und einen nachvollziehbaren Zweck.
- [ ] 🛠️ **AGENT · CODE/CI** — Die Abnahme der eigenständigen Engine erfolgt vor jeglicher Integration in myDocAnizer-Mobile.
- [ ] 🛠️ **AGENT · CODE/CI** — Diese Datei bleibt die einzige aktive Umsetzungswarteschlange.

## Audit- und aktuelle Quellcode-Hinweise

Die Audit-Baseline enthält eine Demo-`:app` und ein wiederverwendbares `:llm-engine`-Modul sowie das festgelegte llama.cpp-Submodul. Große Bereiche mit mehreren Zuständigkeiten sind unter anderem `InferenceClient.kt`, `EngineDiagnostics.kt`, `local_llm_engine_jni.cpp` und `CMakeLists.txt`. Sie sind Kandidaten für Refactoring nach Stabilisierung der Korrektheit, aber allein ihre Größe ist kein Grund, Dateien aufzuteilen.

Das aktuelle Vulkan-Overlay enthält den vorgesehenen Q4_K-Batch-1-Integer-Dot-Selector bereits im Quellcode. Die tatsächliche Hardware-Auswahl und Ausgabekorrektheit sind weiterhin nicht vollständig verifiziert. Der aktuelle Standardwert von `TimeoutConfig` für `abortGracePeriodMs` beträgt 3.000 ms; bei der Analyse eines konkreten Diagnoselaufs muss die tatsächlich wirksame Laufzeitkonfiguration verwendet werden.

Diese Liste ist ein Planungs- und Quellcode-Audit-Snapshot. Ein Punkt darf nur dann als erledigt markiert werden, wenn die genannten Abnahmenachweise vorliegen. Keine Aufgabe allein deshalb als erledigt markieren, weil ein Codepfad oder ein Testname vorhanden ist.
