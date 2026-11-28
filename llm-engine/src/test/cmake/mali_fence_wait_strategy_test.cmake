# Host-side regression test for the production Mali fence-wait source validator.
include("${CMAKE_CURRENT_LIST_DIR}/../../main/cpp/cmake/LocalLlmAndroidVulkan.cmake")

set(_spin_source [=[
if (ctx->device->vendor_id == 0x13B5) {
    // LOCAL_LLM_MALI_FENCE_WAIT_SELECTED_SPIN
    local_llm_poll_fence = true;
} else {
    local_llm_poll_fence = true;
}
if (local_llm_poll_fence) {
    for (;;) {
        result = ctx->device->device.getFenceStatus(ctx->fence);
        if (result == vk::Result::eSuccess) {
            break;
        }
        if (result != vk::Result::eNotReady) {
            throw vk::SystemError(vk::make_error_code(result), "getFenceStatus");
        }
        YIELD();
    }
}
ctx->device->device.resetFences({ ctx->fence });
]=])
local_llm_verify_mali_fence_wait_strategy(_spin_source SPIN)

set(_block_source [=[
if (ctx->device->vendor_id == 0x13B5) {
    // LOCAL_LLM_MALI_FENCE_WAIT_SELECTED_BLOCK
    VK_CHECK(
        ctx->device->device.waitForFences({ ctx->fence }, true, UINT64_MAX),
        "Mali blocking fence wait",
        ctx->device);
} else {
    local_llm_poll_fence = true;
}
if (local_llm_poll_fence) {
    for (;;) {
        result = ctx->device->device.getFenceStatus(ctx->fence);
        if (result == vk::Result::eSuccess) {
            break;
        }
        if (result != vk::Result::eNotReady) {
            throw vk::SystemError(vk::make_error_code(result), "getFenceStatus");
        }
        YIELD();
    }
}
ctx->device->device.resetFences({ ctx->fence });
]=])
local_llm_verify_mali_fence_wait_strategy(_block_source BLOCK)

message(STATUS "Mali fence-wait BLOCK/SPIN CMake regression tests passed")
