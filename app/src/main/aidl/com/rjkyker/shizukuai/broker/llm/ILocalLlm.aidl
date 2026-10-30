package com.rjkyker.shizukuai.broker.llm;

// 1.9.0 bound service in the isolated ":llm" process. All payloads are JSON strings.
// Only the broker's own main process binds (service is exported=false).
interface ILocalLlm {
    String status() = 1;
    String preflight(String modelId) = 2;
    // Starts a verified download in the background; poll status() for progress.
    String ensureModel(String modelId, boolean allowLowMargin) = 3;
    // Blocking; caps enforced service-side (n_ctx<=2048, max_new<=256, 45 s).
    String generate(String requestJson) = 4;
    void cancel() = 5;
    String unload() = 6;
    String deleteModel(String modelId) = 7;
}
