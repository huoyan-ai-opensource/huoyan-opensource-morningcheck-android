# Huoyan Morning Check Android Client

[简体中文](README.md) | English

This module produces the client JAR used to connect an Android App to the separately installed Huoyan morning check SDK shell. The host owns its cameras, employee records, UI, inspection workflow, and result storage. The JAR owns AIDL/WebSocket communication, response correlation, timeouts, and reconnection; it contains no UI resources or native inference libraries.

Use the [English integration guide](INTEGRATION_GUIDE.en.md) for the full API and examples. A [Chinese edition](INTEGRATION_GUIDE.md) is also available.

## Distribution contents

| Location in the ZIP | Contents |
| --- | --- |
| `libs/huoyan-morningcheck-client-2.0.0.jar` | Main client JAR |
| `libs/` | Required runtime JARs, including OkHttp and its dependencies |
| `docs/huoyan-morningcheck-client-2.0.0-integration-guide.zh-CN.docx` | Chinese Word integration guide |
| `docs/huoyan-morningcheck-client-2.0.0-integration-guide.en.docx` | English Word integration guide |
| `INTEGRATION_GUIDE.md`, `INTEGRATION_GUIDE.en.md` | Full integration guides in Markdown |
| `samples/` | Java host example compiled independently against the generated JAR |
| `consumer-rules.pro` | Rules for host shrinking configurations |
| `PACKAGING.md`, `tools/set_account.py` | Customer account configuration instructions and tool |

Both Word guides are maintained in `app/src/main/libs/` in the source project. Their complete filenames and content must remain identical in the project, ZIP, and any future Release Assets. See `docs/releasing.md` in the project root or distribution ZIP for the release requirements. The source repository is [huoyan-ai-opensource/huoyan-opensource-morningcheck-android](https://github.com/huoyan-ai-opensource/huoyan-opensource-morningcheck-android); no GitHub Release has been published yet, and publication is manual only.

## Add the JAR to a host

Copy the main JAR into the host's `app/src/main/libs/`:

```groovy
android {
    defaultConfig { minSdk 21 }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_11
        targetCompatibility JavaVersion.VERSION_11
        coreLibraryDesugaringEnabled true
    }
}

dependencies {
    implementation files('src/main/libs/huoyan-morningcheck-client-2.0.0.jar')
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.1.5'
}
```

The main JAR does not bundle OkHttp, Kotlin, or Okio. Use Maven dependencies as above, or use the runtime JARs from the ZIP's `libs/`, without importing the same dependencies twice. Include exactly one main client JAR; do not also link the source module.

The host must declare Internet permission and SDK package visibility:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<queries>
    <package android:name="cc.huoyan.morningcheck" />
</queries>
```

Also configure the required cleartext destinations if using `ws://` or HTTP image/download URLs. Camera and installation permissions remain the host's responsibility.

## Connect and manage the session

```java
HuoyanClient client = new HuoyanClient(context.getApplicationContext(), listener);
client.connect(SdkConfig.automatic(SdkConfig.DEFAULT_WS_ENDPOINT));
// Remote SDK: client.connect(SdkConfig.webSocket("ws://192.168.1.99:18087/"));
```

The public entry points are `cc.huoyan.morningcheck.sdk.client.HuoyanClient` and `SdkConfig`. Automatic transport selection uses AIDL for local loopback on API 27+, and WebSocket otherwise. The default WebSocket endpoint is `ws://127.0.0.1:18087/`. Initialization includes the host APK's actual `versionName` automatically.

Keep an application-scoped session instead of reconnecting on each page. Callbacks are delivered on the main thread by default. `close()` disconnects and stops reconnection but permits a later explicit connect; `destroy()` permanently releases the instance.

Connection, `init=0`, and `activate=0` do not establish readiness. `isReady()` requires `READY` or `OFFLINE`, a ready model, a valid certificate, and no lost key. `OFFLINE` can provide local recognition, detection, and enrollment but does not establish cloud API availability. Let the SDK shell enforce each command's actual admission rules.

Submit contiguous RGB888 frames with exactly `width × height × 3` bytes and a 32 MiB maximum. Keep one image request in flight per transport and do not mutate its array before the callback. A `40009` response means the outcome is unknown; reconcile actual state before repeating a write. Cancellation stops waiting and does not revoke an operation already executing in the shell.

## Configure a distribution account

The JAR includes the Java resource `raw/config`. Preserve it when building the host APK and do not add another resource with that name. The distribution account associates shop bindings with a customer; it does not authenticate arbitrary clients or bypass device activation.

The Python 3 tool can replace the account without recompiling Java:

```bash
python huoyanClient/tools/set_account.py --jar huoyanClient/build/outputs/jar/huoyan-morningcheck-client-2.0.0.jar --account-id 1234567890123456789 --output huoyanClient/build/outputs/jar/huoyan-morningcheck-client-2.0.0-customer.jar
```

Replace the example account with a verified customer account. The output must not already exist, and its parent directory must exist. The tool rejects invalid accounts, duplicate entries, signed JARs, and JARs lacking the configuration. See [PACKAGING.md](PACKAGING.md) for the full Chinese instructions.

Replace the main JAR inside the ZIP and the host's local dependency with the configured JAR, keeping the dependency filename and only one main JAR. Recalculate distribution checksums. Re-running a Gradle packaging task uses the source account again, so customer distributions must be prepared again. Changing a JAR on a computer does not change an already installed APK.

## Build and verify

The root `build.gradle` defines `ext.morningcheckClientVersion = '2.0.0'`. The module's JAR/ZIP filenames, JAR manifest, and App dependency use that value. App builds consume the committed JAR and do not rebuild this module automatically.

From the project root with JDK 17:

```bash
bash gradlew :huoyanClient:packageReleaseJar
bash gradlew :huoyanClient:updateAppSdkJar
bash gradlew :huoyanClient:testDebugUnitTest :huoyanClient:compileExample :huoyanClient:clientDistribution
python -m unittest discover -s huoyanClient/tools -p 'test_*.py'
```

On Windows, replace `bash gradlew` with `.\gradlew.bat`. `packageReleaseJar` writes to `huoyanClient/build/outputs/jar/`; `clientDistribution` writes to `huoyanClient/build/distributions/`. Run `updateAppSdkJar` explicitly after source or version changes to refresh the App's JAR before rebuilding it. Update both Word guide filenames and documentation when changing the JAR version.

Tests cover protocol mapping, request association, cancellation, unknown outcomes, reconnection, and initialization behavior using simulated transports. They do not establish model accuracy, hardware compatibility, activation, or cloud acceptance on a physical device.
