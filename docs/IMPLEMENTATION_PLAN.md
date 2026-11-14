> **Dokumenttyp:** Historische technische Implementierungshistorie. Datierte CI- und Hardware-Statusblöcke dokumentieren jeweils ihren damaligen Snapshot. Für aktuelle offene Aufgaben und Freigaben ist `REFACTORING_ROADMAP.md` maßgeblich; für Agent-Regeln `AGENTS.md`. Technische Belege bleiben erhalten und dürfen nicht als Live-Status interpretiert werden.

# Aktueller Stand 12.11.2026 – verifizierter CI-/APK-Stand

**Repository:** `MrLongNight/Local_LLM_Engine` · Branch `main`  
**Commit:** `528e1d34af9b00c3bcd1054fb12862cc09ab0703`

## GitHub Actions – vollständig grün

- **Android APK Build #264 / Run `43238009301`:** erfolgreich. APK und Instrumentation-APK erstellt und hochgeladen. Native ARM64-/x86_64-Optimierungsflags, Vulkan-Quelloverlay, Graph-Backend-Diagnose (direkte TU/Unity-TU), Integer-Dot-Shader und zusammengeführtes Service-Manifest wurden geprüft.
- **Android-36-x86_64-Instrumentation:** 7/7 Tests bestanden, 0 übersprungen, 0 fehlgeschlagen. Der Lauf umfasst die Prozessisolation-/Hard-Abort-/Recovery-Pfade aus dem aktuellen Testumfang. Er ersetzt keine Mali-G57-Geräteabnahme.
- **Unit Tests #344 / Run `43238009122`:** erfolgreich; zusätzlich bestanden die nativen Host-Tests für Prompt-Prefix-Cache und geschützte Prompt-Truncation.
- **pre-commit.ci:** Status `success` auf diesem Commit.
- **Profiler-BuildConfig:** Push-Build explizit mit `VULKAN_PERF_PROFILER_ENABLED=false` verifiziert. Ein separater Diagnosebuild kann über `workflow_dispatch` mit `enable_vulkan_perf_profiler=true` erstellt werden; Release erzwingt weiterhin `false`.
- **Dokumentations-Folgecommit:** Der reine Dokumentationscommit `d5fdb1b` wurde mit Unit-Test-Run `#346` ebenfalls erfolgreich geprüft. Zwischen dem verifizierten Engine-Commit `528e1d3` und `d5fdb1b` wurden keine App-/Engine-Quellen geändert. Die Push-Trigger laufen deshalb künftig nur bei relevanten Quell-/Buildänderungen; Pull Requests bleiben vollständig getestet.
- **Workflow-Änderung:** Commit `fc1d3fc` grenzt den Unit-Test-Push-Trigger auf App-/Engine-Quellen, Build-Konfiguration und Workflows ein. Reine README-/Dokumentationsänderungen lösen keine Android-Testjobs mehr aus. Verifikation: Unit-Test-Run `#347` / `43243673895` auf Commit `fc1d3fc` vollständig erfolgreich; SDK-Setup, App-/Engine-Unit-Tests, native Host-Tests und Report-Upload abgeschlossen.

## Verifiziertes APK-Artefakt

- **Datei:** `app-debug.apk`, entpackt aus GitHub-Artefakt `Local-LMM-Engine-debug-apk` (Artifact ID `13952939090`).
- **Größe:** 79.063.814 Bytes (ca. 75,4 MiB).
- **SHA-256:** `55166f9bb1c17f4f39ff9f01240098f8d0bde49d78d86eefb69c4e7646442c5e`.
- **ABIs:** ARM64-v8a und x86_64; das APK enthält `liblocalllm_engine.so` für beide Architekturen.
- **Manifest:** genau ein privater `:inference`-Service, `exported=false`, `isolatedProcess=false`; CI-Prüfung bestanden.
- **Artefakt-Aufbewahrung:** GitHub meldet Ablauf am 26.11.2026. Der APK wurde zusätzlich aus dem Artefakt geladen und lokal auf ZIP-/APK-Inhalt geprüft. Der verifizierte APK ist ein normaler profilerfreier Debug-Build, keine Release-APK.

## Noch offene Abnahmen – keine CI kann sie ersetzen

1. **Zielgerät:** nubia Z2462N / Android 16 / ARM64 / Mali-G57: echte GGUF-Modellladung und Folgeinferenz mit explizitem CPU- und Vulkan-Modus.
2. **Tatsächlicher Offload:** reale Layerzahl und per-run CPU-/Vulkan-Graph-Node-Verteilung nachweisen; eine Vulkan-Auswahl oder angeforderte Layerzahl allein reicht nicht.
3. **Performance:** profileraktivierter Diagnosebuild zur Operatoranalyse; anschließend profilerfreie CPU-/Vulkan- und Q4_K-/Q5_K-/Q6_K-A/B-Läufe mit gleichem Modell/Prompt und reproduzierbaren Bedingungen.
4. **Ressourcenzyklen:** mindestens fünf Load-/Inference-/Timeout-/Kill-/Reconnect-Zyklen mit app-seitiger Nachher-Messung für PSS, Native-/Graphics-PSS und soweit verfügbar Vulkan-Heap/Budget.
5. **SAF-End-to-End:** echter Binder-Transfer eines seekbaren SAF-Descriptors in den `:inference`-Prozess plus lokale-/Cloud-Provider auf Zielhardware.
6. **Geräte-Profiler:** `LOCAL_LLM_VULKAN_OP_TIMING`-Zeilen und deren runId-Verknüpfung mit `TOKEN_STEP_METRIC` auf dem Mali-G57 nachweisen.

**Bewertung:** Der Code-/CI-Stand ist für Unit Tests, native Build-Prüfungen, APK-Erstellung und Emulator-Prozessisolation grün. Die Engine ist damit noch nicht als auf dem nubia/Mali-G57 geräteverifiziert, performant oder produktionsreif einzustufen.

---

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

Zwei Instrumentationstests prüfen den Descriptor-Resolver: ein seekbarer regulärer Dateideskriptor bleibt direkt erhalten, ein Pipe-Deskriptor wird mit einer Diagnose ohne Copy-Fallback abgewiesen. Diese beiden Tests rufen den Resolver direkt im Testprozess auf; der Binder-Transfer eines echten SAF-Descriptors in den `:inference`-Prozess und reale Provider bleiben offen. Der aktuelle APK-Workflow #181 führt sieben Instrumentationstests auf einem Android-36-x86_64-Emulator aus.

**Aktueller Emulator-Nachweis (APK Build #181, Commit `291882d`):** Der Recovery-Test lädt vor der Fehlerinduktion ein echtes, kleines Q4_K_M-GGUF-Testmodell (aus androidTest-Assets in app-private Cache kopiert) und lädt es nach dem Hard-Abort aus derselben `FilePath`-Quelle im Ersatzprozess erneut. Er prüft explizit die persistierte `PROCESS_ABORT_CONFIRMED`-Meldung für die ursprüngliche PID und schließt danach eine echte CPU-Folgeinferenz mit mindestens einem generierten Token erfolgreich ab. Der native Block-Hook ist Debug-only. Diese Abnahme läuft auf Android-36-x86_64-Emulator; sie ist weder ein SAF-Provider/Binder-End-to-End-Test noch ein Lauf mit dem 2,5-GB-Qwen-Modell oder dem Mali-G57-Treiber.

**Noch ausstehend – zwingende Abnahmelücken:**
- **Echte Hardware:** Die bestehenden CI-Instrumentationstests laufen auf einem Android-36-x86_64-Emulator. Den Prozessabbruch und die IPC-Stabilität zusätzlich auf dem Zielgerät (nubia Z2462N, Android 16, ARM64/Mali-G57) prüfen.
- **Timeout-Eskalationsreihenfolge:** Cooperative-first mit `cancelInference(requestId)`, aktiver Request-ID während `abortGracePeriodMs` und Hard-Kill gegen die ursprüngliche PID nach ausbleibendem terminalem Callback ist implementiert. Die Robolectric-Tests laufen mit Unit Tests #234 (66/66 insgesamt) erfolgreich; APK Build #181 bestätigt außerdem den nicht-kooperativen JNI-Hard-Abort auf dem Emulator. Die Verifikation auf echter Zielhardware bleibt erforderlich.
- **Kill-Nachweis:** Im Emulator-Recovery-Test ist nun auch die persistierte `PROCESS_ABORT_CONFIRMED`-Zeile im Diagnosefile des fortbestehenden App-Prozesses für die ursprüngliche Service-PID eine explizite Assertion. App-PID-Fortbestand, alte PID beendet und Binder-Disconnect werden ebenfalls geprüft.
- **Wiederherstellung mit echtem Modell:** Der Emulator-Recovery-Test lädt dieselbe kleine GGUF-`FilePath`-Quelle nach dem automatischen Reconnect erneut und besteht eine Folgeinferenz, die mindestens ein Token generiert. Offen bleibt die Wiederherstellung eines echten SAF-Provider-Descriptors und des großen Benutzer-GGUF auf dem nubia/Mali-G57.
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

## Vulkan-Q4_K-Decode – Build-Toolchain korrigiert, Gerätepfad noch offen (10.11.2026)

**Historischer Build-Befund:** Im APK-Workflow #175 verwendete der ARM64-Vulkan-Build Ubuntu-`glslc 2023.8`. Diese Shaderc-Version unterstützte `GL_EXT_integer_dot_product` nicht; Upstream deaktivierte dadurch die Q8_1-/Integer-Dot-MMVQ-Shader, obwohl der Vulkan-Backendbuild ansonsten erfolgreich sein konnte. Das war ein nachgewiesener Projekt-Buildfehler, aber noch keine Erklärung für die gesamte Mali-Laufzeit.

**Build-Korrektur bestätigt:** Workflow #181 verwendet den SHA256-geprüften LunarG Vulkan SDK 1.4.363.0. Die explizite Kompilierung von llama.cpps `feature-tests/integer_dot.comp` bestand; der ARM64-Nativbuild bestätigte, dass die generierte Android-Vulkan-Kompatibilitätskopie von `ggml-vulkan.cpp` kompiliert wurde. Das beweist die Shader-Compilerfähigkeit des Builds, nicht dass der Mali-G57-Treiber die Integer-Dot-Erweiterung aktiviert oder llama.cpp den Q4_K-MMVQ-Kernel tatsächlich auswählt.

**Neue Geräte-Messung vom 10.11.2026 (vom Nutzer bereitgestellt, Qwen3-4B-Instruct-2507-Q4_K_M, 62 Prompt-Tokens):**
- CPU: Decode p50 369.176 ms/Token (ca. 2,71 Token/s).
- Vulkan 9/37 Layer: 3.143,8 ms/Token; 19/37: 2.289,5 ms; 28/37: 1.512,2 ms; 37/37: 610,5 ms/Token (ca. 1,64 Token/s).
- Bei 37/37: `decodeSubmit` p50 6,8 ms, `gpuSyncWait` p50 603,6 ms. Der gemessene `llama_synchronize()`-Abschnitt dominiert damit die Tokenzeit.
- Prefill: 62 Tokens in 15,35 s. Das ist ein separater, ebenfalls erheblicher Engpass und darf nicht mit Decode-Tokens/s vermischt werden.

**Wichtige Messgrenze:** Das frühere Feld `gpuSyncWaitUs` misst im JNI-Code die gesamte Dauer von `llama_synchronize()`, nicht isoliert die Ausführungszeit der GPU-Kernels. Es wurde im Code in `backendSyncWaitUs` umbenannt; ältere Exporte werden rückwärtskompatibel interpretiert. Der gepinnte Vulkan-Code beendet dabei den Command Buffer, reicht verbleibende Queue-Arbeit ein, wartet auf Fence-Abschluss (im asynchronen Pfad über wiederholtes `getFenceStatus`-Polling mit Yield) und führt gegebenenfalls ausstehende Speicher-Kopien aus. Die 603,6 ms enthalten also GPU-Ausführungszeit plus Synchronisations-/Treiber-/Transferanteile. Aus diesem Wert allein lässt sich kein reiner GPU-Kernel-Durchsatz ableiten.

**Zwei unterschiedliche Teil-Offload-Engpässe:** Die Zeit `decodeSubmit` innerhalb von `llama_decode()` beträgt bei den Layerprofilen 9/37, 19/37 und 28/37 rund 2.943 ms, 1.927 ms bzw. 1.023 ms pro Token. Bei 37/37 fällt sie auf rund 6,8 ms; dort dominiert anschließend `llama_synchronize()` mit 603,6 ms. Der Upstream-Code bestätigt, dass `llama_context::decode()` `ggml_backend_sched_graph_compute_async()` aufruft und der Scheduler in `ggml_backend_sched_compute_splits()` die Rechengraphen auf CPU-/GPU-Backends aufteilt, Eingabetensoren kopiert, zwischen Backend-Splits gegebenenfalls vorherige Arbeit synchronisiert und die einzelnen Teilgraphen ausführt. Die gemessene `decodeSubmit`-Zeit enthält daher Scheduler-, CPU-Backend-, Split- und Transferaufwand; die Logs trennen diese Anteile aktuell noch nicht. Das macht einen reinen GPU-Kernel-Fix als alleinige Lösung für Teil-Offload unwahrscheinlich.

**Mali-Speicherbudget:** llama.cpp summiert bei integrierten GPUs die relevanten Vulkan-Heaps. Wenn `VK_EXT_memory_budget` nicht verfügbar ist, meldet seine Speicherabfrage als `free` ersatzweise die volle Heap-Größe. Die bisherige Zeile mit `freeBytes == totalBytes == 7.784.226.816` belegt daher keinen aktuell freien GPU-Speicher; ohne explizite Budget-Unterstützung kann es nur der geschätzte Heap-Gesamtwert sein. Die neue Diagnose protokolliert die Budget-Erweiterung und Heap-Nutzung separat.

**Nächste notwendige Messung:**
- Die priorisierte Diagnoseexport-Sektion muss die native Mali-Zeile mit `int dot: 0/1`, Subgroup, FP16, Shared Memory und Matrix Cores sowie llama.cpps tatsächliche Meldung `offloaded X/Y layers to GPU` unverlierbar enthalten.
- Für einen gezielten Profil-Lauf Vulkan-Per-Operation-Zeitstempel (`GGML_VK_PERF_LOGGER`) erfassen und mit dem extern gemessenen Synchronisationsabschnitt vergleichen. So lassen sich Q4_K-MMVQ-/F16-Fallback-Kernel, reale GPU-Ausführungszeit und Fence-/Transfer-Overhead voneinander trennen.
- Den Mali-Treiberpfad erst dann optimieren, wenn die tatsächliche Q4_K-Prefill-/Decode-Kernelwahl (Q8_1 Integer-Dot-MMVQ gegen Basis-/F16/F32-Pfad), die echte Layer-Zuweisung, die Vulkan-Speicherbudget-Unterstützung und GPU-Timestamps belegt sind. Ein grüner CI-Build, Vulkan-Backendname oder angeforderte Layerzahl ist kein Performance-Nachweis.

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


## Vulkan-Per-Op-Profiler – expliziter Build-Opt-in und aktueller Abnahmestand (12.11.2026)

- Das Profiling ist standardmäßig in Debug und Release deaktiviert. `-PlocalLlmVulkanPerfProfiler=true` aktiviert es ausschließlich für einen separaten Debug-Build; Release erzwingt den Wert `false`.
- Die Library übergibt den generierten BuildConfig-Schalter an `NativeEngineHandle.ensureLoaded()`. JNI setzt `GGML_VK_PERF_LOGGER` vor `llama_backend_init()` nur bei diesem expliziten Opt-in und protokolliert den tatsächlichen Zustand. Standard-Benchmarks bleiben profilerfrei.
- Der Android-CMake-Overlay ersetzt `vk_perf_logger::print_timings()` im generierten Vulkan-Quelltext und führt Operatorzeiten über `GGML_LOG_INFO` in das native Diagnoseprotokoll. PID, Graph-Snapshot, Operator und Inferenz-`runId` verbinden die Werte mit den Tokenmetriken; der kompakte Export bildet p50/p90.
- **CI-Nachweis:** Commit `6a4f0d6` (12.11.2026) baut die Debug-APK und Instrumentation-APK erfolgreich. Der CI-Check verifiziert das Vulkan-Overlay, den Graph-Backend-Diagnosecode (direkte TU oder Unity-TU), Integer-Dot-Shader sowie Optimierungsflags. Der Android-36-x86_64-Prozessisolationstest und die Unit Tests sind erfolgreich.
- **Messgrenze:** Timestamp-Queries und deren Readback kosten Laufzeit und Synchronisation. Tokens/s eines profileraktivierten Laufs sind kein Performance-Benchmark. Kernelentscheidungen müssen in einem separaten profilerfreien Lauf wiederholt werden.
- **Weiterhin offen:** Der Per-Op-Profiler muss mit einem expliziten Diagnose-Build auf dem Mali-G57 getestet und seine `runId`-Zuordnung im Export bestätigt werden. Auch die Vulkan-A/B-Ergebnisse und sämtliche Performance-Akzeptanzwerte benötigen reale Messungen auf dem nubia Z2462N; CI/Emulator belegt diese nicht.

### Diagnose-Follow-up – Profiling nach Inferenzlauf trennen

Der erste Mali-G57-Bericht (10.11.2026, 21:59) zeigte, dass der bisherige Vulkan-Profiler zwar Operatorzeiten erfasste, die Zusammenfassung aber alle Graph-Snapshots eines Service-PIDs zusammenfasste. Ein Prozess kann während eines GPU-Layer-Sweeps mehrere Modellkonfigurationen und Inferenzläufe ausführen. Deshalb enthält die Profilzeile jetzt auch die aktive `runId`, die der JNI-Prompt- und Tokenpfad vor jedem Graph-Aufruf setzt. Die Auswertung gruppiert nach PID, runId und Operator.

Zusätzlich werden Kommata im Operator-Deskriptor bei der nativen Ausgabe durch Semikolons ersetzt. Dies verhindert, dass Formen wie `FLASH_ATTN_EXT dst(...)` vom kommagetrennten Logfeldparser abgeschnitten werden. Die GPU-p50/p90-Berechnung verwendet jetzt lineare Interpolation; Regressionstests decken mehrere runIds, Operatorformen mit mehreren Dimensionen und eine Zweipunkt-Stichprobe ab.

**Abnahme ausstehend:** Der neue CMake-/JNI-Pfad muss erneut als ARM64-Debug-APK gebaut werden. Anschließend ist ein kurzer Vulkan-Decode-Lauf auf dem nubia Z2462N erforderlich. Der Export muss für jeden Operator dieselbe runId wie die dazugehörigen `TOKEN_STEP_METRIC`-Zeilen ausweisen. Die vorangegangenen grünen CI-Läufe (#201 APK und #262 Unit Tests) beziehen sich auf den vorherigen Stand ohne diese runId-Erweiterung und gelten nicht als Verifikation dieser Änderung.
