# Integrationsanleitung: :llm-engine in myDocAnizer-Mobile

Dieses Dokument beschreibt die schrittweise und vollständige Integration des eigenständigen Android-Library-Moduls `:llm-engine` in das Hauptprojekt **MrLongNight/myDocAnizer_Mobile**.

---

## 1. Einbindung von `:llm-engine` in das Projekt

Das Modul `:llm-engine` ist als vollkommen entkoppeltes Android-Library-Modul (`com.android.library`) konzipiert und besitzt keinerlei Abhängigkeiten zu Room, Compose, Firebase oder DMS-Spezifika.

### `settings.gradle.kts`
Füge das Modul in die Modulliste des Zielprojekts ein:
```kotlin
include(":app")
include(":llm-engine")
```

### `app/build.gradle.kts`
Füge die Projektabhängigkeit im `app`-Modul hinzu:
```kotlin
dependencies {
    implementation(project(":llm-engine"))
}
```

---

## 2. Erforderliche Gradle-Abhängigkeiten & Toolchain-Voraussetzungen

### Modul `:llm-engine` (`build.gradle.kts`)
- **Android Gradle Plugin (AGP)**: 9.1.1 (die Version des Zielprojekts muss mit dessen Gradle-/Kotlin-Plugin-Konfiguration kompatibel sein)
- **Kotlin**: 2.2.10
- **Android NDK**: `27.2.12479018` (in `ndkVersion` fixiert)
- **CMake**: `3.22.1`
- **Java-Bytecode**: 17 (JavaVersion.VERSION_17). Der aktuelle Robolectric-API-36-Unit-Test-Workflow benötigt zum Ausführen JDK 21; das ist keine Laufzeitvoraussetzung für die Android-App.
- **minSdk**: 24
- **targetSdk**: 36
- **Externe Bibliotheken**:
  - `org.jetbrains.kotlinx:kotlinx-coroutines-core` (Flow, Dispatcher)
  - `androidx.core:core-ktx`

---

## 3. Unterstützte ABIs (Native Architekturen)

Das Modul `:llm-engine` baut nativ für:
1. **`arm64-v8a`**: 64-Bit ARM für moderne Android-Smartphones und Tablets (inkl. ARM NEON und KleidiAI-Vektorinstruktionen).
2. **`x86_64`**: 64-Bit x86 für moderne Android-Emulatoren und ChromeOS-Geräte (inkl. AVX/AVX2).

In `llm-engine/build.gradle.kts`:
```kotlin
android {
    defaultConfig {
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }
}
```

---

## 4. Native Bibliotheken im APK

Beim Kompilieren wird durch CMake (`CMakeLists.txt`) folgendes Artefakt erzeugt und automatisch in das APK gepackt:
- **`liblocalllm_engine.so`**: Die zentrale native shared library, welche `llama.cpp` (gepinnter Commit `2da668617612d2df773f966e3b0ee22dc2beef7b`), die Vulkan-GPU-Initialisierung und die JNI-Bridge bündelt.
- **System-Verlinkung**: Verlinkt gegen Android NDK `liblog.so`, `libandroid.so` und `libvulkan.so`.

---

## 5. Engine-Initialisierung

Die Engine wird thread-sicher über die Factory-Methode erzeugt:

```kotlin
import com.mydocanizer.llm.engine.LocalLlmEngine

// Im ViewModel, DI-Modul oder Service:
val engine: LocalLlmEngine = LocalLlmEngine.create(context)
```

Die Engine verwendet intern einen isolierten Single-Thread-IO-Dispatcher (`Dispatchers.IO.limitedParallelism(1)`), sodass zu keinem Zeitpunkt der Android Main/UI-Thread blockiert wird.

---

## 6. Übergabe eines GGUF-Modells

Unterstützt werden lokale Pfade (`ModelSource.FilePath`), SAF Content-URIs (`ModelSource.ContentUri`), Assets (`ModelSource.AssetPath`) und HTTPS-Downloads (`ModelSource.RemoteUrl`). Importierte Quellen werden zunächst in eine temporäre Datei geschrieben und erst nach erfolgreichem Abschluss in den privaten Modellordner verschoben. Der Dateiname erhält einen SHA-256-Suffix, damit unterschiedliche Modelle mit gleichem Anzeigenamen einander nicht überschreiben. Downloads sind derzeit nicht resumierbar.

```kotlin
import com.mydocanizer.llm.engine.ModelSource
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.BackendPreference

val config = EngineConfig(
    contextSize = 4096,               // Dynamische Kontextgröße (wird gegen n_ctx_train geprüft)
    batchSize = 512,                  // Batch-Größe für Prompt-Evaluation
    threads = 4,                      // CPU-Worker Threads
    temperature = 0.2f,               // Niedrig für deterministische Dokumentenextraktion
    topP = 0.9f,                      // Nucleus Sampling
    maxPredictTokens = 1024,          // Token-Ausgabelimit
    backendPreference = BackendPreference.AUTO, // AUTO, VULKAN oder CPU
    gpuLayers = 99                    // Volles GPU-Offloading bei Vulkan
)

// Modell laden:
val result: Result<ModelInfo> = engine.loadModel(
    source = ModelSource.FilePath("/data/user/0/com.mydocanizer/files/models/qwen2.5-0.5b-instruct-q4_k_m.gguf"),
    config = config
)

result.onSuccess { modelInfo ->
    println("Modell bereit: ${modelInfo.name}")
    println("Aktives Backend: ${modelInfo.activeBackend}")
    println("Trainierter Kontext: ${modelInfo.contextLengthTrain} Tokens")
    println("SHA-256 Prüfsumme: ${modelInfo.sha256}")
}.onFailure { error ->
    System.err.println("Fehler beim Laden: ${error.message}")
}
```

---

## 7. Übergabe eines DMS-System-Prompts plus OCR-Nachricht

Für die strukturierte Dokumentenextraktion in myDocAnizer:

```kotlin
import com.mydocanizer.llm.engine.ChatMessage
import com.mydocanizer.llm.engine.InferenceRequest

val dmsSystemPrompt = """
    Du bist die lokale Dokumenten-Analyse-Engine von myDocAnizer.
    Extrahiere aus dem folgenden OCR-Volltext strukturierte Metadaten:
    - Dokumententyp (Rechnung, Vertrag, Brief, Beleg)
    - Rechnungssteller / Absender
    - Datum (Format YYYY-MM-DD)
    - Gesamtbetrag und Währung
    Antworte ausschließlich im validen JSON-Format.
""".trimIndent()

val ocrText = """
    Muster GmbH - Hauptstraße 12, 10115 Berlin
    Rechnung Nr: RE-2026-9921
    Datum: 24.10.2026
    Leistungsbeschreibung: Software-Lizenzierung DMS
    Endbetrag inkl. 19% MwSt: 1.190,00 EUR
""".trimIndent()

val request = InferenceRequest(
    messages = listOf(
        ChatMessage.system(dmsSystemPrompt),
        ChatMessage.user("Hier ist der OCR-Text:\n$ocrText")
    ),
    maxPredictTokens = 1024,
    temperature = 0.1f // Nahe 0 für reproduzierbare JSON-Ausgaben
)
```

---

## 8. Mehrnachrichten-Chats (Multi-Turn)

Die Engine akzeptiert eine vollständige Gesprächshistorie. llama.cpp stellt das im GGUF hinterlegte Chat-Template bereit; die aktuelle JNI-Implementierung ruft die nicht-Jinja-Formatierungsvariante auf. Modelle, deren Template zwingend Jinja-Kontrollstrukturen oder komplexe Template-Logik benötigen, sind daher noch nicht garantiert kompatibel:

```kotlin
val history = listOf(
    ChatMessage.system("Du bist ein hilfsbereiter KI-Assistent für myDocAnizer."),
    ChatMessage.user("Was ist der Unterschied zwischen Vorsteuer und Umsatzsteuer?"),
    ChatMessage.assistant("Die Vorsteuer ist die Umsatzsteuer, die ein Unternehmer beim Einkauf von Waren oder Dienstleistungen an andere Unternehmer bezahlt..."),
    ChatMessage.user("Und wann kann ich sie beim Finanzamt geltend machen?")
)

val request = InferenceRequest(messages = history) // maxPredictTokens aus EngineConfig
```

---

## 9. Token-Streaming an Kotlin

Jedes generierte Token wird sofort und verzögerungsfrei als Kotlin-`Flow` gestreamt:

```kotlin
import com.mydocanizer.llm.engine.InferenceEvent

val responseBuilder = StringBuilder()

engine.streamChat(request).collect { event ->
    when (event) {
        is InferenceEvent.Started -> {
            println("Inferenz gestartet (Tokens im Kontext: ${event.promptMessageCount})")
        }
        is InferenceEvent.Token -> {
            // Live-Token direkt an UI weiterleiten:
            responseBuilder.append(event.token)
            updateUiText(responseBuilder.toString())
        }
        is InferenceEvent.Completed -> {
            println("Fertiggestellt!")
            println("Generierte Tokens: ${event.result.tokensGenerated}")
            println("Geschwindigkeit: ${event.result.tokensPerSecond} t/s")
            println("Dauer: ${event.result.durationMs} ms")
        }
        is InferenceEvent.Cancelled -> {
            println("Inferenz durch Benutzer abgebrochen. Bisheriger Text: ${event.partialText}")
        }
        is InferenceEvent.Error -> {
            System.err.println("Inferenz-Fehler: ${event.throwable.message}")
        }
    }
}
```

---

## 10. Cancellation (Nativer Inferenz-Abbruch)

Der Abbruch kann auf zwei konsistente Arten ausgelöst werden:
1. **Coroutine-Cancellation**: Wenn der aufrufende Flow-Scope gecancelt wird, setzt die Engine den nativen atomaren Abbruch-Flag. llama.cpp prüft ihn über den Abort-Callback auch während der Prompt-Auswertung.
2. **Expliziter Methodenaufruf**:
```kotlin
// Bricht die laufende Generierung sofort nativ ab
engine.cancelInference()
```
Nach einem expliziten Abbruch wird ein `InferenceEvent.Cancelled` mit dem bis dahin erzeugten Text gemeldet. Nach Coroutine-Cancellation wird die Cancellation an den aufrufenden Scope weitergereicht. Der Engine-Zustand kehrt nach Abschluss der laufenden nativen Arbeit zu `ModelLoaded` zurück. Das Schließen der Engine signalisiert zuerst den Abbruch und zerstört den nativen Handle erst serialisiert auf dem Engine-Dispatcher; dadurch wird kein Handle während einer laufenden Inferenz freigegeben.

---

## 11. Fehlerbehandlung

Die Engine unterscheidet strikt zwischen erwartbaren Zuständen und echten Hardware-/Ladefehlern:
- **`BackendPreference.VULKAN`**: Schlägt mit einem klaren Fehler fehl, falls das llama.cpp-Vulkan-Backend nicht mitgebaut oder kein nutzbares Vulkan-Backend registriert ist. Der aktuelle CMake-Build kompiliert `GGML_VULKAN` nicht.
- **`BackendPreference.AUTO`**: Verwendet nur ein tatsächlich registriertes llama.cpp-GPU-Backend; andernfalls CPU.
- **Fehler werden niemals als angebliche Modellantwort formatiert**, sondern immer als `InferenceEvent.Error` bzw. `Result.failure()` geliefert.

---

## 12. `InferenceResult` Metriken

Am Ende jeder erfolgreichen Inferenz liefert `InferenceResult` native Messwerte. Die Zeitmessung umfasst Prompt-Tokenisierung, Prefill und Generierung:
- `text`: Vollständiger generierter Antworttext
- `tokensGenerated`: Tatsächliche Anzahl erzeugter Token
- `durationMs`: Reale Inferenzzeit in Millisekunden
- `tokensPerSecond`: Exakte Generierungsrate (`tokensGenerated / (durationMs / 1000.0)`)
- `finishReason`:
  - `FinishReason.STOP`: Reguläres EOS-Token (End of Sequence) erreicht
  - `FinishReason.MAX_TOKENS`: `maxPredictTokens` erreicht
  - `FinishReason.CANCELLED`: Abbruch durch Benutzer
  - `FinishReason.ERROR`: Interner Fehler

---

## Architektur-Gegenüberstellung: myDocAnizer-Mobile

### Was bleibt in myDocAnizer-Mobile erhalten?
- Die gesamte **DMS-Geschäftslogik** (Dokumentenverwaltung, Ordnerhierarchien, Metadatenfelder).
- Die **Room-Datenbank** (`AppDatabase`, DAOs und SQLite-Entitäten).
- Das **OCR-Subsystem** (Texterkennung, Bildvorverarbeitung).
- Alle **UI-Screens** von myDocAnizer (Dokumentenansichten, PDF-Viewer, Kamera-Scan).

### Was wird durch `:llm-engine` ersetzt?
- Veraltete oder fragmentierte JNI-Bindings zu älteren llama.cpp-Versionen.
- Frühere CMake-Skripte im App-Modul werden vollständig entfernt und durch `:llm-engine/src/main/cpp/CMakeLists.txt` abgelöst.
- Unkontrollierte globale native Zeiger werden durch die objektorientierte Handle-Verwaltung (`EngineInstance`) ersetzt.
- Manuelle Token-Pufferung wird durch standardisierte Kotlin-Coroutinen-Streams (`Flow<InferenceEvent>`) ersetzt.
