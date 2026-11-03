# Offene Punkte – Local LLM Engine

Stand: 03.11.2026
Branch: `main`
Audit-/Fix-Basis: `530d51634e993b5f4b2eb159324bbb0a023aa084`

## In dieser Arbeitsrunde korrigiert

- Vulkan-C++-Build: vollständige Vulkan-Hpp-Definition und `<stdexcept>` werden eingebunden, bevor der dynamische Dispatcher bzw. `std::runtime_error` verwendet werden.
- CMake aktiviert Vulkan nur noch, wenn Vulkan-Loader, `glslc`, SPIR-V-Headers und Vulkan-Hpp tatsächlich vorhanden sind.
- Native Modellwechsel laufen transaktional: das bisherige Modell bleibt geladen, bis Modell, Kontext und Sampler der neuen Datei erfolgreich initialisiert wurden; bei einem Fehler werden neue Teilressourcen freigegeben und das alte Modell wiederhergestellt.
- AUTO versucht bei einem fehlgeschlagenen Vulkan-Modell-Load einen CPU-Modell-Load.
- Die maximale Ausgabelänge wird über die tatsächlich generierten Tokens begrenzt, nicht über eine absolute KV-Position. Kontextverschiebungen verändern das Ausgabelimit daher nicht mehr.
- Der native Context-Shift erhält die tokenisierte führende System-/Developer-Prompt-Präfixlänge und verschiebt nur nachfolgende Tokens.
- Temperatur und Top-P sind Request-Parameter; Top-P wird nicht mehr unbemerkt auf den Modellladewert zurückgesetzt.
- Ungültige GPU-Layer-Werte werden bereits in `EngineConfig` abgelehnt; die Einstellungen erklären, welche Werte erst nach Modell-Neuladen gelten.
- Fehler beim nativen Backend-Start lassen einen erneuten Initialisierungsversuch zu.
- Chat-Reset invalidiert und beendet die alte Generierung. Verspätete Events einer alten Generierung können den neuen Chat nicht verändern.
- Chat-Ausnahmen setzen den UI-Generierungsstatus zuverlässig zurück; ein Abbruch wird erst nach Rückmeldung der Engine als abgeschlossen dargestellt.
- Die Modellliste zeigt ausschließlich GGUF-Dateien, die der Resolver tatsächlich unterstützt.
- Android Auto Backup ist für die lokale Engine-Demo deaktiviert, damit Modell- und Diagnoseablage nicht versehentlich in App-Backups aufgenommen werden.

## CI-Verifikation

Letzter verifizierter Stand vor diesen Änderungen:
- Unit Tests #44 waren erfolgreich auf Commit `530d51634e993b5f4b2eb159324bbb0a023aa084`.
- APK Builds #13, #14 und #15 schlugen auf diesem Commit beim Kompilieren von `ggml-vulkan.cpp` fehl:
  - unvollständiger Typ `DispatchLoaderDynamic`
  - fehlende Deklaration von `std::runtime_error`

Der Commit dieser Korrekturen muss durch einen neuen Unit-Test-Lauf und einen neuen nativen APK-Build bestätigt werden. Bis beide Ergebnisse erfolgreich sind, gilt die Engine nicht als build-verifiziert.

## Noch offen – nach Priorität

### P0 – Build und Geräteverifikation
1. ARM64-Vulkan-APK-Build und x86_64-CPU-APK-Build erfolgreich abschließen und APK-Artefakte kontrollieren.
2. APK auf einem realen ARM64-Gerät installieren und Modell laden, Inferenz, Tokenlimit, Abbruch, Context-Shift, Modellwechsel-Rollback und Entladen prüfen.
3. Vulkan-Offloading auf einem realen Gerät verifizieren. Backend-Registrierung allein beweist weder erfolgreiche GPU-Layer-Auslagerung noch Speicherstabilität.
4. Native Inferenz gegen kleine und große GGUF-Modelle einschließlich OOM-/Kontextgrenzfällen testen.

### P1 – Native Sicherheit und Laufzeit
5. AUTO-Fallback auch für Fehler bei der Vulkan-Kontextinitialisierung absichern; derzeit wird der CPU-Retry bereits bei fehlgeschlagenem Vulkan-Modell-Load durchgeführt.
6. JNI-Einstiegspunkte mit einer einheitlichen C++-Exception-Grenze absichern.
7. Modellimport mit Dateigrößenlimit, Speicherplatz-Preflight, Downloadfortschritt und Bereinigung verwaister `.part`-Dateien ergänzen.
8. Netzwerk-Read-Abbruch und Remote-Download-Timeouts auf Android-Geräten verifizieren.
9. GGUF-Prüfung über Magic/Dateigröße hinaus um Header-/Metadaten-Grenzprüfungen ergänzen.
10. Native llama.cpp-Logcat-Ereignisse bei Bedarf zusätzlich in das exportierbare Diagnoseprotokoll übernehmen.
11. Eine echte Inferenz-Deadline/Timeout-API ergänzen.

### P2 – API, Tests und Optimierung
12. Tool-/Function-Rollen und einen definierten Tool-Call-Vertrag ergänzen.
13. JSON-Schema-/Grammar-Constrained Output und weitere Samplingparameter (z. B. Seed) ergänzen.
14. Native/JNI-/Gerätetests aufbauen; JVM-Tests allein verifizieren weder C++-Linking noch tatsächliche Inferenz oder Vulkan.
15. SHA-256 aus dem Import übernehmen, statt importierte Modelle nach dem Laden erneut vollständig zu hashen.
16. Streaming-UI-Updates bündeln und unnötige Scrollanimationen pro Token entfernen.
17. Nicht benötigte Demo-App-Abhängigkeiten prüfen und entfernen; ABI-getrennte APKs sowie Release-Minifizierung messen.
18. `INTEGRATION_MYDOCANIZER.md` gegen das reale Zielprojekt und dessen Gradle-/Kotlin-Versionen erneut verifizieren.

## Testgrenzen

Die JVM-Tests prüfen Konfigurationsvalidierung, Request-Sampling-Grenzen, Diagnoseformatierung, GGUF-Magic und Mapping nativer Abschlussgründe. Sie simulieren keine JNI-Aufrufe. Ein erfolgreicher Unit-Test-Lauf ersetzt daher weder einen nativen Build noch die Geräteverifikation.
