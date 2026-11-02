# Proguard rules for consumer of llm-engine
-keepclasseswithmembernames class com.mydocanizer.llm.engine.internal.NativeEngineHandle {
    native <methods>;
}
