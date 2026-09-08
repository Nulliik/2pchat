---
name: 2pchat-impact-analysis
description: Determine direct and transitive impact of a 2PChat code change.
---

Start with:
`git diff --name-only`

Map changed files into:
Android / JNI / Go / Crypto / Session / Protocol / Transport / Discovery / Persistence / Tests.

Then inspect imports/callers/callees and test coverage.

Security impact is HIGH when a change crosses:
- identity -> session
- session -> crypto
- crypto -> protocol
- Go -> JNI
- transport -> policy
- persistence -> key material

Output:
changed components, impacted boundaries, compatibility risks, required reviewers, required tests.
