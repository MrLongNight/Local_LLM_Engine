# Offene Punkte – Local LLM Engine

Stand: 05.11.2026
Branch: `main`
Ausgangsstand vor dieser Bereinigungsrunde: `d6ffe05fe055dfff48054e66b896d1ed8bc49d03`.

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

- Die Demo-App hat jetzt genau einen Ort für Modellimport, Modellauswahl sowie Laden/Entladen: den Bereich „Modell-Import“. Doppelte Modellaktionen im Chat wurden entfernt.
- Der Chat bietet nur noch den Engine-Status und einen direkten Wechsel zur Modellverwaltung; der doppelte Gesprächs-Reset wurde entfernt.
- Der Diagnose-Export ist nur noch einmal vorhanden und bleibt über die obere App-Leiste von jedem Bereich aus erreichbar.
- Doppelte bzw. ungenutzte ViewModel-Zustände und Wrapper (importedModelFiles, selectedModelFile, loadSelectedModel, loadExistingModel, clearChat) wurden entfernt; Modelllisten und Ladevorgänge verwenden jeweils einen eindeutigen Zustand bzw. Einstiegspunkt.
- DMS-spezifische Demo-Benennung und der Dokumentenmanagement-Systemprompt wurden neutralisiert (EngineViewModel). Der Namespace der wiederverwendbaren Library bleibt bewusst com.mydocanizer.llm.engine, da er für die geplante Integration vorgesehen ist.
- Nicht referenzierte AI-Studio-/Gemini-Scaffold-Dateien sowie zwei byte-identische temporäre Screenshots entfernt; die verbliebenen Beispiel-Tests ohne fachlichen Prüfwert entfernt, die App-/Engine-Testsuite bleibt bestehen.

## Prozessisolation und IPC – Task-1-Audit

Geprüfter Ausgangsstand: `a754052304db19f8411a3555598016d8758e8943`.

**Status: teilweise umgesetzt, nicht abgeschlossen.**

- Vorhanden: Android-Library-Manifest registriert `InferenceService` mit `android:process=":inference"`, `exported="false"` und `isolatedProcess="false"`. Das ist ein separater Android-Prozess mit derselben App-UID; die gemeinsame UID ist hier für den Zugriff auf app-private Modell-Dateien erforderlich.
- Vorhanden: AIDL-Service-/Callback-Verträge, Parcelable-Deklarationen, asynchrone Request-Callbacks, eine serielle Worker-Queue im Service und ein IPC-Client-Skelett.
- Vorhanden: Der Service erstellt seine `LocalLlmEngine`-Instanz innerhalb des Service-Prozesses. Wird der Service tatsächlich gebunden, läuft diese Instanz dort.
- **Noch nicht produktiv aktiviert:** `LocalLlmEngine.create(context)` verwendet standardmäßig weiterhin `useIpcProcess = false` und erstellt damit `LocalLlmEngineImpl` im aufrufenden Prozess. Die bisherige App-Integration ist daher nicht automatisch prozessisoliert.
- **Noch nicht real nachgewiesen:** `InferenceIpcTest` verwendet einen Fake-`IInferenceService`. Es gibt hier noch keinen Instrumentationstest, der die echte Service-Bindung, unterschiedliche PID von App und Service sowie den nativen Modellbetrieb im Service-Prozess nachweist.
- **Lifecycle-Fehler:** `InferenceService.onDestroy()` startet die Engine-Bereinigung über `serviceScope.launch` und ruft unmittelbar danach `serviceScope.cancel()` auf. Die Cleanup-Coroutine kann dadurch abgebrochen werden, bevor `engine.close()` und `workerExecutor.shutdown()` ausgeführt werden.
- Prozess-Level-Abbruch, Binder-Death-Recovery und Reconnect sind keine nachgewiesenen Eigenschaften dieses Task-1-Stands und bleiben den Folgeaufgaben vorbehalten.

Task 1 darf erst nach Behebung des Service-Lifecycle-Fehlers und einem echten Android-Prozesstest als abgeschlossen gelten. Die Umstellung der produktiven Factory-Aufrufer und der echte Prozessabbruch sind zusätzlich separat zu prüfen.

## Nachaudit Task 1 – zusätzliche Regressionen

Geprüfter Implementierungscommit: `a754052304db19f8411a3555598016d8758e8943`.

- Der Task-1-Commit änderte zwei Dateien unter `llm-engine/src/main/cpp/llama.cpp/ggml/` und löschte vier weitere Vendor-Dateien. Diese Änderungen waren für die IPC-Grundlage nicht erforderlich und werden auf den vorherigen Stand zurückgesetzt.
- Insbesondere entfernte die Änderung in `ggml-vulkan.cpp` den dynamisch aufgelösten `vkGetPhysicalDeviceFeatures2`-Aufruf für Android API 24 und verwendete stattdessen einen direkten Symbolaufruf. Die vorherige API-24-Kompatibilitätsbehandlung bleibt erhalten.
- Der Buildfehler `SPIRV-HeadersConfig.cmake` entstand durch eine neu eingeführte Package-Abhängigkeit im Vendor-CMake. Die ursprüngliche Header-Verzeichnis-Konfiguration genügt; der zusätzliche CMake-Paket-Installationsschritt wird entfernt. Vulkan bleibt für ARM64 aktiviert.
- Der Task-1-Commit änderte außerdem die Engine-Defaults von CPU / Kontext 2048 / Batch 128 / 512 Ausgabetokens / 16 GPU-Layer auf AUTO / 4096 / 512 / 1024 / 99. Das wird auf die zuvor vorhandenen konservativen CPU-Defaults zurückgestellt. Vulkan und manuelle Vulkan-Auswahl bleiben verfügbar.
- Eingabevalidierung für endliche Temperatur-, Top-P- und GPU-Layer-Werte wird wiederhergestellt und mit API-Tests abgesichert.
- Zusätzlich wurden vier bestehende Engine-Tests entfernt. Diese Testlücken sind noch nicht vollständig rückgeführt und müssen nach dem Build-Fix gesondert abgearbeitet werden.

## Nachaudit Task 1 – weitere Befunde

**Task 1 bleibt teilweise umgesetzt.**

- Die App-Integration in `EngineViewModel` verwendet weiterhin `LocalLlmEngine.create(application)`. Da der Factory-Parameter `useIpcProcess` standardmäßig `false` ist, läuft die produktive App-Engine noch im Hauptprozess. Die Factory-API wurde zudem um `useIpcProcess` und `createIpcClient` erweitert und `InferenceServiceClient` ist öffentlich; das widerspricht der Vorgabe, die öffentliche `LocalLlmEngine`-API stabil zu halten. Vor produktiver Aktivierung ist diese API-Ausweitung zurückzunehmen bzw. intern zu kapseln.
- `InferenceServiceClient` verwendet ein einmalig angelegtes `CompletableDeferred`. `onServiceDisconnected` und `onBindingDied` setzen nur einen Fehlerzustand; sie erzeugen keine neue Verbindung/Deferred und starten keinen Reconnect. Nach Prozess-Tod kann `getService()` daher an einer veralteten bzw. unerfüllbaren Verbindung hängen.
- `InferenceService.onDestroy()` startet Cleanup in `serviceScope` und cancelt denselben Scope sofort. `engine.close()` und `workerExecutor.shutdown()` sind damit nicht zuverlässig abgeschlossen.
- Der neue IPC-Unit-Test nutzt einen Fake-Service mit künstlicher PID `99999`. Es gibt keinen Android-Instrumentationstest, der Service-Binding, getrennte App-/Service-PIDs oder native Inferenz im `:inference`-Prozess nachweist.
- `ModelSourceResolver.kt` wurde im Task-1-Commit von ca. 10 KB auf ca. 3 KB reduziert. Der zuvor vorhandene HTTPS-Remote-Download mit Redirect-/Downgrade-Schutz, gestreamtem Import, SHA-256 und GGUF-Headerprüfung wurde entfernt. `ModelSource.RemoteUrl` erzeugt aktuell lediglich einen Dateipfad und lädt keine Datei herunter.
- `EngineDiagnostics.kt` wurde von persistentem, rotiertem und bereinigtem Datei-Logging auf höchstens 500 flüchtige RAM-Einträge reduziert. Dadurch gehen Diagnosen des separaten Inferenzprozesses beim Prozessabbruch verloren und sind aus dem App-Prozess nicht zuverlässig exportierbar.
- Vier bestehende Engine-Tests wurden entfernt (`DiagnosticLogFormatterTest`, `EngineValidationTest`, `GgufHeaderValidatorTest`, `ModelSourceResolverTest`). Die erneute Testabdeckung ist noch nicht vollständig wiederhergestellt.
- Echter Prozess-Level-Abbruch, PID-Ende-Bestätigung, Binder-Death-Recovery und Reconnect sind weiterhin nicht implementiert bzw. nicht nachgewiesen. Sie sind zentrale Folgeaufgaben und dürfen nicht als durch den Service-Skeleton erledigt gelten.

## CI-Verifikation – Nachlauf 03.11.2026

- Unit Tests #69 auf `7e60ece`: fehlgeschlagen, weil `LocalLlmEngineApiTest.testInferenceRequest` 512 erwartete, `InferenceRequest.single()` aber weiter 1024 als Standard lieferte.
- Android APK Build #35 auf `7e60ece`: fehlgeschlagen beim Linken von `liblocalllm_engine.so` für x86_64 (`vkCreateInstance`, `vkEnumeratePhysicalDevices`, `vkDestroyInstance`, `vkGetPhysicalDeviceProperties` nicht aufgelöst). Der JNI-Gerätecheck ruft diese Vulkan-Symbole direkt auf; dem JNI-Target fehlte die Link-Abhängigkeit `vulkan`.
- Die nachfolgende Korrektur stellt beide Request-Token-Defaults auf 512 und ergänzt `vulkan` beim Linken von `localllm_engine`. Die nächsten Actions-Ergebnisse entscheiden über den Status; ein erfolgreicher APK-Build liegt bis dahin nicht vor.

## Gerätefehler vom 03.11.2026

- Diagnose vom nubia Z2462N: Qwen3-GGUF mit ca. 2,5 GB lädt über Vulkan; Prompt-Evaluation endet mit Code 0. Der Prozess startet während der nativen Token-Generierung neu. Im exportierten App-Log steht kein regulärer Kotlin-/JNI-Fehler.
- Das Muster spricht für einen nativen Prozessabbruch (z. B. Vulkan-Treiberfehler oder Speicherdruck), beweist die Ursache aber nicht. Der bisherige Standard AUTO + 99 GPU-Layer + Batch 512 + Kontext 4096 ist für einen mobilen Erststart zu aggressiv.
- Neuer Standard: CPU, Kontext 2048, Batch 128, maximal 512 Ausgabetokens. Vulkan/AUTO bleiben manuell auswählbar. Inferenzlogs enthalten nun zusätzlich aggregierte Prozess- und Gerätespeicherwerte.
- Noch zu verifizieren: Chat mit demselben Modell im CPU-Standardprofil; danach Vulkan separat mit reduzierter Layerzahl testen. CI-Erfolg ersetzt diese Geräteprüfung nicht.

## CI-Verifikation

- Auf dem Audit-Ausgangscommit `a754052304db19f8411a3555598016d8758e8943` schlugen Unit Tests #66 und APK Build #32 fehl. Unit Tests #66: drei Robolectric-Tests wählten wegen fest verdrahtetem `robolectric.dependency.dir=/home/appuser/.m2/repository` den lokalen Resolver auf dem GitHub-Runner. APK Build #32: `find_package(SPIRV-Headers CONFIG REQUIRED)` fand keine Package-Konfiguration, weil nur das Header-Repository geklont, aber nicht als CMake-Paket installiert wurde.
- Build-Fix-Versuch `e886268`: Unit Tests #67 und APK Build #33 scheiterten bereits beim Kompilieren von `app/build.gradle.kts` (`java.io.File` im Kotlin-DSL-Skript nicht aufgelöst).
- Korrektur `2a411b1`: Unit Tests #68 erfolgreich; APK Build #34 scheiterte anschließend beim Auffinden von `SPIRV-HeadersConfig.cmake`, obwohl der Workflow die Package-Konfiguration installiert hatte.
- Der nächste Fix entfernt die unnötige `find_package(SPIRV-Headers)`-Abhängigkeit durch Wiederherstellung der vorherigen Header-Include-Konfiguration; danach müssen Unit- und APK-Actions erneut erfolgreich sein.
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
15. Weitere nicht verwendete UI-/Demo-Funktionen und verbliebene Artefakte prüfen; die erste Bereinigungsrunde vom 05.11.2026 hat die dokumentierten Duplikate und AI-Studio-Scaffold-Dateien entfernt.
16. ABI-getrennte APKs sowie Release-Minifizierung messen.
17. `INTEGRATION_MYDOCANIZER.md` gegen das reale Zielprojekt und dessen Gradle-/Kotlin-Versionen erneut verifizieren.

## Testgrenzen

Der grüne Unit-Test-Lauf prüft Kotlin-/Robolectric-Pfade und deterministische Hilfsfunktionen. Er simuliert keine JNI-Aufrufe. Ein grüner Unit-Test-Lauf ersetzt daher weder den nativen Build noch die Geräteverifikation.
