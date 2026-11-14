# Agent Guide — Local_LLM_Engine

- Nur main verwenden; vor jedem Schreibvorgang aktuellen HEAD und Blob-SHAs prüfen.
- Ein fokussierter Commit je freigegebenem Arbeitsschritt; bei CI-Fehlern erst Logs lesen, dann nur die belegte Ursache korrigieren.
- Dieselbe EngineViewModel-Instanz bleibt an alle Screens weitergereicht; keine parallelen Engine-Instanzen erzeugen.
- Öffentliche Kotlin-/Java-, AIDL-/Parcelable-, Manifest-, JNI-, Prozess-, ABI- und CMake-Verträge erhalten.
- Keine llama.cpp-Patches, NPU-Backends, Dependencies, DI-Umbauten oder Performance-Default-Änderungen ohne Freigabe.
- Mechanische Extraktionen: Methoden-Körper, Reihenfolge und Verhalten unverändert; notwendige Sichtbarkeitsänderungen separat begründen.
- Es gibt keinen eingecheckten Gradle-Wrapper. CI nutzt Gradle 9.3.1/JDK 21; Java source/target bleibt 17.
- Unit-Workflow prüft JVM-Tests. APK-Workflow baut APK/Test-APK und führt Emulator-Instrumentation aus. APK-Build, Emulator und echte Geräteabnahme getrennt bewerten.
- Immer exakten Code-SHA, Run-ID, Conclusion und Artefakte prüfen. Geräte-/SAF-/Mali-Vulkan-Verhalten nur nach echter Zielgeräteprüfung bestätigen.
- Refactoring-Status wird ausschließlich in docs/REFACTORING_STATUS.md gepflegt. Keine neue Dokumentdatei für kleine Teiländerungen anlegen.
- Vollständige Projekt-Dokumentation liegt unter docs/. README.md bleibt als GitHub-Einstiegsseite im Root.