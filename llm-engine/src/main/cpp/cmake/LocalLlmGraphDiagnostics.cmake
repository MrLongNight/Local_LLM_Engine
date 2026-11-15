# Keep the pinned llama.cpp tree pristine while compiling a generated copy of
# llama-context.cpp that counts graph nodes assigned to CPU/Vulkan per request.
function(local_llm_prepare_graph_backend_diagnostics source_dir)
    set(_context_source "${source_dir}/src/llama-context.cpp")
    if(NOT EXISTS "${_context_source}")
        message(FATAL_ERROR "LocalLLM: pinned llama-context.cpp is missing: ${_context_source}")
    endif()

    file(READ "${_context_source}" _context_contents)

    set(_gdn_bridge_anchor "#include \"llama.h\"")
    string(FIND "${_context_contents}" "${_gdn_bridge_anchor}" _gdn_bridge_position)
    if(_gdn_bridge_position LESS 0)
        message(FATAL_ERROR "LocalLLM: llama-context.cpp GDN-policy bridge anchor changed.")
    endif()
    string(REPLACE "${_gdn_bridge_anchor}" "${_gdn_bridge_anchor}\nextern \"C\" int local_llm_gdn_policy_for_arch(int is_qwen35_arch);" _context_contents "${_context_contents}")
    set(_graph_callback_anchor [=[
void llama_context::sched_graph_realloc(ggml_backend_sched_t sched, ggml_cgraph * gf, bool backend_ids_changed, bool graph_size_changed, void * user_data) {
    // reallocating after the backend assignments changed is expected, trace only the unexpected ones
    if (backend_ids_changed) {
        return;
    }
]=])
    string(FIND "${_context_contents}" "${_graph_callback_anchor}" _graph_callback_position)
    if(_graph_callback_position LESS 0)
        message(FATAL_ERROR "LocalLLM: pinned llama_context scheduler callback anchor changed; graph backend distribution cannot be instrumented.")
    endif()

    # The pinned source already uses std::string; add the explicit mutex header
    # for thread-safe per-run deduplication of identical graph layouts.
    string(REPLACE "#include <limits>" "#include <limits>\n#include <mutex>" _context_contents "${_context_contents}")

    set(_graph_callback_replacement [=[
extern "C" const char * local_llm_vulkan_profile_run_id();

void llama_context::sched_graph_realloc(ggml_backend_sched_t sched, ggml_cgraph * gf, bool backend_ids_changed, bool graph_size_changed, void * user_data) {
    // Count nodes against their actual scheduler backend. This is stronger evidence
    // of CPU fallback than requested gpuLayers or an aggregate split count alone.
    const char * run_id = local_llm_vulkan_profile_run_id();
    if (run_id != nullptr && std::strcmp(run_id, "none") != 0 && gf != nullptr) {
        int cpu_nodes = 0;
        int vulkan_nodes = 0;
        int other_nodes = 0;
        int unassigned_nodes = 0;
        int gdn_cpu_nodes = 0, gdn_vulkan_nodes = 0, gdn_other_nodes = 0, gdn_unassigned_nodes = 0;
        int ssm_scan_cpu_nodes = 0, ssm_scan_vulkan_nodes = 0, ssm_scan_other_nodes = 0, ssm_scan_unassigned_nodes = 0;
        int ssm_conv_cpu_nodes = 0, ssm_conv_vulkan_nodes = 0, ssm_conv_other_nodes = 0, ssm_conv_unassigned_nodes = 0;
        const int graph_nodes = ggml_graph_n_nodes(gf);
        for (int i = 0; i < graph_nodes; ++i) {
            ggml_tensor * node = ggml_graph_node(gf, i);
            const bool is_gdn = node->op == GGML_OP_GATED_DELTA_NET;
            const bool is_ssm_scan = node->op == GGML_OP_SSM_SCAN;
            const bool is_ssm_conv = node->op == GGML_OP_SSM_CONV;
            ggml_backend_t backend = ggml_backend_sched_get_tensor_backend(sched, node);
            if (backend == nullptr) {
                ++unassigned_nodes;
                if (is_gdn) ++gdn_unassigned_nodes;
                if (is_ssm_scan) ++ssm_scan_unassigned_nodes;
                if (is_ssm_conv) ++ssm_conv_unassigned_nodes;
                continue;
            }
            const char * backend_name = ggml_backend_name(backend);
            if (backend_name != nullptr && std::strcmp(backend_name, "CPU") == 0) {
                ++cpu_nodes;
                if (is_gdn) ++gdn_cpu_nodes;
                if (is_ssm_scan) ++ssm_scan_cpu_nodes;
                if (is_ssm_conv) ++ssm_conv_cpu_nodes;
            } else if (backend_name != nullptr && std::strncmp(backend_name, "Vulkan", 6) == 0) {
                ++vulkan_nodes;
                if (is_gdn) ++gdn_vulkan_nodes;
                if (is_ssm_scan) ++ssm_scan_vulkan_nodes;
                if (is_ssm_conv) ++ssm_conv_vulkan_nodes;
            } else {
                ++other_nodes;
                if (is_gdn) ++gdn_other_nodes;
                if (is_ssm_scan) ++ssm_scan_other_nodes;
                if (is_ssm_conv) ++ssm_conv_other_nodes;
            }
        }

        // The callback may be invoked several times for an unchanged graph. Keep
        // one evidence row per distinct graph layout and inference run, while
        // retaining separate prefill/decode shapes when their node assignments differ.
        static std::mutex local_llm_graph_diagnostic_mutex;
        static std::string local_llm_last_graph_signature;
        const std::string graph_signature =
            std::string(run_id) + ":" + std::to_string(graph_nodes) + ":" +
            std::to_string(ggml_backend_sched_get_n_splits(sched)) + ":" +
            std::to_string(cpu_nodes) + ":" + std::to_string(vulkan_nodes) + ":" +
            std::to_string(other_nodes) + ":" + std::to_string(unassigned_nodes) + ":" +
            std::to_string(gdn_cpu_nodes) + ":" + std::to_string(gdn_vulkan_nodes) + ":" +
            std::to_string(gdn_other_nodes) + ":" + std::to_string(gdn_unassigned_nodes) + ":" +
            std::to_string(ssm_scan_cpu_nodes) + ":" + std::to_string(ssm_scan_vulkan_nodes) + ":" +
            std::to_string(ssm_scan_other_nodes) + ":" + std::to_string(ssm_scan_unassigned_nodes) + ":" +
            std::to_string(ssm_conv_cpu_nodes) + ":" + std::to_string(ssm_conv_vulkan_nodes) + ":" +
            std::to_string(ssm_conv_other_nodes) + ":" + std::to_string(ssm_conv_unassigned_nodes);
        bool should_log_graph_distribution = false;
        {
            std::lock_guard<std::mutex> lock(local_llm_graph_diagnostic_mutex);
            if (graph_signature != local_llm_last_graph_signature) {
                local_llm_last_graph_signature = graph_signature;
                should_log_graph_distribution = true;
            }
        }
        if (should_log_graph_distribution) {
            // Keep the existing compact report marker intact for downstream parsers.
            LLAMA_LOG_INFO(
                "llama_context: LOCAL_LLM_GRAPH_BACKEND_DISTRIBUTION - runId=%s,graphNodes=%d,graphSplits=%d,cpuNodes=%d,vulkanNodes=%d,otherNodes=%d,unassignedNodes=%d,backendIdsChanged=%d,graphSizeChanged=%d\\n",
                run_id,
                graph_nodes,
                ggml_backend_sched_get_n_splits(sched),
                cpu_nodes,
                vulkan_nodes,
                other_nodes,
                unassigned_nodes,
                backend_ids_changed ? 1 : 0,
                graph_size_changed ? 1 : 0);
            LLAMA_LOG_INFO(
                "llama_context: LOCAL_LLM_GRAPH_OP_BACKEND_DISTRIBUTION - runId=%s,graphNodes=%d,graphSplits=%d,cpuNodes=%d,vulkanNodes=%d,otherNodes=%d,unassignedNodes=%d,gdnCpuNodes=%d,gdnVulkanNodes=%d,gdnOtherNodes=%d,gdnUnassignedNodes=%d,ssmScanCpuNodes=%d,ssmScanVulkanNodes=%d,ssmScanOtherNodes=%d,ssmScanUnassignedNodes=%d,ssmConvCpuNodes=%d,ssmConvVulkanNodes=%d,ssmConvOtherNodes=%d,ssmConvUnassignedNodes=%d,backendIdsChanged=%d,graphSizeChanged=%d\n",
                run_id,
                graph_nodes,
                ggml_backend_sched_get_n_splits(sched),
                cpu_nodes,
                vulkan_nodes,
                other_nodes,
                unassigned_nodes,
                gdn_cpu_nodes,
                gdn_vulkan_nodes,
                gdn_other_nodes,
                gdn_unassigned_nodes,
                ssm_scan_cpu_nodes,
                ssm_scan_vulkan_nodes,
                ssm_scan_other_nodes,
                ssm_scan_unassigned_nodes,
                ssm_conv_cpu_nodes,
                ssm_conv_vulkan_nodes,
                ssm_conv_other_nodes,
                ssm_conv_unassigned_nodes,
                backend_ids_changed ? 1 : 0,
                graph_size_changed ? 1 : 0);
        }
    }

    // reallocating after the backend assignments changed is expected, trace only the unexpected ones
    if (backend_ids_changed) {
        return;
    }
]=])
    string(REPLACE "${_graph_callback_anchor}" "${_graph_callback_replacement}" _context_contents "${_context_contents}")

    # Qwen3.5 GDN correctness fallback. Both the autoregressive decode and
    # chunked prefill paths use the unfused reference graph until parity is proven.
    set(_gdn_policy_anchor [=[
    cparams.fused_gdn_ar = true;
    cparams.fused_gdn_ch = true;
    cparams.auto_fgdn    = false;
]=])
    string(FIND "${_context_contents}" "${_gdn_policy_anchor}" _gdn_policy_position)
    if(_gdn_policy_position LESS 0)
        message(FATAL_ERROR "LocalLLM: pinned llama-context.cpp GDN policy anchor changed; review the Qwen3.5 fallback.")
    endif()
    set(_gdn_policy_replacement [=[
    const bool local_llm_qwen35_arch =
        model.arch == LLM_ARCH_QWEN35 || model.arch == LLM_ARCH_QWEN35MOE;
    const int local_llm_gdn_fused_flags =
        local_llm_gdn_policy_for_arch(local_llm_qwen35_arch ? 1 : 0);
    cparams.fused_gdn_ar = (local_llm_gdn_fused_flags & 1) != 0;
    cparams.fused_gdn_ch = (local_llm_gdn_fused_flags & 2) != 0;
    cparams.auto_fgdn    = false;
    if (local_llm_qwen35_arch) {
        LLAMA_LOG_WARN(
            "llama_context: LOCAL_LLM_QWEN35_GDN_POLICY - autoregressive=%s,chunked=%s,vulkanGdn=cpu-fallback,fusionFlags=%d\\n",
            (local_llm_gdn_fused_flags & 1) != 0 ? "fused" : "unfused",
            (local_llm_gdn_fused_flags & 2) != 0 ? "fused" : "unfused",
            local_llm_gdn_fused_flags);
    }
]=])
    string(REPLACE "${_gdn_policy_anchor}" "${_gdn_policy_replacement}" _context_contents "${_context_contents}")

    set(_patched_dir "${CMAKE_CURRENT_BINARY_DIR}/local-android-llama")
    file(MAKE_DIRECTORY "${_patched_dir}")
    set(_patched_context "${_patched_dir}/llama-context.cpp")
    file(WRITE "${_patched_context}" "${_context_contents}")
    if(NOT _context_contents MATCHES "LOCAL_LLM_GRAPH_BACKEND_DISTRIBUTION" OR
       NOT _context_contents MATCHES "LOCAL_LLM_GRAPH_OP_BACKEND_DISTRIBUTION" OR
       NOT _context_contents MATCHES "ggml_backend_sched_get_tensor_backend" OR
       NOT _context_contents MATCHES "ssmScanVulkanNodes" OR
       NOT _context_contents MATCHES "ssmConvVulkanNodes")
        message(FATAL_ERROR "LocalLLM: graph backend distribution overlay did not apply completely.")
    endif()
    if(NOT _context_contents MATCHES "LOCAL_LLM_QWEN35_GDN_POLICY" OR
       NOT _context_contents MATCHES "local_llm_gdn_policy_for_arch" OR
       NOT _context_contents MATCHES "cparams.fused_gdn_ar" OR
       NOT _context_contents MATCHES "cparams.fused_gdn_ch" OR
       NOT _context_contents MATCHES "autoregressive=%s,chunked=%s")
        message(FATAL_ERROR "LocalLLM: Qwen3.5 unfused AR+CH GDN fallback overlay did not apply completely.")
    endif()
    set(LOCAL_LLM_PATCHED_LLAMA_CONTEXT_CPP "${_patched_context}" PARENT_SCOPE)
endfunction()