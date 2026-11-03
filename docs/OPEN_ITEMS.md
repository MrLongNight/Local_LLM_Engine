# Offene Punkte – Local LLM Engine

Stand: 03.11.2026
Branch: `main`
Aktueller geprüfter Hauptstand vor dem nächsten Lauf: `59917925941f524bfafee7e324fcc1bee0c0f07a`.

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
- Importierte Modellquellen geben die bereits beim Streaming berechnete SHA-256-Prüfsumme direkt an ModelInfo weiter; lokale FilePath-Modelle werden weiterhin gehasht.
- Modellimporte reservieren mindestens 128 MiB freien App-Speicher und prüfen den freien Speicher in 16-MiB-Schritten erneut; SAF-/Asset-/Remote-Streams werden auch bei frühem Abbruch geschlossen.
- Der native Samplerzustand (History/Repetition-Penalties) wird bei unabhängigen Requests und Lifecycle-Resets explizit zurückgesetzt.

## CI-Verifikation

- Unit Tests #50 sind erfolgreich auf Commit `59917925941f524bfafee7e324fcc1bee0c0f07a`: [Run #50](https://github.com/MrLongNight/Local_LLM_Engine/actions/runs/41595712956).
- Android APK Build #18 ist erfolgreich auf Commit `a4fe58d692319e0e65b6bc9f80843aa9c0b95dd1`: [Run #18](https://github.com/MrLongNight/Local_LLM_Engine/actions/runs/41595339179).
- Der native Build lief für `arm64-v8a` (inkl. Vulkan) und `x86_64` durch. `app-debug.apk` wurde erzeugt und vom Workflow auf Existenz geprüft; Größe laut Runner: 93 MiB.
- GitHub-Artefakt `Local-LMM-Engine-debug-apk`: 38.433.868 Bytes, SHA-256 des Artefakt-Archivs `852962c436da1a1e09f48d6bd77cee60b2975487ee5f698ce2e1da6332e97ea6`, verfügbar bis 17.11.2026.
- Der APK-Build enthält denselben App-/Engine-Quellstand wie der aktuelle Hauptstand zum Zeitpunkt der Erstellung; der spätere Unterschied bis zum Unit-Test-Commit #50 war die Korrektur eines Test-Fixtures.
- Build-Erfolg und Artefaktprüfung ersetzen weiterhin nicht die Installation und Inferenzprüfung auf einem realen Android-Gerät.

## Noch offen – nach Priorität

### P0 – Build und Geräteverifikation
1. ARM64-Vulkan-APK und x86_64-CPU-APK erfolgreich bauen und beide Artefakte prüfen.
2. APK auf einem realen ARM64-Gerät installieren; Modell laden, Prompt-Prefill, Tokenlimit, Context-Shift, Abbruch, Modellwechsel-Rollback, Entladen und Engine-Schließen prüfen.
3. Vulkan-Offloading und CPU-Fallback auf einem realen Gerät messen. Ein registriertes Backend beweist weder tatsächliches GPU-Offloading noch Speicherstabilität.
4. Modellwechsel auf Geräten mit knappem RAM testen: das transaktionale Laden hält das alte Modell bis zum erfolgreichen Wechsel im Speicher und kann dadurch vorübergehend mehr RAM benötigen.

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
