# Offene Punkte – Local LLM Engine

Stand: 03.11.2026
Branch: `main`
App-/Engine-Quellstand für APK Build #30: `aaaef521170cad70ce9c1d36eb8bfd4d6592c880`.

## In den Korrekturläufen umgesetzt

- Vulkan-Hpp: Dispatcher-Typ wird vollständig eingebunden, bevor seine Funktionszeiger verwendet werden.
- Vulkan-Hpp: Der llama.cpp-eigene `VULKAN_HPP_DEFAULT_DISPATCHER` wird vor dem Vulkan-Hpp-Include definiert, damit Header-Inlines nicht auf `vk::defaultDispatchLoaderDynamic` linken.
- Vulkan-Build: CMake aktiviert Vulkan nur bei vorhandenen Vulkan-Loader-, `glslc`-, SPIR-V- und Vulkan-Hpp-Abhängigkeiten.
- Native Modellwechsel sind transaktional: ein fehlerhafter Modellwechsel stellt das zuvor geladene Modell wieder her.
- AUTO versucht nach einem fehlgeschlagenen Vulkan-Modell-Load einen CPU-Modell-Load.
- Das Ausgabelimit basiert auf generierten Tokens und bleibt auch nach Context-Shift korrekt.
- Der führende System-/Developer-Prompt wird beim nativen Context-Shift anhand seiner tokenisierten Präfixlänge geschützt.
- Temperatur und Top-P können pro Inferenzanfrage gesetzt werden.
- Fehler bei der Backend-Initialisierung können erneut versucht werden.
- Chat-Reset invalidiert laufende Generierungen; verspätete Events einer alten Generierung dürfen den neuen Chat nicht verändern.
- Chat-Ausnahmen setzen Streaming- und Generierungsstatus zuverlässig zurück.
- Die Modellliste bietet nur noch GGUF-Dateien an.
- Batch-Allokation wird auf fehlende native Puffer geprüft.
- GPU-Layer-Werte werden validiert und die UI erklärt, welche Einstellungen erst nach Modell-Neuladen gelten.
- Auto Backup der Demo-App ist deaktiviert.
- Der APK-Workflow läuft auf `main` automatisch bei Änderungen an App-/Engine-Code, Gradle-/Toolchain-Konfiguration oder dem Workflow.
- Nicht verwendete Firebase-, App Check-, Room/KSP-, Retrofit-, OkHttp- und Moshi-Abhängigkeiten wurden aus der Demo-App entfernt.
- Importierte Modelle geben die beim Import berechnete SHA-256-Prüfsumme weiter; dadurch entfällt das zweite vollständige Lesen der importierten GGUF-Datei.
- Modellimporte prüfen den verfügbaren Speicher und halten eine Mindestreserve frei; der SAF-/Asset-/Remote-Input wird auch bei einem Fehler vor dem Kopieren geschlossen.
- Chat-Autoscroll wird gebündelt statt pro Token neu animiert.
- Inferenz- und Lifecycle-Methoden reichen Coroutine-Cancellation als CancellationException weiter, statt sie in Result.failure umzuwandeln.
- Das Entladen eines Modells meldet Fehler korrekt, statt bei jedem Ausgang Erfolg in der Demo-App anzuzeigen.
- Die Chat-Oberfläche zeigt den Fortschritt nativer Tokens auch dann, wenn noch kein sichtbarer UTF-8-Text entstanden ist; Teilantworten bleiben bei späteren Inferenzfehlern erhalten.
- Der Diagnose-Export ist zusätzlich direkt über die obere App-Leiste erreichbar und hängt nicht mehr davon ab, dass der Settings-Bildschirm geöffnet werden kann.
- Die obere Titelleiste kennzeichnet den aktiven Bereich (Chat, Modell-Import oder Einstellungen); Tab-Wechsel werden an Auswahl, Render-Anforderung und sichtbarem Settings-Screen protokolliert.
- Importierte Modellquellen geben die bereits beim Streaming berechnete SHA-256-Prüfsumme direkt an ModelInfo weiter; lokale FilePath-Modelle werden weiterhin gehasht.
- Modellimporte reservieren mindestens 128 MiB freien App-Speicher und prüfen den freien Speicher in 16-MiB-Schritten erneut; SAF-/Asset-/Remote-Streams werden auch bei frühem Abbruch geschlossen.
- Der native Samplerzustand (History/Repetition-Penalties) wird bei unabhängigen Requests und Lifecycle-Resets explizit zurückgesetzt.
- Abbruch während eines Modellimports/-ladens stellt den Engine-State wieder her; die Demo-App verschluckt Coroutine-Cancellation nicht.
- Bereits vorhandene SHA-256-importierte Modelldateien werden vor Wiederverwendung vollständig gegen den erwarteten Hash geprüft.
- Modelldateipfade und das vollständige ModelInfo-Objekt werden nicht mehr in Logcat geschrieben.
- Die öffentliche Chat-API unterstützt jetzt auch die native Developer-Rolle.
- Nicht mehr verwendete Dokumenten-Tresor-Resource und Google-Services-Gradle-Altlast entfernt.

## Gerätefehler vom 03.11.2026

- Diagnose vom nubia Z2462N: Qwen3-GGUF mit ca. 2,5 GB lädt über Vulkan; Prompt-Evaluation endet mit Code 0. Der Prozess startet während der nativen Token-Generierung neu. Im exportierten App-Log steht kein regulärer Kotlin-/JNI-Fehler.
- Das Muster spricht für einen nativen Prozessabbruch (z. B. Vulkan-Treiberfehler oder Speicherdruck), beweist die Ursache aber nicht. Der bisherige Standard AUTO + 99 GPU-Layer + Batch 512 + Kontext 4096 ist für einen mobilen Erststart zu aggressiv.
- Neuer Standard: CPU, Kontext 2048, Batch 128, maximal 512 Ausgabetokens. Vulkan/AUTO bleiben manuell auswählbar. Inferenzlogs enthalten nun zusätzlich aggregierte Prozess- und Gerätespeicherwerte.
- Noch zu verifizieren: Chat mit demselben Modell im CPU-Standardprofil; danach Vulkan separat mit reduzierter Layerzahl testen. CI-Erfolg ersetzt diese Geräteprüfung nicht.

## CI-Verifikation

- Unit Tests #60 erfolgreich auf `48f8fb8b2d4d11e96eb350cd823712e10a2c0027`: [Run #60](https://github.com/MrLongNight/Local_LLM_Engine/actions/runs/41643402289).
- Android APK Build #28 erfolgreich auf demselben Commit: [Run #28](https://github.com/MrLongNight/Local_LLM_Engine/actions/runs/41643402174).
- Unit Tests #63 erfolgreich auf `aaaef521170cad70ce9c1d36eb8bfd4d6592c880`: [Run #63](https://github.com/MrLongNight/Local_LLM_Engine/actions/runs/41688075347).
- Android APK Build #30 erfolgreich auf demselben Commit; APK-Artefakt `Local-LMM-Engine-debug-apk` vorhanden (38.445.110 Bytes, Archiv-SHA-256 `0b6920ecf3ce77deaf42f380df9c78be2b9b5164c5ff83f6d0e84a5565e67ea2`), verfügbar bis 17.11.2026: [Run #30](https://github.com/MrLongNight/Local_LLM_Engine/actions/runs/41688075363).
- Unit Tests #62 erfolgreich auf `27966cd62855af7df4a7e744c3ae5c4891d5c6bf`: [Run #62](https://github.com/MrLongNight/Local_LLM_Engine/actions/runs/41687470987).
- Unit Tests #61 auf `47632ee5f4137c17a5c273ed496f5cd52accfc4c`: Kotlin-Compile erfolgreich; ein Log-Snapshot-Test schlug wegen uneinheitlicher Newline-Testdaten fehl: [Run #61](https://github.com/MrLongNight/Local_LLM_Engine/actions/runs/41686101022).
- Android APK Build #29 auf demselben Commit erfolgreich; APK-Artefakt `Local-LMM-Engine-debug-apk` vorhanden (38.444.545 Bytes, Archiv-SHA-256 `f5042ce8a717d276aa058a0600854a1f83936b07d1ebd291232e30d46b33f4c0`), verfügbar bis 17.11.2026: [Run #29](https://github.com/MrLongNight/Local_LLM_Engine/actions/runs/41686101062).
- Die nachfolgenden Änderungen betreffen nur Tests/Dokumentation und verändern die APK-Quelle nicht.
- Ein erfolgreicher Build ist keine Geräteverifikation der Settings-Navigation oder der Inferenz.

## Noch offen – nach Priorität

### P0 – Geräteverhalten und Chat-Ausgabe
1. Settings-Tab auf einem realen Gerät öffnen und per exportiertem TAB_SELECTED/TAB_CONTENT_RENDER_REQUESTED/SETTINGS_SCREEN_VISIBLE-Log den gesamten Navigationspfad prüfen. Der Diagnose-Export ist dafür jetzt zusätzlich direkt in der oberen App-Leiste erreichbar.
2. Chat mit einem realen GGUF-Modell prüfen: sichtbare Token-Ausgabe, nur interne/control tokens, leere Modellantwort, Teilantwort bei Inferenzfehler, Abbruch und Tokenlimit.
3. Neue Progress-Events und Erhalt von Teilantworten nach Inferenzfehlern mit einem echten nativen Modelllauf verifizieren.

### P0 – Build und Geräteverifikation
4. ARM64-Vulkan-APK und x86_64-CPU-APK erfolgreich bauen und beide Artefakte prüfen.
5. APK auf einem realen ARM64-Gerät installieren; Modell laden, Prompt-Prefill, Tokenlimit, Context-Shift, Abbruch, Modellwechsel-Rollback, Entladen und Engine-Schließen prüfen.
6. Vulkan-Offloading und CPU-Fallback auf einem realen Gerät messen. Ein registriertes Backend beweist weder tatsächliches GPU-Offloading noch Speicherstabilität.
7. Modellwechsel auf Geräten mit knappem RAM testen: das transaktionale Laden hält das alte Modell bis zum erfolgreichen Wechsel im Speicher und kann dadurch vorübergehend mehr RAM benötigen.

### P1 – Native Sicherheit und Laufzeit
5. AUTO-Fallback auch für Vulkan-Kontextinitialisierungsfehler absichern; bisher wird nach einem fehlgeschlagenen Vulkan-Modell-Load auf CPU zurückgefallen.
6. JNI-Einstiegspunkte mit einer einheitlichen C++-Exception-Grenze absichern.
7. Netzwerk-Read-Abbruch und Remote-Download-Timeouts auf realen Android-Geräten prüfen; Downloads sind nicht resumierbar.
8. GGUF-Prüfung über Magic/Dateigröße hinaus um Header-/Metadaten-Grenzprüfungen erweitern.
9. Native llama.cpp-Logcat-Ereignisse bei Bedarf zusätzlich in das exportierbare Diagnoseprotokoll übernehmen.
10. Eine echte Inferenz-Deadline/Timeout-API ergänzen.
11. Speicherreserve und Importabbruch mit großen Testdateien und knappem freien Speicher verifizieren.

### P2 – API, Tests und weitere Optimierung
12. Tool-/Function-Rollen und einen definierten Tool-Call-Vertrag ergänzen.
13. JSON-Schema-/Grammar-Constrained Output und weitere Samplingparameter (z. B. Seed) ergänzen.
14. Native/JNI-/Gerätetests aufbauen; JVM-Tests allein verifizieren weder C++-Linking noch tatsächliche Inferenz oder Vulkan.
15. Nicht verwendete UI-/Demo-Funktionen und verbliebene Artefakte weiter bereinigen.
16. ABI-getrennte APKs sowie Release-Minifizierung messen.
17. `INTEGRATION_MYDOCANIZER.md` gegen das reale Zielprojekt und dessen Gradle-/Kotlin-Versionen erneut verifizieren.

## Testgrenzen

Der grüne Unit-Test-Lauf prüft Kotlin-/Robolectric-Pfade und deterministische Hilfsfunktionen. Er simuliert keine JNI-Aufrufe. Ein grüner Unit-Test-Lauf ersetzt daher weder den nativen Build noch die Geräteverifikation.
