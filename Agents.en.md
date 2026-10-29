# Agent instructions

[简体中文](AGENTS.md) | English

Follow the workspace rules and these repository-specific rules when working on `huoyan-opensource-morningcheck-android`.

## Dialogs and feedback

- Use the project's custom list dialogs for choices and custom AlertDialog style for confirmation and risk notices. Do not use default system confirmation styles.
- Show errors, exceptions, and success through the corresponding `ToastEx` error/success method. Do not display operation results in page, card, or form text, and do not infer the result type from the message text.
- Display request, read, or processing progress only in a Loading dialog. Close it on completion, failure, or page destruction, avoiding leftover overlays and duplicate submissions.
- Do not expose SDK readiness or key diagnostics in normal UI. Display only business states explicitly requested by the user; that exception does not allow extra internal states or operation results.
- Support the application's maximum 130% font size in every page, list, form, navigation element, and dialog. Allow wrapping or content scrolling and keep essential actions reachable.

## Code style

- Follow adjacent code and the project's existing reformat. Preserve indentation, wrapping, blank lines, and comment style.
- Java uses four spaces, opening braces on declaration lines, annotations on their own lines, and expanded method bodies. Retain existing short guard statements.
- XML uses four spaces, one attribute per line, blank lines between sibling controls, and separate lines for style items. Do not compress layouts or methods into single lines.
- Limit formatting to the affected code; do not reformat unrelated files or overwrite the user's formatting.

## Build and release requirements

- The App package is `cc.huoyan.opensource.morningcheck`. Do not change the morning-check SDK service package, AIDL contract, or public client JAR API.
- Use JDK 17, Gradle 8.14.3, and Android SDK / Build Tools 35. Java source and target compatibility are 11. On Windows, follow the workspace toolchain rules: select a JDK from `D:/JDK`, set `GRADLE_USER_HOME=D:/Gradle`, and reuse caches first.
- The App consumes the committed client JAR in `app/src/main/libs/`. After changing `huoyanClient` source, explicitly run `:huoyanClient:updateAppSdkJar`; ordinary App builds do not rebuild the JAR.
- Maintain the client version in `morningcheckClientVersion` in the root `build.gradle`. Manage the App version separately. When upgrading the client, update the JAR, guide source filenames, documentation, and compatibility table together.
- Keep the Word source files in `app/src/main/libs/`, named `huoyan-morningcheck-client-<JAR-version>-integration-guide.zh-CN.docx` (Chinese) and `huoyan-morningcheck-client-<JAR-version>-integration-guide.en.docx` (English).
- Include both Word guides in `docs/` inside the delivery ZIP and as separate GitHub Release Assets. Filenames, versions, and contents must match the source files. Stop publication on a missing, empty, or mismatched file; an English guide outside the ZIP alone is insufficient.
- Display the full project filenames in Assets. Do not set custom labels or append `#label` when uploading. Include every attachment except the checksum file itself in `SHA256SUMS`; update checksums and release notes when attachments change.
- GitHub checking and release workflows must use manual `workflow_dispatch` only. Do not enable builds or publication on pushes, pull requests, or tags, and do not restore CNB Release download links.
- Run the full local test and build commands before changing a workflow, then verify the actual outputs after editing. Do not describe a local Windows check as a successful GitHub Ubuntu run or invent repositories, Releases, download links, or validation records.
- Never commit production signing keys or passwords. The only exception is the already-public experimental key `signing/xinhai-debug-demo.jks`, restricted to noncommercial experiments and test Release builds. Configure all four production signing properties in the untracked `local.properties`.
- See [Releasing](docs/releasing.md) (Chinese) for the complete procedure.
