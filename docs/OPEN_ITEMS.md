# Offene Punkte – Local LLM Engine

Stand: 03.11.2026
Branch: `main`
Aktueller Korrekturlauf basiert auf `07aed58fc19f58b2bc9db1fb31bd315cb1cff862`.

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

## CI-Verifikation

- Unit Tests #47 liefen erfolgreich auf Commit `07aed58fc19f58b2bc9db1fb31bd315cb1cff862`: [Run #47](https://github.com/MrLongNight/Local_LLM_Engine/actions/runs/41594175452).
- APK Build #16 kompilierte den Vulkan-C++-Quellcode, scheiterte anschließend jedoch beim nativen Linken mit `undefined symbol: vk::defaultDispatchLoaderDynamic`: [Run #16](https://github.com/MrLongNight/Local_LLM_Engine/actions/runs/41594085549).
- Der aktuelle Korrekturlauf verschiebt deshalb die Definition von `VULKAN_HPP_DEFAULT_DISPATCHER` vor den Vulkan-Hpp-Include. Dieser Stand muss noch durch den automatischen APK-Build bestätigt werden.
- Ein installierbares APK und reale Geräte-Inferenz sind weiterhin nicht verifiziert.

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
