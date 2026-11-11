# Changelog

All notable changes to this project are documented in this file.

## [Unreleased]

### Added
- Added explicit Vulkan A/B selectors for Q5_K/Q6_K base-MMV decode and Q4_K F16/F32 prefill; original overlay source-anchor guards remain mandatory.
- Added benchmark profile generation for Vulkan n_ubatch 32/64/128 and Flash Attention ON/OFF on Vulkan and CPU.

### Changed
- Mali vendor ID 0x13B5 selects base-type Q4_K decode in Vulkan AUTO; Q5_K/Q6_K remain measurement-only overrides.
- Vulkan n_ubatch defaults to 64 when no explicit value is set.
- Native Release flags require -O3 and -DNDEBUG; optional LTO is opt-in.
- Vulkan per-operation profiling is no longer automatically enabled in Debug builds; it requires explicit opt-in.

### Fixed
- EngineConfig uses safe enum parsing, typed Parcelable reads on API 33+, and backward-compatible appended Flash Attention parcel data.
- Flash Attention mode is forwarded explicitly to llama.cpp context parameters.

### Fixed
- Fixed the Android CI build failure caused by the Vulkan backend not finding the installed SPIRV-Headers CMake package during native configuration.
- Hardened the GitHub Actions workflow to build and install SPIRV-Headers before invoking CMake, and to expose the install prefix to the Android native build.
