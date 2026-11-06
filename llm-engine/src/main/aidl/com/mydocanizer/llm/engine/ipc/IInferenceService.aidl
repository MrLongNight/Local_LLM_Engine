package com.mydocanizer.llm.engine.ipc;

import android.os.ParcelFileDescriptor;
import com.mydocanizer.llm.engine.EngineConfig;
import com.mydocanizer.llm.engine.InferenceRequest;
import com.mydocanizer.llm.engine.BackendInfo;
import com.mydocanizer.llm.engine.ipc.IInferenceCallback;

interface IInferenceService {
    void initialize();
    void loadModel(String modelPath, in ParcelFileDescriptor modelDescriptor, String displayName, in EngineConfig config, IInferenceCallback callback);
    void unloadModel(IInferenceCallback callback);
    void streamChat(String requestId, in InferenceRequest request, IInferenceCallback callback);
    void cancelInference(String requestId);
    oneway void killProcess();
    String getStatus();
    BackendInfo getBackendInfo();
    int getProcessId();
    void shutdown();
}
