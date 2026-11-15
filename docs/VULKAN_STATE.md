# VULKAN_STATE — V1 Code-Audit

**Stand:** 15.11.2026  
**Repository:** MrLongNight/Local_LLM_Engine  
**Branch:** main  
**Geprüfter Repository-Stand:** 9361618d1454f30e7df19e2269dff28a069d942f  
**Gepinntes llama.cpp-Submodul:** ggml-org/llama.cpp, Commit a4d4f2fd4ec85ec6a0a9ece4870416c1b6cc24f5

**V1-Modus:** ausschließlich Quellcode- und Dateiprüfung. Keine Code-Fixes, keine Sweeps und keine neuen Geräte-Benchmarks.

---

## 1. Vulkan-Overlay lokalisieren

**Ergebnis: vorhanden und in das native Vulkan-Target eingebunden.**

Fundstellen:

- llm-engine/src/main/cpp/cmake/LocalLlmAndroidVulkan.cmake:4-10 — Funktion local_llm_prepare_android_vulkan(source_dir); sie lokalisiert die Upstream-Datei ggml/src/ggml-vulkan/ggml-vulkan.cpp und liest sie mit file(READ) ein.
- LocalLlmAndroidVulkan.cmake:1023-1027 — die modifizierte Quelldatei wird in das CMake-Build-Verzeichnis unter local-android-vulkan/ggml-vulkan.cpp geschrieben.
- LocalLlmAndroidVulkan.cmake:1141 — der erzeugte Pfad wird als LOCAL_LLM_PATCHED_VULKAN_CPP an den aufrufenden CMake-Scope übergeben.
- llm-engine/src/main/cpp/cmake/LocalLlmUpstreamConfiguration.cmake — ruft local_llm_prepare_android_vulkan auf.
- llm-engine/src/main/cpp/cmake/LocalLlmNativeTargets.cmake:48-64 — ersetzt im Target ggml-vulkan die ursprüngliche Quelldatei ggml-vulkan.cpp durch LOCAL_LLM_PATCHED_VULKAN_CPP.

**Aktueller Code-Ausschnitt (LocalLlmAndroidVulkan.cmake:4-10):**
    set(_vulkan_source "source_dir/ggml/src/ggml-vulkan/ggml-vulkan.cpp")
    ...
    file(READ _vulkan_source _vulkan_contents)

**Bewertung:** Overlay vorhanden; die Build-Verdrahtung kompiliert die erzeugte Kopie. Das festgelegte llama.cpp-Submodul wird durch diesen Overlay-Schritt nicht direkt verändert.

## 2. Q4_K-Decode-Selektion

**Ergebnis: Mali-AUTO erzwingt derzeit ausdrücklich BASE_TYPE_MMV.**

### Upstream-Ausgangsbedingung

Im gepinnten llama.cpp-Commit a4d4f2f:

- ggml/src/ggml-vulkan/ggml-vulkan.cpp:6852 — Upstream aktiviert quantize_y nur, wenn Integer-Dot unterstützt wird, src1 F32 und contiguous ist, y nicht non-contiguous ist, die Alignment-Bedingung erfüllt ist, die Präzision Q8 erlaubt und ggml_vk_should_use_mmvq(...) den MMVQ-Pfad auswählt.

Aktuelle Upstream-Bedingung (Zeile 6852):
    bool quantize_y = ctx->device->integer_dot_product &&
        src1->type == GGML_TYPE_F32 &&
        ggml_is_contiguous(src1) &&
        !y_non_contig &&
        (ne11 * ne10) % 4 == 0 &&
        (swap_inputs || ggml_vk_src1_prec_allows_q8(dst)) &&
        ggml_vk_should_use_mmvq(ctx->device, ne01, ne11, ne10, src0->type);

### Android-Overlay-Bedingung

Fundstellen im Repository:

- llm-engine/src/main/cpp/cmake/LocalLlmAndroidVulkan.cmake:378-420 — Mali-Q4_K-Batch-1-Auswahl und Q8_1-Eignungsbedingungen.
- LocalLlmAndroidVulkan.cmake:386 — local_llm_mali_auto_prefers_mmvq ist ausdrücklich false.
- LocalLlmAndroidVulkan.cmake:389-395 — local_llm_q4k_integer_dot_batch1 hängt von der Eignung, Integer-Dot, Mali-AUTO und dem false-Schalter ab. Für den aktuellen Mali-AUTO-Zweig wird quantize_y = false gesetzt.
- LocalLlmAndroidVulkan.cmake:408-420 — separater expliziter MMVQ-Override (Modus 7), weiterhin nur bei erfüllten Integer-Dot-/Q8_1-Voraussetzungen.
- LocalLlmAndroidVulkan.cmake:441-463 — protokolliert nach Pipeline-Auflösung und Fallback den Pfad LOCAL_LLM_VULKAN_Q4_K_DECODE_PATH mit selected, reason, q8_1Eligible, quantizeY, src1Type, yKernelType und Tensor-Dimensionen.
- LocalLlmAndroidVulkan.cmake:469-478 — der Q8_1-Pipeline-Fallback wird vor dem Rücksetzen von quantize_y protokolliert.

**Aktuelle Auswahlbedingung (LocalLlmAndroidVulkan.cmake:383-395):**
    constexpr bool local_llm_mali_auto_prefers_mmvq = false;
    const bool local_llm_q4k_integer_dot_batch1 =
        local_llm_q4k_q8_1_eligible &&
        ctx->device->integer_dot_product &&
        local_llm_mali_q4k_auto &&
        local_llm_mali_auto_prefers_mmvq;
    ...
    } else if (src0->type == GGML_TYPE_Q4_K && ne11 == 1 &&
               (local_llm_quant_override == 3 || local_llm_mali_q4k_auto)) {
        quantize_y = false;

**Bewertung:** Kein fehlender oder versehentlich nicht erreichbarer Selector gefunden. Die aktuelle Mali-AUTO-Auswahl auf BASE_TYPE_MMV ist eine explizite Quellcodeentscheidung. Der Kommentar im Overlay nennt frühere Messungen mit LFM3 1.7B Q4_K_M: BASE_DMMV 4,14/3,92 tok/s und MMVQ Integer-Dot 2,71/2,80 tok/s. Das ist ein im Code dokumentierter Altbefund, kein neuer V2-Messwert. V1 nimmt keine Änderung an der Auswahl vor.

## 3. Vulkan-Logits-Readback

**Ergebnis: HOST_VISIBLE/HOST_COHERENT-Staging ist bereits vorhanden und wird gemappt wiederverwendet.**

### Buffer-Allokation und Mapping

Fundstellen im gepinnten llama.cpp-Submodul:

- ggml/src/ggml-vulkan/ggml-vulkan-buffers.cpp:53-200 — ggml_vk_create_buffer legt den Vulkan-Buffer und den zugehörigen Speicher an.
- ggml-vulkan-buffers.cpp:191 — mapMemory wird aufgerufen, wenn memory_property_flags eHostVisible enthält. Im Quellcode ist das Vulkan-Hpp device->mapMemory, nicht ein wörtlicher vkMapMemory-Aufruf.
- ggml-vulkan-buffers.cpp:397-407 — ggml_vk_ensure_sync_staging_buffer(ctx, size) erstellt den Staging-Puffer nur, wenn er fehlt oder zu klein ist.
- ggml-vulkan-buffers.cpp:401-405 — angeforderte Properties: eHostVisible | eHostCoherent | eHostCached; Fallback: eHostVisible | eHostCoherent.

**Staging-Allokation (Upstream ggml-vulkan-buffers.cpp:397-405):**
    ctx->sync_staging = ggml_vk_create_buffer_check(ctx->device, size,
        eHostVisible | eHostCoherent | eHostCached,
        eHostVisible | eHostCoherent);

Der wiederverwendete Buffer wird beim Erstellen gemappt; solange seine Größe ausreicht, bleibt der bestehende sync_staging-Buffer erhalten. Ein erneutes Mapping pro Token ist dadurch nicht vorgesehen.

### Logits-Auslesen und Readback

Fundstellen:

- src/llama-context.cpp:1991-1996 — die Logits werden mit ggml_backend_tensor_get_async(...) aus dem Backend-Ergebnis in den Host-seitigen logits.data-Puffer übertragen.
- src/llama-context.cpp:1127-1136 — llama_context::get_logits_ith() gibt anschließend einen Zeiger in diesen Host-Puffer zurück.
- LocalLlmAndroidVulkan.cmake:483-591 — der Overlay-Readback kopiert den Backend-Buffer in sync_staging, stellt die Daten über deferred_memcpy bereit und synchronisiert den Backend-Kontext.
- LocalLlmAndroidVulkan.cmake:522-536 — Map-Zeit wird nur bei fehlendem oder zu kleinem Staging-Buffer gemessen; der wiederverwendete Pfad prüft HOST_VISIBLE, HOST_COHERENT und ptr auf Gültigkeit. Der Kommentar sagt ausdrücklich, dass beim gecachten Buffer kein vkMapMemory pro Token ausgeführt wird.
- LocalLlmAndroidVulkan.cmake:591 — misst die Readback-Sync-Phase.

**Bewertung:** Ein per-Token-Remapping-Bug ist im aktuellen Code nicht erkennbar. Das Mapping kann bei der ersten Allokation oder beim Vergrößern des Staging-Buffers stattfinden; im Folgelauf wird der gemappte Buffer wiederverwendet. Copy und Synchronisation bleiben notwendige Schritte und müssen anhand der V2-Phasenwerte beurteilt werden. V1 belegt weder einen redundanten Wait noch einen sicheren Wait-Entfernungspunkt.

### Diagnose-Build beeinflusst den Sampling-Pfad

Fundstelle: llm-engine/src/main/cpp/local_llm_engine_jni.cpp:1638-1654.

- Ohne LOCAL_LLM_ENABLE_TEXT_DIAGNOSTICS wird für Vulkan die Sampler-Kette über llama_set_sampler(...) beim Vulkan-Kontext registriert.
- Mit LOCAL_LLM_ENABLE_TEXT_DIAGNOSTICS wird diese Registrierung unterlassen. Der Code protokolliert requested=0 und reason=top5_diagnostics_requires_cpu_logits, weil der Top-5-Trace vollständige CPU-sichtbare Logits benötigt.

**Bewertung:** Ein Debug-Diagnoselauf mit aktivierten Text-/Token-Traces misst damit nicht denselben Backend-Sampling-Pfad wie eine normale nicht-diagnostische Vulkan-Inferenz. Das ist für die Interpretation der V2-Performancewerte relevant. V1 ändert diesen Build-Modus nicht.

## 4. Vulkan-Submit- und Fence-Pfad

**Ergebnis: Submit-/Wait-Anzahl ist graph- und zustandsabhängig; eine feste Anzahl pro Decode-Token ist aus dem Quellcode nicht ableitbar.**

Fundstellen im gepinnten llama.cpp-Submodul:

- ggml/src/ggml-vulkan/ggml-vulkan.cpp:902-979 — ggml_vk_submit() baut die Submit-Infos für die Sequenzen und reicht diese mit einem Queue-Submit ein. Bei leerer Sequenz kann mit übergebenem Fence auch ein leerer Submit erfolgen.
- ggml-vulkan.cpp:12984-13045 — Graph-Verarbeitung ruft ggml_vk_compute_forward() an Graph-/Submit-Grenzen auf. Abhängig von serialize_submissions, almost_ready und dem bereits ausstehenden Fence wird mit Haupt-Fence, Almost-ready-Fence oder ohne Fence submitted.
- ggml-vulkan.cpp:13011-13045 — ggml_vk_compute_forward() führt die bedingte Übermittlung aus.
- ggml-vulkan.cpp:13612-13667 — ggml_vk_synchronize() kann einen Compute-Kontext submitten und bei ausstehendem Submit zusätzlich einen Fence-Submit ausführen (leer oder mit Transfer-Semaphor), bevor der Fence-Wait erfolgt.
- ggml-vulkan.cpp:336-367 — ggml_vk_wait_for_fence() wartet gegebenenfalls zunächst auf almost_ready_fence und danach auf den eigentlichen Graph-Fence.
- ggml-vulkan.cpp:4980 — serialize_submissions wird über GGML_VK_SERIALIZE_SUBMISSIONS aktiviert; ohne gesetzte Umgebungsvariable ist dieser Sondermodus nicht aktiv.

Overlay-Fundstellen:

- llm-engine/src/main/cpp/cmake/LocalLlmAndroidVulkan.cmake:95-138 — Zeitmessung für reguläre und leere Queue-Submits.
- LocalLlmAndroidVulkan.cmake:660-730 — Mali-spezifischer Fence-Wait: Für Vendor-ID 0x13B5 wird waitForFences statt des Upstream-getFenceStatus-Pollings verwendet; das Log LOCAL_LLM_MALI_BLOCKING_FENCE_WAIT belegt den gewählten Modus.
- LocalLlmAndroidVulkan.cmake:829-880 — Messung der direkten Compute-Queue-Submits und weiterer Wait-Pfade.
- local_llm_engine_jni.cpp:2192-2196 — die gesamte llama_synchronize()-Dauer wird nach llama_decode() getrennt gemessen.

**Bewertung:** Die Anzahl hängt von Graph-Splits, Almost-ready-Submits, Transfer-Queue und Serialize-Modus ab. Eine feste Anzahl von Queue-Submits oder Fence-Waits pro Decode-Token lässt sich aus dem Code nicht seriös angeben. Die vorhandene Instrumentierung misst Zeit, zählt aber keine Submit-/Fence-Aufrufe je Token. V1 hat keinen eindeutig redundanten Wait und keine eindeutig semantisch sichere Zusammenlegung mehrerer Submits nachgewiesen. Fix C ist durch V1 nicht begründet.

## 5. Sample-Timer

**Ergebnis: sampleUs umfasst Sampler und Diagnose-Top-5, nicht Decode oder anschließenden Fence-Wait.**

Fundstellen in llm-engine/src/main/cpp/local_llm_engine_jni.cpp:

- Zeile 2106 — sample_start_us startet.
- Zeile 2110 — common_sampler_sample(...) wird ausgeführt.
- Zeilen 2120 ff. — in Builds mit LOCAL_LLM_ENABLE_TEXT_DIAGNOSTICS wird log_token_selection_trace(...) im Sample-Zeitfenster aufgerufen.
- Zeilen 568-599 — log_token_selection_trace() liest llama_get_logits_ith() und durchsucht das gesamte Vokabular, um die fünf höchsten Logits zu bestimmen.
- Zeile 2150 — sample_duration_us wird nach Token-ID-Validierung, common_sampler_accept() und EOG-Prüfung berechnet.
- Zeilen 2186-2196 — llama_decode() und llama_synchronize() werden anschließend separat als Decode-Submit und Backend-Sync gemessen.

**Bewertung:** sampleUs enthält common_sampler_sample(), den optionalen vollständigen Top-5-Vokabularscan sowie die anschließende Token-ID-Validierung und Sampler-Accept-/EOG-Verarbeitung. Es enthält nicht den nachfolgenden llama_decode()- oder llama_synchronize()-Abschnitt. Die Map-/Copy-/Sync-Werte in TOKEN_SAMPLE_PHASE_METRIC stammen laut Kommentar und Ablauf aus dem vorigen Token-Decode, dessen Logits jetzt gesampelt werden; sie sind keine Unterphasen des sample-Timers. EOG kehrt vor dem regulären sample_duration_us-Messpunkt zurück.

Damit ist ein hoher sampleUs-Wert im Diagnose-Build nicht automatisch ein Readback-Problem: Der Top-5-Scan selbst kann diesen Wert erhöhen.

---

## Prüfung der zusätzlich genannten früher als abgeschlossen gemeldeten Bereiche

Die folgende Prüfung bestätigt den aktuellen Code-/Dateistand, nicht die Geräteabnahme oder einen erfolgreichen CI-Lauf.

| Bereich | Codebefund | Bewertung |
|---|---|---|
| EngineViewModel-Aufteilung | EngineViewModel ist eine dünne Fassade über EngineLifecycleViewModel. Die Kette enthält zusätzlich ChatViewModel, ModelManagementViewModel und SettingsViewModel. | Teilweise bestätigt |
| DownloadViewModel | In der aktuellen Dateiliste existiert keine DownloadViewModel-Klasse. ModelImportViewModel dokumentiert, dass Remote-HTTPS-Materialisierung in ModelSourceResolver verbleibt. | Nicht bestätigt — DownloadViewModel fehlt |
| SettingsScreen-Sektionen | SettingsScreen bindet getrennte Composables für System Prompt, Backend Settings, Inference Settings, Backend Acceptance, Vulkan Q4K Diagnostics und Benchmark Result ein. | Bestätigt |
| ModelImportScreen-Sektionen | Getrennte Dateien/Composables für Active Model, File Picker, Import Error, Import Progress und Imported Models/Card/Empty State sind vorhanden und werden vom Screen verwendet. | Bestätigt |
| InferenceServiceProcessTest-Aufteilung | Die ursprüngliche InferenceServiceProcessTest.kt ist nicht mehr vorhanden. Aktuell gibt es InferenceServiceProcessTestBase sowie getrennte Tests für Process Lifecycle, GGUF Recovery, Cancellation, Descriptor und Native Boundary/IPC. | Aufteilungsstruktur bestätigt |
| Abbruch-/Timeout-Grundlogik | InferenceClient protokolliert die wirksame Timeout-Konfiguration je Request, versucht kooperative Cancellation und eskaliert nach abortGracePeriodMs gegen die ursprüngliche Service-PID. InferenceService leitet Cancel an die Engine weiter und kann den eigenen Prozess beenden. | Im Code bestätigt; keine neue Runtime-Abnahme |
| Model-Loading | SAF-Content-URIs werden im Client mit openFileDescriptor(uri, "r") geöffnet und per Binder übertragen. Der Service verwendet den eigenen Prozess-lokalen /proc/self/fd/N-Pfad. | Im Code bestätigt |
| Descriptor-Ownership | Client schließt seine PFD-Kopie nach Binder-Übergabe. Service hält die Binder-Kopie, JNI dupliziert den FD mit dup(), wrappt ihn mit fdopen() und behält den FILE*-Stream bis zum Model-Unload. Beim Unload wird llama.cpp zuerst entladen, dann der Service-PFD geschlossen. | Im Code bestätigt |

Fundstellen:

- ViewModel-Kette: app/src/main/java/com/example/ui/EngineViewModel.kt:22-25; app/src/main/java/com/example/ui/diagnostics/EngineLifecycleViewModel.kt:40-43; app/src/main/java/com/example/ui/settings/SettingsViewModel.kt:41-44; app/src/main/java/com/example/ui/modelimport/ModelImportViewModel.kt:40-43; app/src/main/java/com/example/ui/modelimport/ModelManagementViewModel.kt:32-35; app/src/main/java/com/example/ui/chat/ChatViewModel.kt:28.
- Settings-Sektionen: app/src/main/java/com/example/ui/settings/SettingsScreen.kt:25-90; Dateien in app/src/main/java/com/example/ui/settings/sections/.
- Model-Import-Sektionen: app/src/main/java/com/example/ui/modelimport/ModelImportScreen.kt:15-99; Dateien in app/src/main/java/com/example/ui/modelimport/.
- Testaufteilung: llm-engine/src/androidTest/java/com/mydocanizer/llm/engine/ipc/.
- Timeout/Abbruch: llm-engine/src/main/java/com/mydocanizer/llm/engine/ipc/InferenceClient.kt:860-874, 985-1020 und 1470-1577; InferenceService.kt:514-529.
- Descriptor-Ownership: InferenceClient.kt:599-608 und 684-695; InferenceService.kt:244-345 und 360-370; ModelSourceResolver.kt:268-356; LocalLlmEngineImpl.kt:208-242; local_llm_engine_jni.cpp:1191-1215 und 1299.

---

## V1-Abschluss

- Overlay, Selector, Readback, Submit-/Fence-Zweige und Sample-Timer sind im aktuellen Code lokalisiert.
- Ein per-Token-Remapping des Staging-Buffers wurde nicht gefunden.
- Der Sample-Timer ist kein Readback- oder Decode-Timer.
- Die Submit-/Fence-Anzahl ist vom Graph-/Queue-Zustand abhängig und wird im Code nicht als fester Wert pro Token bestimmt.
- Mali-AUTO erzwingt aktuell ausdrücklich BASE_TYPE_MMV.
- Die erwähnte ViewModel-Aufteilung ist teilweise bestätigt; DownloadViewModel fehlt.
- Es wurden keine Code-Fixes vorgenommen.

**V1 abgeschlossen. V2 wurde noch nicht ausgeführt.**

Nächster Schritt gemäß Plan: genau ein LFM3-1.7B-Q4_K_M-Lauf mit explizitem Vulkan-Backend und identischer Diagnose-Sequenz wie im Referenz-Log. Erst die Werte dieses Laufs bestimmen die V3-Fallauswahl.
