---
name: android-app-update
description: Analyze, design, implement, or review a reusable Android in-app update system covering automatic and manual checks in one shared session, forced updates, stable/beta channels, five-or-more CDN metadata and APK sources, SHA-256 and APK identity verification, scrollable Markdown update dialogs, download progress, installation authorization, and GitHub Actions-generated update JSON. Use when Codex needs to inspect an Android/Gradle project, produce a Chinese update requirements document, add or migrate update functionality, or review update, download, installation, metadata, or release workflow code.
---

# Android App Update

## Workflow

1. Read project instructions and locate Android modules.
2. Search source, resources, manifests, tests, Gradle files, update JSON, and Actions workflows with `rg`. Exclude generated builds, dependencies, decompiled work directories, and `.git`.
3. Trace trigger -> shared check session -> CDN selection -> policy -> dialog -> download -> SHA-256/APK verification -> permission -> installer -> Actions publishing.
4. Record evidence as `absolute-path:line`. Separate existing behavior, inferred behavior, and proposed behavior.
5. Read [requirements.md](references/requirements.md) before writing requirements, implementing, reviewing, or testing. Treat it as the single authoritative product requirements document, including CDN counts and cache-conflict handling.
6. Read [source-findings.md](references/source-findings.md) when comparing iKanAPP, Guise Reborn, or Zhiliao.
7. Remove project-specific behavior unless the user explicitly requests it. Do not include iKanPro branching, Root silent installation, remote business configuration, or product-specific startup authorization.
8. Produce or implement the target-specific subset, then verify parsing, policy, shared-session concurrency, UI states, CDN fallback, SHA-256, installation recovery, and Actions output.

## Non-negotiable design rules

- Compare versions with monotonic `versionCode`; use `versionName` only for display/channel selection.
- Make automatic and manual checks share one in-flight session. A manual request joining an automatic request must receive a visible result.
- Manual checks bypass skipped-version suppression and stale completed-session cache.
- Support a durable forced-update boundary such as `maxForcedVersionCode`; forced updates override skip/ignore state.
- Configure at least five HTTPS CDN/source endpoints for metadata checks and at least five distinct-host HTTPS CDN endpoints for APK downloads.
- Separate metadata authority from CDN redundancy. Prefer the unique authority; reject rollback, same-revision conflicts, and invalid payloads instead of accepting the first or majority response.
- Require HTTPS, timeouts, response-size limits, schema/channel validation, and cache-busting.
- Require SHA-256 for every APK. Before installation also verify package name, expected versionCode, and signer continuity when updating the same app.
- Use one dialog with three actions for optional updates: skip this version, ignore for now, download and install. A manual-update entry shows a red dot after “ignore for now” while an update remains known.
- Make version, publication time, and Markdown notes one bounded scroll region; keep three actions fixed and keep dialog geometry stable while progress changes.
- Publish update JSON through GitHub Actions after the Release APK exists. Read version from the APK, calculate SHA-256, require at least five distinct download CDN hosts, validate JSON, then update the stable/beta target atomically.
- Never publish a Release, upload an APK, change forced-update policy, or install a package without explicit user authorization.

## Output

Default to Chinese Markdown. Include scope, flows/state, functional requirements, JSON schema, CDN consistency, security, Actions workflow, failure handling, acceptance matrix, and target-project decisions. Use clickable absolute-path evidence in Codex responses.
