# UMAssisted-private (dev)

Closed-source 1.0 alpha (REQ-P3). Desktop brain: Grok Build on QODESH.
Sibling public tree: `../UMAssisted` (requirements + capture scripts).

- Overlay: `docs/desktop-agent/`.
- Build: `./gradlew :app:assembleDebug` (or `gradlew.bat` on Windows).
- Tests: `./gradlew :app:testDebugUnitTest`.
- CI: `.github/workflows/ci.yml` on GitHub-hosted ubuntu (no device).
- `local.properties` is gitignored. Point `sdk.dir` at this machine's SDK.
- `captureScreen` expects `../UMAssisted/tools/capture_screen.sh`.
- Do not inject taps into the live Uma client (REQ-DEV1/DEV2).
- Do not assemble-release or publish unsigned APKs. Signing is local
  `keystore.properties` only.
