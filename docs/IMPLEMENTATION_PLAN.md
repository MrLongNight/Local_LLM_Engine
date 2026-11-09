# Umsetzungsplan – Local LLM Engine

**Arbeitsbranch:** `main`  
**llama.cpp-Submodule:** `a4d4f2fd4ec85ec6a0a9ece4870416c1b6cc24f5` (Upstream-HEAD, geprüft am 09.11.2026, 13:48 UTC)  
**Ziel:** Eine nachweislich funktionierende lokale GGUF-Inferenz auf Android mit korrekter CPU-/GPU-Auswahl, kontrolliertem Abbruch und messbarer Performance.

Dieser Plan ersetzt die frühere, widersprüchliche Aufgabenliste. Er ist die einzige zentrale Aufgabenliste dieses Repositories. Ein Punkt gilt erst als erledigt, wenn sein jeweiliger Abnahmetest bestanden ist. Ein erfolgreicher Kotlin-Test oder APK-Build allein gilt nicht als Nachweis echter Geräte-Inferenz.

## Verbindliche technische Referenzen

Das llama.cpp-Git-Submodule ist auf einen konkreten Upstream-Commit gepinnt und enthält die vollständige offizielle Android-Referenzimplementierung:

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
4. Das llama.cpp-Submodule bleibt unverändert. Notwendige Android-/Mali-Kompatibilitätskorrekturen werden als überprüfte Build-Kopie im übergeordneten CMake-Projekt angewendet; Upstream-Dateien werden nicht direkt editiert.
5. Keine parallelen Diagnose-, Statistik- oder Aufgabenlisten. Diagnoseprotokolle dürfen weder Prompts noch Antworten speichern.
6. Die öffentliche `LocalLlmEngine`-API und den Library-Namespace `com.mydocanizer.llm.engine` stabil halten.
7. Priorität: korrekte Inferenz und zuverlässiger Abbruch vor maximaler Tokenrate. Keine NPU-Implementierung ohne konkret unterstütztes Gerät und reproduzierbaren Test.

## Phase 0 – llama.cpp-Upstream aktualisieren und reproduzierbar pinnen

**Umgesetzt in Commit `b345a4f`:** Das Git-Submodule wurde von `67107ac34f62882d8b53ea41ad68010ea635b9be` auf den am 06.11.2026 um 19:51 UTC geprüften Upstream-HEAD `669204ee2b9004845b34f26466a91d003edc98aa` aktualisiert. Der neue Stand enthält 15 Upstream-Commits zusätzlich, darunter Vulkan-ReBAR-Erkennung und die dazugehörige Anpassung der Gerätespeicher-Allokation. Der APK-Workflow prüft exakt diesen SHA.

**Nachfolgendes Upstream-Refresh am 09.11.2026:** Das Submodule wurde auf den aktuellen Upstream-HEAD `a4d4f2fd4ec85ec6a0a9ece4870416c1b6cc24f5` (13:48 UTC) angehoben – 51 Commits neuer als der vorherige Pin. In `ggml-vulkan.cpp` wurden die Änderungen zu RoPE/YARN-Truncation und Argsort-Pipeline-Auswahl übernommen. Der APK-Workflow prüft jetzt exakt den neuen Gitlink-SHA.

Das Android-CMake-Overlay baut weiterhin eine separate Arbeitskopie von `ggml-vulkan.cpp`; der Upstream-Submodule-Inhalt bleibt unverändert. Die Android-API-24-/Mali-Kompatibilitätsanker (drei `vkGetPhysicalDeviceFeatures2`-Aufrufe sowie Include-, Buffer-Device-Address-, 16-bit-storage- und Device-Create-Anker) wurden erneut gegen diesen Upstream-Quellstand geprüft und sind vorhanden. Die CMake-Ersetzungen greifen weiterhin. Der nachfolgende native APK-Build muss die Kompatibilität und Toolchain jetzt erneut bestätigen; ein neuer Upstream-Pin allein belegt noch keine funktionierende Geräte-Inferenz oder bessere Performance.

**CI-Abnahme:** Unit Tests #151 für `b345a4f` und Android APK Build #112 für denselben Commit sind erfolgreich abgeschlossen. Der APK-Workflow baute die konfigurierten ABIs und veröffentlichte sein Debug-Artefakt. Das bestätigt den damaligen Buildpfad, nicht die Ausführung auf dem Zielgerät.

**Noch ausstehende Abnahme:** GGUF-Modellladen mit explizitem Vulkan-Modus auf dem Mali-G57-Zielgerät prüfen und native Vulkan-/GGML-Logs auswerten. Das Versionsupdate und ein grüner CI-Build allein belegen weder stabile Geräte-Inferenz noch bessere Performance.

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

**Prozess-/JNI-Instrumentationstests ergänzt:** `InferenceServiceProcessTest` prüft den echten AIDL-Service, die getrennte Service-PID, Binder-Disconnect, das Verschwinden von `/proc/<pid>`, Rebind und automatische Wiederverbindung des echten `InferenceClient`. Ein zusätzlicher Debug-only JNI-Test-Hook blockiert gezielt im nativen `nativeProcessChat`-Aufruf. Der echte Client-Prefill-Watchdog läuft ab; der Test prüft die Timeout-Fehlermeldung, das Überleben der App-PID, den Tod der ursprünglichen Service-PID, Binder-Disconnect und die Verbindung zu einer Ersatz-PID. CMake/Gradle aktivieren den Hook für Debug und schalten ihn für Release aus.

Zwei weitere Instrumentationstests prüfen den Descriptor-Resolver: ein seekbarer regulärer Dateideskriptor bleibt direkt erhalten, ein Pipe-Deskriptor wird mit einer Diagnose ohne Copy-Fallback abgewiesen. Diese beiden Tests rufen den Resolver jedoch direkt im Testprozess auf; der Binder-Transfer eines echten SAF-Descriptors in den `:inference`-Prozess und reale Provider bleiben offen. CI führt sechs Instrumentationstests auf einem Android-36-x86_64-Emulator aus.

**Grenze dieser Emulator-Abnahme:** Der blockierte JNI-Test lädt absichtlich kein GGUF-Modell. Er prüft daher weder automatisches Reload derselben SAF-/GGUF-Quelle noch eine erfolgreiche Folgeinferenz. Außerdem prüft der Test derzeit nicht explizit, dass `PROCESS_ABORT_CONFIRMED` im App-Prozess-Diagnoselog persistiert wurde. Emulator-Ergebnisse belegen keine Treiberfreigabe oder Zielgerätefunktion.

**Noch ausstehend – zwingende Abnahmelücken:**
- **Echte Hardware:** Die bestehenden CI-Instrumentationstests laufen auf einem Android-36-x86_64-Emulator. Den Prozessabbruch und die IPC-Stabilität zusätzlich auf dem Zielgerät (nubia Z2462N, Android 16, ARM64/Mali-G57) prüfen.
- **Timeout-Eskalationsreihenfolge (Implementierung aktualisiert; CI-Abnahme ausstehend):** Der Watchdog fordert jetzt zuerst `cancelInference(requestId)` an und hält die Request-ID während `abortGracePeriodMs` aktiv. Ein terminaler Binder-Callback beendet den Timeout ohne Prozess-Kill; ohne Callback eskaliert der Client gegen die ursprüngliche PID zu `killProcess()`. Neue Robolectric-Tests prüfen beide Zweige einschließlich der tatsächlichen Grace-Period. Die laufenden Unit-/APK-CI-Läufe müssen diese Änderung noch bestätigen; anschließend bleibt die zusätzliche Verifikation auf dem Zielgerät erforderlich.
- **Kill-Nachweis:** Der neue JNI-Blocktest prüft bereits, dass die App-PID bestehen bleibt, die ursprüngliche Service-PID verschwindet und Binder-Disconnect eintritt. Noch explizit nachzuweisen ist, dass `PROCESS_ABORT_CONFIRMED` mit der ursprünglichen PID im fortbestehenden App-Prozess-Diagnoselog persistiert und im Test ausgewertet wird.
- **Wiederherstellung mit echtem Modell:** Nach dem automatischen Reconnect denselben ursprünglichen GGUF-Quellpfad bzw. dieselbe SAF-URI erneut laden und eine Folgeinferenz erfolgreich abschließen. Der aktuelle Test prüft nur Ersatz-PID und `EngineState.Ready`; das genügt nicht als Recovery-Nachweis.
- **Ressourcenfreigabe unter Last:** Mindestens fünf kontrollierte Load-/Inference-/Timeout-/Kill-/Reconnect-Zyklen durchführen. App-PID, alte/neue Service-PIDs, offene Service-Prozesse, Prozess-PSS, Native-PSS, Graphics-PSS sowie – soweit der Mali-Treiber es bereitstellt – Vulkan-Heap-/Budgetwerte vor und nach den Zyklen erfassen. Alte PIDs und verwaiste Service-Prozesse dürfen nicht verbleiben; Speicherwerte müssen nach einer definierten Stabilisationsphase zum Geräte-Ruhewert zurückkehren. Treiberwerte sind separat zu bewerten und nicht aus einem erfolgreichen Prozess-Kill abzuleiten.
- **Messgrenze des Resource-Monitors:** `EngineResourceMonitor` läuft im zu beendenden Service-Prozess und endet beim harten Kill ebenfalls. Er kann daher die Freigabe nach Prozessende nicht selbst bestätigen. Die Nachher-Messung muss von der fortbestehenden App bzw. dem externen Instrumentation-/ADB-Test-Harness kommen.
- CPU-Inferenzabbruch über den offiziellen llama.cpp-Context-Abort-Callback prüfen. Laut öffentlicher Header-Dokumentation wirkt dieser Callback derzeit nur für CPU-Ausführung; der Prozess-Kill bleibt deshalb der backend-unabhängige Fallback.
- JNI-Aufrufe gegen C++-Ausnahmen absichern; keine C++-Ausnahme darf die JNI-Grenze überschreiten.

**Abnahme – alle Punkte müssen gemeinsam erfüllt sein:**
1. Ein echter nativer/JNI-Aufruf ist nachweislich blockiert, der konfigurierte Timeout löst den harten Prozessabbruch aus und die App-PID bleibt am Leben.
2. Die alte Service-PID ist weg, Binder-Disconnect und `PROCESS_ABORT_CONFIRMED` sind nachgewiesen.
3. Der Client verbindet sich automatisch neu, lädt dasselbe GGUF erneut und erzeugt danach eine erfolgreiche Antwort.
4. Mindestens fünf Wiederholungszyklen zeigen keine verwaisten Service-Prozesse und keine dauerhaft anwachsenden Prozess-/Graphics-/Vulkan-Ressourcen.
5. Das Protokoll nennt Geräte-, Android-, ABI-, App-/Commit-, Modell-SHA-, Backend- und Timeout-Informationen, aber speichert keine Prompts oder Antworten.
6. Die gesamte Abnahme läuft zusätzlich auf echter Zielhardware. Emulator-CI bleibt ein notwendiger automatisierter Test, ersetzt diese Geräteabnahme aber nicht.

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
- Die native Chat-Template-Eingabe ließ `common_chat_templates_inputs.enable_thinking` bisher immer auf dem llama.cpp-Default `true`. Das Evora-Qwen3-Modell verlangt laut Modellvorgabe deaktiviertes Thinking. Der neue `InferenceRequest.enableThinking`-Parameter wird bis zur Jinja-Template-Renderung durchgereicht; Standard ist `false`, bei Bedarf kann ein Aufrufer es pro Request einschalten.
- Der Dateiname verweist auf Evora v3. Laut Modellkarte ist dies eine englische Qwen3-1.7B-Basis mit separatem deutschem LoRA-Adapter. Das Modell erwartet definierte Evora-Task-Prompts und JSON-Ausgaben. Die Demo-App sendete stattdessen ihren generischen Assistenten-Systemprompt. Außerdem verwendet der native Renderer standardmäßig `enable_thinking=true`, während die dokumentierte Evora-Promptvorlage Thinking deaktiviert.
- Die Demo-App enthielt bisher ihre UI-Willkommensnachricht als künstliche Assistant-History (System + Begrüßung + Nutzerfrage); beim Reset wurde ebenfalls UI-Text als Assistant gespeichert. Das verfälscht den ersten Prompt. Die Begrüßung wird deshalb außerhalb des Modellkontexts gerendert; ein neuer/geleerter Chat startet mit leerer Gesprächshistorie.
- Konsequenz: Diese Läufe belegen sehr schlechte gemessene Performance, sind aber kein valider Beleg für die allgemeine Textqualität von llama.cpp oder Local LLM Engine. Für einen Engine-Funktionstest ist ein allgemeines Instruct-GGUF zu verwenden; Evora muss mit Adapter, passendem Task-Payload und korrekter Template-Konfiguration getestet werden.

## Historischer CI-Ausgangsstand (vor diesem Plan)

Ausgangscommit vor diesem Plan: `ed6506302565f02c4896095c8d6f00a778f9f689`.

- Android APK Build #64: erfolgreich.
- Unit Tests #101: erfolgreich.
- Kein erfolgreicher Realgerät-Nachweis für Modellladen, GPU-Offloading, Prozessabbruch oder Performance aus diesen CI-Läufen ableitbar.


## Historischer Baseline-Snapshot – 07.11.2026

**Repository-Head:** `main` = `9b455a7471059fadb11a09cf17471afbd05cd67d`.

- **Unit Tests #153:** fehlgeschlagen. 37 Tests liefen, 3 schlugen in `InferenceIpcTest` fehl: `modelLoadTimeoutKillsHungInferenceProcessWithoutInferenceRequestId`, `modelLoadTimeoutNeverKillsReplacementInferenceProcess` und `testMockIpcClientModelLoadingAndChat`. Damit ist die IPC-/Timeout-Logik noch nicht grün abgenommen.
- **Android APK Build #113:** erfolgreich. ARM64-/x86_64-Debug-APK und Instrumentation-Test-APK wurden gebaut; die Emulator-Prozessisolationstests liefen erfolgreich. Das APK-Artefakt wurde von GitHub Actions hochgeladen.
- **Grenze des grünen APK-Laufs:** Die vorhandenen Instrumentationstests beenden und ersetzen einen im Leerlauf gestarteten Service-Prozess. Sie führen keine reale GGUF-Inferenz aus und testen keinen Timeout-Kill aus einem blockierten nativen/JNI-Aufruf.
- **Echte Geräteabnahme:** Für das Zielgerät liegt weiterhin kein verifizierter Testlauf vor. Modellladen, native Inferenz, Timeout-Hard-Abort, PID-Tod, Modell-Recovery nach Reconnect und Vulkan-/Treiber-Ressourcenfreigabe bleiben offen.
- **SAF-No-Copy-Pfad:** Der Service-Resolver prüft `fstat`/Dateityp, `lseek`, `pread` und read-only `mmap`; die Tests für reguläre Datei und Pipe bestehen. Noch offen ist ein End-to-End-Test des per Binder übertragenen echten SAF-Descriptors im `:inference`-Prozess sowie die Abnahme mit lokalen, Cloud- und Pipe-Providern auf Zielhardware.

**Status:** Work in progress. Weder die erfolgreiche APK-Erstellung noch die Emulator-Tests rechtfertigen die Bezeichnung „geräteverifiziert“ oder „produktionsreif“.


## Aktueller CI- und Abnahme-Snapshot – 08.11.2026

**Code-Head der ausgewerteten CI-Läufe #121/#162:** `fec84e66d9e03abdbe522431924fbd987877b709`. Der anschließende Dokumentations-/KDoc-Folgecommit auf `main` ist `5b03d16112083140e362f3d52f25e9eb44c700d7`; seine Läufe #122/#163 sind ebenfalls erfolgreich.

- **Unit Tests #162:** erfolgreich. Die hochgeladenen JUnit-Berichte enthalten 40 Tests, 0 Fehler und 0 übersprungene Tests (37 im Modul `:llm-engine`, 3 im Demo-Modul). Die drei Fehler aus Baseline #153 sind jetzt grün: `modelLoadTimeoutKillsHungInferenceProcessWithoutInferenceRequestId`, `modelLoadTimeoutNeverKillsReplacementInferenceProcess` und `testMockIpcClientModelLoadingAndChat`.
- **Android APK Build #121:** erfolgreich. Native Debug-APK und Instrumentation-Test-APK wurden gebaut; ABI-/Vulkan-Compile-Prüfungen, `-O2`-Prüfung, zusammengeführtes Service-Manifest und APK-Upload waren erfolgreich. Der Emulator meldete 6/6 Instrumentationstests bestanden, 0 übersprungen, 0 fehlgeschlagen.
- **APK-Artefakt:** `Local-LMM-Engine-debug-apk` wurde tatsächlich heruntergeladen und geprüft. Das ZIP enthält `app-debug.apk` (69.337.322 Bytes; SHA-256 `92371ca0d49ad6ed0e6ee332fed5e1ea271a2f84e13df11f2f3f4d968be01f76`). GitHub führt das Artefakt mit 14 Tagen Aufbewahrung; es ist kein Release-APK.
- **Folge-CI für Dokumentations-/KDoc-Commit `5b03d16`:** Unit Tests #163 erneut erfolgreich (40/40, 0 Fehler). Android APK Build #122 ebenfalls erfolgreich; der Emulator-Schritt startete und beendete alle sechs Tests ohne gemeldeten Fehler und `connectedDebugAndroidTest` endete mit `BUILD SUCCESSFUL`. Ein separates Instrumentation-JUnit-Artefakt wird von diesem Workflow nicht hochgeladen. Das APK-Artefakt wurde ebenfalls heruntergeladen und geprüft (69.337.322 Bytes; SHA-256 `94b88285e13120c9b07f583842f9d3dcad513ce8110764d3bb30ef42600f9a7c`).
- **SAF-No-Copy:** Resolver-Code und zwei Instrumentationstests prüfen reguläre seekbare/mmap-fähige Deskriptoren sowie die Ablehnung eines Pipes ohne Copy-Fallback. Der Test läuft im Testprozess, nicht als echter SAF/Binder-Provider-Test im `:inference`-Prozess.
- **Native Hard-Abort:** Der neue Debug-Hook hält `nativeProcessChat` nicht-kooperativ in JNI fest. Der Client-Prefill-Timeout führt im Emulator zum Verschwinden der alten Service-PID, Binder-Disconnect, Fortbestand der App-PID und Reconnect zu einer Ersatz-PID. Der Test prüft weder persistiertes `PROCESS_ABORT_CONFIRMED` noch Modell-Reload und Folgeinferenz.
- **Noch offen:** Timeout-Watchdog muss vor dem Kill kooperative Cancellation plus Grace Period versuchen; echte Modell-Recovery, mindestens fünf Ressourcenzyklen mit app-seitiger Nachher-Messung, echte Zielhardware nubia Z2462N/Android 16/ARM64/Mali-G57 und Vulkan-Layer-/Operation-Offload/Performance bleiben ohne Nachweis.
- **Hardware-Abnahme:** In Repository und diesen Actions-Artefakten liegt kein neuer Zielgerätebericht vor. Die CI-Ergebnisse sind Emulator-/Build-Nachweise und keine Geräteverifikation.

**Gesamtstatus:** Work in progress. Die Unit-Test- und Emulator-CI ist für diesen Commit grün; die verbindliche Geräte- und Ressourcenabnahme ist ausdrücklich nicht abgeschlossen.


## Implementierungsupdate – 09.11.2026

**Code-/Teststand zum Zeitpunkt dieser Aktualisierung:** `main` = `96694bfa405ff15fbfb9093f4d981f0ea8f05fb8` (Timeout-Implementierung plus korrigierte Timeout-Test-Fixture).

- **Manifest geprüft:** `llm-engine/src/main/AndroidManifest.xml` enthält den Service mit `android:process=":inference"`, `android:exported="false"` und `android:isolatedProcess="false"`. Zusätzlich bestätigte der APK-Workflow die eindeutige Registrierung im zusammengeführten APK-Manifest. Der Audit-Hinweis „leere Manifest-Datei“ trifft auf diesen Repository-Stand nicht zu.
- **AIDL geprüft:** `IInferenceService.aidl` ist vorhanden und enthält die aufgerufenen IPC-Methoden einschließlich `initialize`, `cancelInference`, `killProcess`, `getStatus`, `getProcessId` und `shutdown`.
- **Timeout-Pfad:** Der bisher direkte Timeout-Hard-Kill wurde auf cooperative-first umgestellt. Der Client signalisiert kooperative Cancellation, wartet auf einen terminalen Binder-Callback bis zur konfigurierten Grace Period und eskaliert erst danach gegen die ursprüngliche Service-PID. Tests für kooperative Bestätigung ohne Kill und ausbleibende Bestätigung mit anschließendem Kill wurden ergänzt.
- **CI-Status:** Unit Tests Run `42589359555` für Commit `96694bfa405ff15fbfb9093f4d981f0ea8f05fb8` sind erfolgreich abgeschlossen. Der Android-APK-Build Run `42588846000` für Commit `47a9b6a714088e9f1f683441da9b7e1374ba1ac1` läuft noch; dieser Commit enthält dieselbe `:llm-engine`-Implementierung wie der aktuelle Code-Head. Ein grüner APK-/Emulator-Lauf ist noch nicht bestätigt.
- **Weiterhin offen:** Reale GGUF-Wiederherstellung und Folgeinferenz nach einem Timeout-Kill, fünf oder mehr externe Ressourcenzyklen, persistierter Kill-Nachweis in der App-Diagnose, reale Zielgeräteabnahme auf nubia Z2462N/Android 16/Mali-G57, Vulkan-Registry-/Layer-/Operation-Offload-Verifikation und fortsetzbare Downloads.
- **Messgrenze:** Ein Prozess-Kill beendet den `:inference`-Prozess einschließlich seines Resource-Monitors. Vulkan-/Graphics-Ressourcenfreigabe muss deshalb vom fortbestehenden App-Prozess oder einem externen Test-Harness nach dem Kill gemessen werden.

**Status:** Work in progress. Manifest und AIDL sind im Quellstand vorhanden; Timeout-Korrektur ist implementiert, CI- und Geräteabnahme sind noch nicht abgeschlossen.
