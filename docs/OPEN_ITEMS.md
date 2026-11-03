# Offene Punkte – Local LLM Engine

Stand: 03.11.2026
Branch: `main`
Basis-Commit vor dieser Arbeitsrunde: `7a3922aee9455618a372c26986f111772c5ca592`

## Bereits umgesetzt (Quellcode)

- Native llama.cpp-Engine mit CPU-Backend; Vulkan wird nur gemeldet, wenn das Backend tatsächlich registriert ist.
- GGUF-Modellquellen: lokaler Dateipfad, Android Storage Access Framework, Assets und HTTPS-Download; SAF/Asset/Remote-Import wird in app-private Ablage kopiert und per SHA-256 benannt.
- Native Inferenz mit Streaming, kooperativem Abbruch während Prompt-Auswertung und Token-Generierung sowie nativen Abschlussgründen.
- Modellmetadaten, Architektur, Backend-Auswahl, Temperatur-Override und konfigurierbares Kontext-/Batch-/Thread-/Token-Limit.
- Jinja-Chat-Template wird verwendet, mit Role/Content-Fallback bei nicht initialisierbaren Templates.
- Kontextkürzung erfolgt an Nachrichtengrenzen und erhält System-/Developer-Nachrichten sowie die jüngste Nachricht.
- Neu: app-interne, rotierende Diagnoseprotokollierung für Engine-Start/-Ende, Modellauflösung/-laden, Backend, Prompt-Auswertung, Inferenzmetriken, Abbrüche und Fehler.
- Neu: Export des Diagnoseprotokolls über Android Storage Access Framework in den Einstellungen. Chat-Prompts und generierte Antworten werden nicht protokolliert.
- Neu: zusätzliche JVM-Tests für Logformat/Redaktion, GGUF-Magic-Prüfung, Konfigurationsgrenzen und Native-Finish-Reason-Mapping.

## CI-Verifikation

- JVM/Robolectric-Unit-Tests waren zuletzt auf dem vorherigen Commit `7a3922a` erfolgreich: Run [41572652765](https://github.com/MrLongNight/Local_LLM_Engine/actions/runs/41572652765).
- Der Debug-APK-Build für den vorherigen Quellstand mit Vulkan-Header-Include-Fix ist fehlgeschlagen: Run [41572630303](https://github.com/MrLongNight/Local_LLM_Engine/actions/runs/41572630303).
- Konkreter Linkerfehler im arm64-v8a Native Build: `undefined symbol: vkGetPhysicalDeviceFeatures2`. Die C++-Kompilierung erreicht den Link-Schritt; ein installierbares APK ist damit noch nicht verifiziert.
- Die Änderungen dieser Arbeitsrunde müssen noch durch die anschließende CI-Ausführung verifiziert werden.

## Offen – nach Priorität

### P0 – Build und lauffähiger Prototyp
1. Android-Vulkan-Linkerfehler `vkGetPhysicalDeviceFeatures2` beheben, ohne minSdk/API-24-Unterstützung versehentlich zu brechen.
2. APK-Build für arm64-v8a und x86_64 erfolgreich abschließen und Artefakt verifizieren.
3. APK auf echtem Android-Gerät installieren: Modell laden, Chat-Antwort vollständig generieren, Abbruch testen, Modell entladen und Engine schließen.
4. Vulkan-Verfügbarkeit, tatsächlich ausgelagerte GPU-Layer und CPU-Fallback auf einem realen Gerät messen. Ein registriertes Backend allein beweist noch keinen erfolgreichen Offload.

### P1 – Diagnose, Stabilität und Laufzeit
5. Diagnoseexport auf dem Gerät prüfen: Datei muss sich über SAF speichern lassen und Session-, Modell-, Inferenz- und Fehlerereignisse enthalten.
6. Native llama.cpp-Logcat-Ausgaben bei Bedarf zusätzlich an den Diagnosekanal anbinden. Das neue Protokoll erfasst bereits Kotlin-/JNI-Ergebnis- und Fehlerpfade, aber nicht jeden nativen Logcat-Eintrag.
7. Inferenz-Timeout als API-Funktion ergänzen; aktuell gibt es kooperativen Abbruch, aber keinen garantierten Zeit-Deadline-Mechanismus.
8. Modellimport gegen sehr große Dateien absichern (maximale Dateigröße, freier Speicher, HTTP-Download-Limits/Resume) und echten Abbruch bei blockierendem Netzwerk-Read prüfen.
9. GGUF-Prüfung von Magic/Dateigröße zu robuster struktureller Vorvalidierung erweitern.

### P2 – API und Integration
10. Tool-/Function-Rollen und Tool-Call-Vertrag ergänzen; aktuell gibt es nur SYSTEM, USER und ASSISTANT.
11. Striktes JSON-/Schema-/Grammar-Output und zusätzliche Samplingparameter (z. B. Top-P/Seed pro Request) als öffentliche API ergänzen.
12. Reale Native-/JNI-/Gerätetests ergänzen. JVM-Tests allein verifizieren weder C++-Linking noch native Inferenz, Vulkan, SAF-Dateiauswahl oder Verhalten auf dem Gerät.
13. `INTEGRATION_MYDOCANIZER.md` gegen die tatsächliche Ziel-App erneut abgleichen und die Engine gezielt in `myDocAnizer-Mobile` integrieren. Bis dahin bleibt dies ein eigenständiger Prototyp mit Demo-App.
14. Demo-Modellliste bereinigen: UI listet derzeit zusätzlich `.bin`, während der Resolver ausschließlich GGUF validiert.
15. APK-/Native-Library-Größe und ABI-Aufteilung optimieren.

## Testgrenzen

Die in dieser Arbeitsrunde ergänzten Unit-Tests prüfen deterministische Logformatierung/Redaktion, GGUF-Header-Grenzen, Konfigurationsvalidierung und Mapping der nativen Abschlusscodes. Sie simulieren keine JNI-Aufrufe und ersetzen keinen Native-Build oder Gerätetest.
