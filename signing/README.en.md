# Experimental signing key

[简体中文](README.md)

`xinhai-debug-demo.jks` is the public experimental key already distributed with Huoyan's open-source retail project. This project reuses the same key for noncommercial experiments and test Release builds only. Do not use it for commercial publication or treat it as a trusted production identity.

| Setting | Public value |
| --- | --- |
| Keystore | `signing/xinhai-debug-demo.jks` |
| Format | JKS |
| Alias | `xinhai-debug-demo` |
| Store password | `xinhai-debug-demo` |
| Key password | `xinhai-debug-demo` |

Release builds use this key by default. Debug builds continue to use the local Android debug key. APKs with different signing keys normally cannot update each other. Back up employee data, settings, and inspection records before changing keys; do not erase production data merely to install a test build. Increment `versionCode` for updates signed with the same key.

For production, supply all four properties in the untracked `local.properties`: `signing.storeFile`, `signing.storePassword`, `signing.keyAlias`, and `signing.keyPassword`. Relative keystore paths resolve from the repository root. An incomplete configuration fails the build. See [local.properties.example](../local.properties.example). Never commit production private keys or passwords.

These restrictions apply to the public experimental key. See [USAGE.md](../USAGE.md) for the use and activation rules covering the project's own source code.
