package com.mydocanizer.llm.engine.ipc;

import com.mydocanizer.llm.engine.ModelInfo;
import com.mydocanizer.llm.engine.InferenceResult;

interface IInferenceCallback {
    void onModelLoaded(in ModelInfo modelInfo);
    void onModelLoadFailed(String errorMessage);
    void onStarted(String requestId, int promptMessageCount);
    void onToken(String requestId, String token, int tokenIndex);
    void onProgress(String requestId, int tokensGenerated, long elapsedMs);
    void onCompleted(String requestId, in InferenceResult result);
    void onCancelled(String requestId, String partialText, int tokensGenerated);
    void onError(String requestId, String errorMessage, String partialText);
}
