# Changelog

All notable changes to this project are documented in this file.

## [Unreleased]

### Fixed
- Fixed the Android CI build failure caused by the Vulkan backend not finding the installed SPIRV-Headers CMake package during native configuration.
- Hardened the GitHub Actions workflow to build and install SPIRV-Headers before invoking CMake, and to expose the install prefix to the Android native build.
