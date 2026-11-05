# Umsetzungsplan – Local LLM Engine

**Arbeitsbranch:** `main`  
**Referenz-llama.cpp:** `2da668617612d2df773f966e3b0ee22dc2beef7b`  
**Ziel:** Eine nachweislich funktionierende lokale GGUF-Inferenz auf Android mit korrekter CPU-/GPU-Auswahl, kontrolliertem Abbruch und messbarer Performance.

Dieser Plan ersetzt die frühere, widersprüchliche Aufgabenliste. Er ist die einzige zentrale Aufgabenliste dieses Repositories. Ein Punkt gilt erst als erledigt, wenn sein jeweiliger Abnahmetest bestanden ist. Ein erfolgreicher Kotlin-Test oder APK-Build allein gilt nicht als Nachweis echter Geräte-Inferenz.

## Verbindliche technische Referenzen

Die vendorte llama.cpp-Version enthält bereits eine offizielle Android-Referenzimplementierung:

- `examples/llama.android`: Kotlin-`InferenceEngine`, JNI, GGUF-Metadatenleser, Modellladen, Flow-Streaming und Benchmark-Einstieg.
- `docs/android.md`: Android-Build, GGUF-Laden und Laufzeitvoraussetzungen.
- `docs/build.md`: CPU-, Vulkan-, OpenCL- und Arm-KleidiAI-Buildoptionen.
- `docs/backend/snapdragon/`: Snapdragon-spezifischer OpenCL-/Hexagon-Build- und Ausführungspfad.
- `include/llama.h`: öffentliche C-API, Context-Parameter und Abort-Callback.
- `ggml/include/ggml-backend.h`: Backend-Registry und Geräteabfrage.

Die Referenzimplementierung aktiviert auf ARM64 `GGML_CPU_KLEIDIAI` und `GGML_OPENMP`. In diesem Projekt wird zunächst nur KleidiAI übernommen. OpenMP bleibt bis zu einem kontrollierten Vergleich deaktiviert; die allgemeine Android-Bauanleitung nennt Einschränkungen für OpenMP auf Android, während die Referenz-App es aktiviert. Diese Abweichung wird nicht durch Raten entschieden.

## Regeln für Änderungen

1. Ausschließlich `main`; vor jedem Push aktuellen Head prüfen und den erwarteten Head beim Ref-Update absichern.
2. Änderungen in kleinen, fachlich zusammenhängenden Commits. GitHub Actions sind die Build- und Testquelle.
3. Keine Behauptung über erfolgreiche Geräte-Inferenz ohne echten Gerätetest.
4. Keine Änderungen am vendorten llama.cpp-Code, solange kein konkreter Upstream-Fehler oder notwendiger API-Eingriff belegt ist.
5. Keine parallelen Diagnose-, Statistik- oder Aufgabenlisten. Diagnoseprotokolle dürfen weder Prompts noch Antworten speichern.
6. Die öffentliche `LocalLlmEngine`-API und den Library-Namespace `com.mydocanizer.llm.engine` stabil halten.
7. Priorität: korrekte Inferenz und zuverlässiger Abbruch vor maximaler Tokenrate. Keine NPU-Implementierung ohne konkret unterstütztes Gerät und reproduzierbaren Test.

## Phase 1 – Offizielle Android-Referenz und CPU-Grundlage

**Änderung:** ARM64-CPU-Build an die offizielle llama.cpp-Android-Referenz annähern. KleidiAI wird für `arm64-v8a` aktiviert; x86_64 bleibt ohne KleidiAI. OpenMP bleibt vorerst deaktiviert.

**Abnahme:**
- APK-Workflow baut die ARM64- und x86_64-Bibliotheken erfolgreich.
- CI-Log bestätigt, dass KleidiAI für ARM64 konfiguriert und eingebunden wird.
- APK-Artefakt enthält die erwarteten ABI-Bibliotheken.
- Anschließend echter Gerätetest mit demselben GGUF-Modell und identischem Prompt; CPU-Prefill und Generierung werden getrennt gemessen.

**Status:** CMake-Aktivierung umgesetzt und im ARM64-APK #65 durch vorhandene KleidiAI-Kernel-/Selector-Symbole im nativen ELF bestätigt. Das belegt die Kompilierung, nicht die tatsächliche Auswahl auf dem Gerät.

**Zusätzlicher Build-Befund vom 05.11.2026:** Die APK-Buildlogs #65 und #66 zeigen für beide ABI-Konfigurationen `CMAKE_BUILD_TYPE=Debug`, aber nur `-fno-limit-debug-info` in `CMAKE_C_FLAGS_DEBUG` und `CMAKE_CXX_FLAGS_DEBUG`; das im vorherigen CMake-Code erwartete `-O2` fehlte. Ursache war, dass der Android-NDK diese Flags als normale Variablen setzt und die bisherige CACHE-only-Zuweisung die normalen Variablen nicht überschrieben hat. Die korrigierte CMake-Logik ergänzt deshalb die normale Variable, spiegelt den Wert anschließend in den Cache und bricht die Konfiguration ab, falls `-O2` für C oder C++ fehlt. Abnahme: Der nächste APK-Build muss für beide ABIs im `compile_commands.json` beim JNI-Compile-Eintrag als letzten Optimierungsparameter `-O2` nachweisen; der Workflow bricht bei Abweichung ab.

**Quantisierungsgrenze:** Die KleidiAI-Integration in dieser llama.cpp-Version stellt explizite Q4_0- und Q8_0-Microkernelpfade (sowie F16-Pfade) bereit. Ihre `supports_op()`-Freigabe für quantisierte Matrixoperationen ist auf Q4_0/Q8_0 begrenzt. Ein Q4_K_M-GGUF darf daher nicht als automatisch von diesen KleidiAI-Kerneln beschleunigt betrachtet werden. Für dieses Modell muss die tatsächliche Tensor-/Kernel-Auswahl gemessen werden.

## Phase 2 – Backend-Erkennung und Performance-Messung

**Änderungen:**
- Vulkan-Treiber-/Geräteverfügbarkeit nicht mehr als Beweis für aktives llama.cpp-GPU-Offloading behandeln.
- llama.cpp-/GGML-Backend-Registry und Geräteeigenschaften erfassen.
- Verfügbarkeit, ausgewähltes Gerät und angeforderte Layer-Offload-Anzahl getrennt ausweisen. Eine erfolgreiche Modellauswahl allein wird nicht als Messnachweis für GPU-Ausführung ausgegeben.
- AUTO muss nach einem fehlgeschlagenen Vulkan-Modellladen einen expliziten CPU-only-Ladeversuch starten. Ein expliziter VULKAN-Wunsch darf nicht still auf CPU wechseln.
- Native Modelllade- und Inferenzereignisse strukturiert exportieren; keine Prompt-/Antwortinhalte protokollieren.
- Messpunkte getrennt erfassen: Modellladen, Template, Tokenisierung, Prefill, Time-to-first-token, Token-Generierung, Prozess-/Speicherstatus.
- Generierte Tokens und Tokens/s zählen nur normale Modell-Ausgabetokens; das intern konsumierte EOG-Stop-Token wird separat protokolliert und nicht zur Antwortlänge addiert.
- CPU und Vulkan mit identischem, allgemein einsetzbarem GGUF-Modell, Kontext, Prompt und Ausgabegrenze vergleichen.

**Bereits umgesetzt in Commit 479854a:** Backend- und Geräteauswahl erfolgt jetzt über llama.cpps GGML-Registry. CPU-Modus wird auf das registrierte CPU-Gerät begrenzt. AUTO versucht bei registriertem Vulkan zunächst diesen Backend-Pfad und wiederholt einen fehlgeschlagenen Modell-Load CPU-only. Die Anzeige nennt Vulkan deshalb „selected“ und nicht unbewiesen „active“.

**Zusätzliche Korrektur:** Der native Generator zählte bisher das abschließende EOG-Token in `tokens_generated` und dadurch in Antwortlänge und Tokens/s. Die neue native Änderung prüft EOG vor der Erhöhung und protokolliert das Stop-Token separat.

**Noch ausstehend:** Tatsächliche Layer-/Operation-Ausführung unabhängig belegen und reproduzierbare Geräte-Benchmarks durchführen. Das bisherige Log vom 05.11.2026 ist ein wichtiger Performance-Ausgangswert, aber kein valider Textqualitäts-Test: Das verwendete Evora-Modell ist auf eng definierte JSON-Tasks trainiert und wurde ohne den vorgesehenen deutschen LoRA-Adapter sowie mit abweichendem Prompt-/Thinking-Vertrag betrieben.

**Abnahme:** Ein Export belegt für einen realen Lauf, welches Backend registriert und ausgewählt wurde, welche Layer-Offload-Anforderung konfiguriert war und welche Phase der Engpass ist. Ein Backend-Name allein genügt nicht.

## Phase 3 – Prozessisolation und zuverlässiger Abbruch

**Bereits umgesetzt:**
- AIDL `killProcess()` ist eine `oneway`-Transaktion. Der Client wartet nicht auf eine synchrone Antwort aus dem möglicherweise blockierten Inferenzprozess.
- Der Service ruft vor dem Prozessende weder `engine.close()` noch JNI-Cleanup, Dateilogging oder Job-`join()` auf. Das OS gibt native Ressourcen beim Prozessende frei.
- Der Client wartet auf das Ende der alten PID, eskaliert bei Bedarf mit eigenem Kill und protokolliert `PROCESS_ABORT_CONFIRMED` oder `PROCESS_ABORT_UNCONFIRMED`.
- Der Reconnect verwirft den alten Binder, PID-, Modell- und Fortschrittsstatus sowie das alte `CompletableDeferred`, bevor er neu bindet. Parallele Reconnect-Callbacks werden über einen Guard zusammengeführt.
- Der Test-Fake leitet Prozesslebendigkeit nun aus dem tatsächlichen Kill-Zustand ab, nicht aus einer manuell nachträglich umgeschalteten PID-Variable.

**Noch ausstehend:**
- Echter Android-Instrumentationstest mit zwei unterschiedlichen PIDs und realem Service-Kill/Rebind.
- CPU-Inferenzabbruch über den offiziellen llama.cpp-Context-Abort-Callback. Die öffentliche Header-Dokumentation weist darauf hin, dass dieser Callback derzeit nur für CPU-Ausführung wirkt.
- JNI-Aufrufe gegen C++-Ausnahmen absichern; keine C++-Ausnahme darf die JNI-Grenze überschreiten.
- Nachgewiesene Wiederherstellung eines zuvor geladenen Modells und erfolgreiche Folgeinferenz nach echtem Prozessabbruch.

**Abnahme:** Android-Instrumentationstest mit echtem Service: App-PID bleibt bestehen, Service-PID endet nach Abbruch nachweislich, Reconnect gelingt und eine neue Inferenz ist möglich.

## Phase 4 – Inferenzkorrektheit und Speicherverhalten

**Änderungen:**
- Kontextüberlauf an Nachrichtengrenzen behandeln; System-/Developer-Prompts und die aktuelle Nutzerfrage erhalten. Keine rohe Kürzung am Ende des tokenisierten Gesamtprompts.
- Modellwechsel, Abbruch während des Ladens, Entladen und Engine-Schließen auf konsistente Zustände prüfen.
- GGUF-Import und Modellvalidierung gegen unvollständige/fehlerhafte Dateien absichern.
- Eine zentrale, konsistente Engine-Konfiguration verwenden; Demo- und Library-Defaults dürfen nicht unbemerkt auseinanderlaufen.

**Abnahme:** Tests decken Prompt-Erhalt, Context-Shift, Fehler-/Abbruchzustände, Modellwechsel und begrenzten Speicher ab. Ein echter Gerätelauf bestätigt die Ergebnisse.

## Phase 5 – Zusätzliche Android-Hardwarepfade

**Änderungen erst nach den Phasen 1–4:**
- Snapdragon: offiziellen llama.cpp-OpenCL-/Hexagon-Pfad und Qualcomm-spezifische Möglichkeiten anhand eines konkreten Snapdragon-Geräts evaluieren.
- Andere GPU-/NPU-Plattformen nur ergänzen, wenn ein unterstützter Runtime-/Treiberpfad, passendes Modellformat und reproduzierbarer Geräte-Test vorliegen.
- OpenMP-Variante getrennt gegen den aktuellen ARM64-Build benchmarken.

**Abnahme:** Ein Backend wird nur angeboten, wenn der Build, die Runtime-Erkennung, reale Inferenz und ein reproduzierbarer Leistungs-/Stabilitätsvergleich belegt sind.

## Geräte-Log vom 05.11.2026 – Ausgangsbefund, keine Abnahme

Verwendetes Modell: `evora-qwen3-1.7b-en.Q4_K_M.gguf` (1,107,408,576 Bytes).

- CPU-Lauf: 85 Prompt-Tokens, Decode/Prefill 75,627 ms; Generierung 50,934 ms; 19 native Tokens inklusive des abschließenden EOG-Tokens; gemeldete Rate 0.373 tokens/s.
- Vulkan-Lauf: 110 Prompt-Tokens, Decode/Prefill 39,223 ms; Generierung 41,514 ms; 3 native Tokens inklusive des abschließenden EOG-Tokens; gemeldete Rate 0.072 tokens/s.
- CPU-Konfiguration: 6 Threads, Batch 128, Kontext 2048. Der Systeminfo-String des damaligen Builds nennt NEON und ARM_FMA, aber keinen KleidiAI-Kernel.
- Beide Läufe endeten mit `finishReason=STOP`; im bereitgestellten Ausschnitt ist weder Timeout noch Cancellation protokolliert. Die Textinhalte selbst werden aus Datenschutzgründen nicht protokolliert; daher lässt sich der konkrete Wortlaut aus diesem Log nicht rekonstruieren.
- Der Dateiname verweist auf Evora v3. Laut Modellkarte ist dies eine englische Qwen3-1.7B-Basis mit separatem deutschem LoRA-Adapter. Das Modell erwartet definierte Evora-Task-Prompts und JSON-Ausgaben. Die Demo-App sendete stattdessen ihren generischen Assistenten-Systemprompt. Außerdem verwendet der native Renderer standardmäßig `enable_thinking=true`, während die dokumentierte Evora-Promptvorlage Thinking deaktiviert.
- Die Demo-App enthielt bisher ihre UI-Willkommensnachricht als künstliche Assistant-History (System + Begrüßung + Nutzerfrage); beim Reset wurde ebenfalls UI-Text als Assistant gespeichert. Das verfälscht den ersten Prompt. Die Begrüßung wird deshalb außerhalb des Modellkontexts gerendert; ein neuer/geleerter Chat startet mit leerer Gesprächshistorie.
- Konsequenz: Diese Läufe belegen sehr schlechte gemessene Performance, sind aber kein valider Beleg für die allgemeine Textqualität von llama.cpp oder Local LLM Engine. Für einen Engine-Funktionstest ist ein allgemeines Instruct-GGUF zu verwenden; Evora muss mit Adapter, passendem Task-Payload und korrekter Template-Konfiguration getestet werden.

## Aktueller CI-Ausgangsstand

Ausgangscommit vor diesem Plan: `ed6506302565f02c4896095c8d6f00a778f9f689`.

- Android APK Build #64: erfolgreich.
- Unit Tests #101: erfolgreich.
- Kein erfolgreicher Realgerät-Nachweis für Modellladen, GPU-Offloading, Prozessabbruch oder Performance aus diesen CI-Läufen ableitbar.
