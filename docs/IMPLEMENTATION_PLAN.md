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

**Status:** CMake-Aktivierung umgesetzt; CI- und Geräteabnahme ausstehend.

## Phase 2 – Backend-Erkennung und Performance-Messung

**Änderungen:**
- Vulkan-Treiber-/Geräteverfügbarkeit nicht mehr als Beweis für aktives llama.cpp-GPU-Offloading behandeln.
- llama.cpp-/GGML-Backend-Registry und Geräteeigenschaften erfassen.
- Verfügbarkeit, Modell-Layer-Offload und tatsächlich gewähltes Backend als getrennte Zustände ausweisen.
- Native Modelllade- und Inferenzereignisse strukturiert exportieren; keine Prompt-/Antwortinhalte protokollieren.
- Messpunkte getrennt erfassen: Modellladen, Template, Tokenisierung, Prefill, Time-to-first-token, Token-Generierung, Prozess-/Speicherstatus.
- CPU und Vulkan mit identischem Modell, Kontext, Prompt und Ausgabegrenze vergleichen.

**Abnahme:** Ein Export belegt für einen realen Lauf, welches Backend registriert wurde, welche Ausführung tatsächlich gewählt wurde und welche Phase der Engpass ist. Backend-Name allein genügt nicht.

## Phase 3 – Prozessisolation und zuverlässiger Abbruch

**Änderungen:**
- Die bestehende `:inference`-Service-Integration auf echte Android-Bindung, getrennte PID, Fehlerpfade und Lifecycle prüfen.
- CPU-Inferenzabbruch über den offiziellen llama.cpp-Context-Abort-Callback absichern. Die öffentliche llama.cpp-Header-Dokumentation weist darauf hin, dass dieser Callback derzeit nur für CPU-Ausführung wirkt.
- Für Vulkan und blockierte native Operationen Prozessabbruch über den separaten Android-Prozess verwenden.
- Alte PID nach Abbruch überprüfen, Binder-Death/Reconnect und Modellwiederherstellung testen.
- JNI-Aufrufe gegen C++-Ausnahmen absichern; keine C++-Ausnahme darf die JNI-Grenze überschreiten.

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

## Aktueller CI-Ausgangsstand

Ausgangscommit vor diesem Plan: `ed6506302565f02c4896095c8d6f00a778f9f689`.

- Android APK Build #64: erfolgreich.
- Unit Tests #101: erfolgreich.
- Kein erfolgreicher Realgerät-Nachweis für Modellladen, GPU-Offloading, Prozessabbruch oder Performance aus diesen CI-Läufen ableitbar.
