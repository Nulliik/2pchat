# Instructions for the previous Android client

This directory contains the Chaquopy-based client. The primary release target is [2pchatGO/android](../2pchatGO/android/README.md).

Follow the repository [AGENTS.md](../AGENTS.md) and [engineering rules](../RULES.md). Build and bridge details are in [ANDROID_INTEGRATION.md](../docs/ANDROID_INTEGRATION.md).

Shared Python sources are maintained in root `messenger/` and copied by Gradle's `syncCanonicalPythonCore`; do not maintain generated copies. This tree remains useful for compatibility tests and comparison. It is not built by the release workflow.

The previous WAT boilerplate referred to absent `workflows/` and cloud deliverables and did not describe this repository.
