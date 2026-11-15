# Verify the upstream async backend-split race fix required by hybrid Qwen models.
# Vulkan may enqueue a split asynchronously; a following CPU split without inputs can
# otherwise reuse/overwrite the same allocator region before the Vulkan copy consumes it.
function(local_llm_verify_split_scheduler_race_fix source_dir)
    set(_backend_source "${source_dir}/ggml/src/ggml-backend.cpp")
    if(NOT EXISTS "${_backend_source}")
        message(FATAL_ERROR "LocalLLM: pinned ggml-backend.cpp is missing: ${_backend_source}")
    endif()
    file(READ "${_backend_source}" _backend_contents)

    set(_function_start_anchor "static enum ggml_status ggml_backend_sched_compute_splits(ggml_backend_sched_t sched)")
    set(_function_end_anchor "ggml_backend_sched_t ggml_backend_sched_new(")
    string(FIND "${_backend_contents}" "${_function_start_anchor}" _function_start)
    string(FIND "${_backend_contents}" "${_function_end_anchor}" _function_end)
    if(_function_start LESS 0 OR _function_end LESS 0 OR _function_end LESS_EQUAL _function_start)
        message(FATAL_ERROR "LocalLLM: ggml backend split scheduler function boundary changed; review async synchronization.")
    endif()

    math(EXPR _function_length "${_function_end} - ${_function_start}")
    string(SUBSTRING "${_backend_contents}" ${_function_start} ${_function_length} _function_contents)

    set(_previous_backend_anchor "int prev_backend_id = -1;")
    set(_no_input_sync_anchor "if (split->n_inputs == 0 && prev_backend_id >= 0 && prev_backend_id != split_backend_id)")
    set(_event_sync_anchor "ggml_backend_event_synchronize(sched->events[prev_backend_id][sched->cur_copy]);")
    set(_backend_sync_anchor "ggml_backend_synchronize(sched->backends[prev_backend_id]);")
    set(_record_split_anchor "// record the event of this split")
    set(_record_event_anchor "ggml_backend_event_record(sched->events[split_backend_id][sched->cur_copy], split_backend);")
    set(_advance_backend_anchor "prev_backend_id = split_backend_id;")

    foreach(_anchor
        _previous_backend_anchor
        _no_input_sync_anchor
        _event_sync_anchor
        _backend_sync_anchor
        _record_split_anchor
        _record_event_anchor
        _advance_backend_anchor)
        string(FIND "${_function_contents}" "${${_anchor}}" _anchor_position)
        if(_anchor_position LESS 0)
            message(FATAL_ERROR "LocalLLM: upstream backend split race fix anchor missing: ${${_anchor}}")
        endif()
    endforeach()

    # Verify the complete guard, not just isolated strings: the fence/event wait
    # must execute inside the no-input cross-backend transition branch.
    set(_no_input_sync_block_anchor [=[
        if (split->n_inputs == 0 && prev_backend_id >= 0 && prev_backend_id != split_backend_id) {
            if (sched->events[prev_backend_id][sched->cur_copy] != NULL) {
                ggml_backend_event_synchronize(sched->events[prev_backend_id][sched->cur_copy]);
            } else {
                ggml_backend_synchronize(sched->backends[prev_backend_id]);
            }
        }
]=])
    string(FIND "${_function_contents}" "${_no_input_sync_block_anchor}" _no_input_sync_block_position)
    if(_no_input_sync_block_position LESS 0)
        message(FATAL_ERROR "LocalLLM: upstream no-input cross-backend synchronization block changed; refusing an unverified hybrid model backend.")
    endif()

    string(FIND "${_function_contents}" "${_previous_backend_anchor}" _previous_backend_position)
    string(FIND "${_function_contents}" "${_no_input_sync_anchor}" _no_input_sync_position)
    string(FIND "${_function_contents}" "${_record_split_anchor}" _record_split_position)
    string(FIND "${_function_contents}" "${_advance_backend_anchor}" _advance_backend_position)
    if(_previous_backend_position GREATER_EQUAL _no_input_sync_position OR
       _no_input_sync_position GREATER_EQUAL _record_split_position OR
       _record_split_position GREATER_EQUAL _advance_backend_position)
        message(FATAL_ERROR "LocalLLM: upstream split synchronization/event ordering changed; refusing an unverified hybrid model backend.")
    endif()
    message(STATUS "LocalLLM: verified upstream async backend-split synchronization for no-input CPU/Vulkan split transitions")
endfunction()