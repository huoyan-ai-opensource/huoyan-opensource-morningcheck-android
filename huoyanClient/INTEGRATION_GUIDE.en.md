# Huoyan Morning Check SDK Android Integration Guide

Client JAR 2.0.0 · Android developers

This guide covers connecting an Android host to the morning check SDK shell, activating a model, managing face samples, and processing identity, head, and hand results. The host owns cameras, employee records, the inspection workflow, and result storage. The JAR communicates with the separately installed SDK shell through AIDL or WebSocket; the shell owns authorization and model inference.

Read chapters 1–6 in order for a first integration. Chapters 7–10 provide the API reference, request rules, deployment options, and acceptance checks. Use `huoyan-morningcheck-client-2.0.0.jar` with a compatible morning check SDK shell. App, JAR, and shell versions are independent.

## 1 Environment and project configuration

Place the distributed JAR in the host's `app/src/main/libs/`. Include only one client version. Do not include both the source module and the distributed JAR in the same App.

### 1.1 Gradle configuration

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
    implementation files(
        'src/main/libs/huoyan-morningcheck-client-2.0.0.jar')
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    coreLibraryDesugaring(
        'com.android.tools:desugar_jdk_libs:2.1.5')
}
```

The main JAR does not embed OkHttp, Kotlin, or Okio. Gradle resolves OkHttp's transitive dependencies when using Maven. For an offline integration, the distribution ZIP's `libs/` directory contains the runtime dependencies. Do not import the same libraries twice.

### 1.2 Manifest configuration

```xml
<uses-permission android:name="android.permission.INTERNET" />
<queries>
    <package android:name="cc.huoyan.morningcheck" />
</queries>
```

Add these elements under the root `manifest` element. The host must separately declare and request any camera or package installation permissions it needs. The JAR does not merge a Manifest or request permissions for the host.

When using `ws://`, the default HTTP download address, or HTTP image URLs returned by the SDK, allow the required destinations through the host's Network Security Config or application cleartext setting. A global allowance should be limited to controlled deployments that require it; the host decides the production policy.

### 1.3 Packaging and shrinking

Keep the Java resource `raw/config` from the JAR. Do not add another resource with the same name. The JAR includes rules that preserve Parcelable `CREATOR` fields. If repackaging the JAR or stripping `META-INF`, retain the supplied `consumer-rules.pro` rules and validate a Release build.

## 2 Connection and lifecycle

Prefer one `HuoyanClient` in an application-scoped session, with pages subscribing to that session. Page navigation does not require another `connect`. Each page still owns its camera, UI subscriptions, and pending page requests.

### 2.1 Create the client

```java
HuoyanClient client = new HuoyanClient(
        context.getApplicationContext(),
        new HuoyanClient.Listener() {
            @Override
            public void onConnection(
                    boolean connected, String message) {
                // This only reports the transport connection.
            }

            @Override
            public void onStatus(JSONObject status) {
                // Read isReady() from the shared client.
            }
        });
client.connect(SdkConfig.automatic(SdkConfig.DEFAULT_WS_ENDPOINT));
```

Import `cc.huoyan.morningcheck.sdk.client.HuoyanClient`, `cc.huoyan.morningcheck.sdk.client.SdkConfig`, and `org.json.JSONObject`. Status and business callbacks are delivered on the main thread by default. Keep disk access, downloading, and lengthy image processing out of callbacks.

### 2.2 Select a transport

| Configuration | Selection rule |
| --- | --- |
| `SdkConfig.automatic(url)` | Uses AIDL for a loopback address on API 27+; otherwise uses WebSocket at the supplied URL |
| `SdkConfig.aidl()` | Forces a local SDK binding; rejected below API 27 |
| `SdkConfig.webSocket(url)` | Forces WebSocket to a local or LAN device |

The default endpoint is `ws://127.0.0.1:18087/`. A remote example is `ws://192.168.1.99:18087/`; replace the IP with the SDK device's address. Use `webSocket` explicitly when you need a local custom port to use WebSocket rather than automatic AIDL selection.

The local binding uses package `cc.huoyan.morningcheck`, service `cc.huoyan.morningcheck.app.comm.SdkCommService`, and action `cc.huoyan.morningcheck.SdkCommService`.

`connect` automatically runs `init`, then queries status, capabilities, and class tables. Initialization includes the host APK's `versionName`. Do not unconditionally call `init` again from `onConnection`. Current capability API versions are 4 for AIDL and 2 for WebSocket; read the actual capability snapshot.

`close()` disconnects and stops reconnection; a later explicit `connect` can reuse the client. `destroy()` permanently releases it. Switching configurations terminates old requests without transferring or replaying them on another device.

## 3 Activation and model preparation

For initial activation, collect an activation code, query available models, let the user select a model, submit activation, then wait for model readiness. If the SDK already has authorization, query its state first instead of requiring the activation code on every launch.

### 3.1 Query models and activate

```java
client.queryLicenseModels(activationCode, (code, message, data) -> {
    if (code != 0) {
        // Report the actual error and stop this activation attempt.
        return;
    }
    JSONArray models = data.optJSONArray("models");
    // Let the user select a model; an empty list has no default.
});

// selectedModelCode comes from the selected model_code.
client.activate(activationCode, selectedModelCode,
        (code, message, data) -> {
            if (code == 0) client.refreshStatus(statusCallback);
        });
```

`activationCode`, `selectedModelCode`, and `statusCallback` are host-provided values. Import `org.json.JSONArray`. Each model entry contains `model_code`, `model_name`, `chip`, and `current`. Display the model name but submit the model code unchanged.

An authorized device can use `queryDeviceModels(callback)` to query device models without business parameters or a locally stored activation code. Switching to a different model still needs the selected `model_code` and a valid activation code. Selecting the current model or cancelling should not submit a change.

### 3.2 Determine readiness

| Required condition | Meaning |
| --- | --- |
| Connected | The transport connection is still active |
| `sdk_state` is `READY` or `OFFLINE` | The SDK can provide local functions subject to the other readiness conditions |
| `model_ready` is `true` | Model preparation is complete |
| `certificate_valid` is `true` | The activation certificate is valid |
| `key_lost` is not `true` | The device identity key is not lost |

`client.isReady()` checks these conditions. Neither `init=0` nor `activate=0` is a substitute. After successful activation, query or await status updates; do not repeatedly call `init` while asynchronous model preparation is underway.

`OFFLINE` allows local recognition, detection, and registration when the model and certificate remain valid. It does not mean cloud operations are available. The shell rejects cloud requests while identity calibration is incomplete and can publish `READY` when calibration recovers. The host need not reconnect or replay `init` for that transition.

`getDownloadProgress(callback)` returns `downloaded` and `total`; `total=0` means the total is unknown. Download progress does not replace the activation result. Certificate timestamps use seconds; `state_updated_at` uses milliseconds.

An expired certificate restricts recognition, detection, and registration. Queries and forgetting are not uniformly rejected merely because of expiry, but may still be restricted by an unloaded model, identity rotation, device disablement, or other conditions. Use the actual response.

## 4 Image input and coordinate conversion

### 4.1 Input format

| Item | Requirement |
| --- | --- |
| Pixel format | Contiguous HWC RGB888, in R, G, B order |
| Byte length | Exactly `width × height × 3` |
| Per-image limit | 32 MiB |
| Dimensions | Positive integer dimensions of the frame actually submitted |
| Buffer ownership | Do not modify or reuse the submitted array before its callback |

`identify`, `detectFace`, `checkHead`, `checkHand`, and `register` accept raw frames in this format. Do not pass JPEG, PNG, NV21, or a Bitmap ARGB array directly. `uploadPhoto` is a separate compressed-image API and uses a different input format.

### 4.2 Convert a Bitmap

The following conversion assumes rotation and orientation have already been applied. Prefer RGB888 directly from the production capture pipeline instead of allocating a Bitmap for every frame.

```java
static byte[] toRgb888(Bitmap bitmap) {
    int width = bitmap.getWidth(), height = bitmap.getHeight();
    int[] pixels = new int[width * height];
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
    byte[] rgb = new byte[pixels.length * 3];
    for (int i = 0, j = 0; i < pixels.length; i++) {
        int value = pixels[i];
        rgb[j++] = (byte) ((value >> 16) & 0xff);
        rgb[j++] = (byte) ((value >> 8) & 0xff);
        rgb[j++] = (byte) (value & 0xff);
    }
    return rgb;
}
```

Constrain the Bitmap size before calling this method so that the per-image limit is respected. Capture and conversion belong on background threads; the UI only renders results. Keep at most one image request in flight per transport, submitting the next after the previous response rather than queueing every preview frame.

### 4.3 Bounding box coordinates

`x` and `y` are the normalized top-left position in the submitted frame; `w` and `h` are normalized width and height. Frame pixel coordinates are `left=x×width`, `top=y×height`, `right=(x+w)×width`, and `bottom=(y+h)×height`.

When drawing on the preview, also apply the camera's rotation, mirroring, scale, and crop offset. Multiplying by the view dimensions alone produces displaced boxes if frame and preview orientations differ.

## 5 Face enrollment and sample management

A person's ID and a sample UID are different identifiers. `personId` identifies an employee; `uid` identifies one successfully registered sample. An employee can have multiple samples.

### 5.1 Register and retrieve images

```java
long personId = 1001L; // Stable positive ID in the host's roster.
client.register(rgb, width, height, personId,
        (code, message, data) -> {
            if (code != 0) return;
            long uid = data.optLong("uid", 0);
            if (uid <= 0) return; // No valid sample ID was returned.
            String imageUrl = data.optString("image_url", "");
            // Keep the UID mapping; load available images in background.
            client.userDetail(personId,
                    (detailCode, detailMessage, detail) -> {
                        if (detailCode != 0) return;
                        JSONArray items = detail.optJSONArray("items");
                        // Process each UID together with its availability and URL.
                    });
        });
```

| `items` field | Type and meaning |
| --- | --- |
| `uid` | Positive int64 sample ID, used to delete that sample |
| `image_available` | Boolean indicating whether the original image is readable |
| `image_url` | Original image HTTP URL; empty when unavailable |
| `mime_type` | `image/jpeg` for an available JPEG; empty when unavailable |

Prefer `items`. Fall back to `uids` or `samples` only when an older shell omits `items`. A missing image does not mean enrollment is missing: retain the UID and show that the image is unavailable. The current SDK saves the submitted frame as JPEG; it does not crop the face automatically or upload to COS during `register`. An image-saving failure does not turn an already successful sample registration into a registration failure.

### 5.2 Delete one sample or clear a person

`forgetByUid(uid, callback)` deletes one sample. `forget(personId, callback)` clears all samples for that employee without deleting the host's employee record. `userCount(callback)` returns the number of registered people in the shell, not the size of the host roster.

Use positive Java `long` values for both `personId` and `uid`. Do not convert them through `double` or use an image's display sequence number as its UID. After `40009`, query `userDetail` to reconcile actual samples before registering again or repeating a delete.

The SDK dynamically allocates the image HTTP port; it is not the WebSocket port 18087. Query URLs again after reconnecting. Do not construct URLs or permanently reuse an old address. Remote WebSocket responses use the SDK device address; local AIDL normally returns a loopback address.

## 6 Identity recognition and head and hand detection

### 6.1 Identify a person or detect a face

```java
client.identify(rgb, width, height, (code, message, data) -> {
    if (code != 0) return;
    boolean matched = data.optBoolean("matched", false);
    if (matched) {
        long personId = data.optLong("person_id", 0);
        double similarity = data.optDouble("similarity");
        // Look up the host employee record by personId.
    }
});
```

`detectFace(rgb, width, height, maxFaces, callback)` detects face boxes without identifying employees. Check the `detect_face` capability in `getCapabilities()` first; do not call this API on an older shell lacking the capability.

A successful `detectFace` response contains `faces[]`; each entry has `x`, `y`, `w`, `h`, `score`, and `landmarks`. `landmarks` contains 10 normalized coordinate values. Only `code=0` with `faces=[]` means no face was detected in this request. `identify` returning `matched=false` only means there was no successful match; it cannot establish that no face was present.

### 6.2 Check the head and hands

```java
client.checkHead(rgb, width, height, 32,
        (code, message, data) -> {
            if (code != 0) return;
            JSONArray boxes = data.optJSONArray("boxes");
            // Resolve class_id through the head class table.
        });
```

After the head response and the business transition to the hand stage, call `checkHand(rgb, width, height, 32, handCallback)` with the host callback. Do not submit head and hand frames simultaneously. `maxDetections` is 1–64. `maxFaces` must be a positive `int`; the shell caps values above 64.

A detection can return multiple targets. `boxes` is their list; `boxes[]` below means any list entry. For example, `boxes[0].score` is the first target's confidence.

| Detection field | Meaning and use |
| --- | --- |
| `boxes[].class_id` | Target category code; find the same `classId` in the corresponding class table |
| `boxes[].score` | Detection confidence from 0 to 1; higher means the model is more confident; apply business thresholds as needed |
| `boxes[].x` | Normalized left position; multiply by submitted image width for pixel x |
| `boxes[].y` | Normalized top position; multiply by submitted image height for pixel y |
| `boxes[].w` | Normalized box width; multiply by submitted image width |
| `boxes[].h` | Normalized box height; multiply by submitted image height |

These coordinates are normalized to the submitted image, not pixel values. Apply rotation, mirroring, and scaling as described in chapter 4 when rendering them on a camera preview.

Resolve `checkHead` results using the `head` class table, and `checkHand` results using the `hand` table. Both tables use the following fields:

| Class table field | Meaning |
| --- | --- |
| `classId` | Category code corresponding to result `class_id`; note the different field names |
| `name` | Category name for display |
| `nameEn` | English category name |

Use `refreshClassTable(callback)` to query the tables; `getClassTable()` only reads the cache. The host interprets masks, work caps, hand orientation, and abnormalities using the returned categories and its business rules. Do not hardcode a category number to a permanent business meaning.

## 7 Public API reference

All named business methods take `HuoyanClient.Callback` as the last parameter and return a `String requestId`. The tables omit that callback parameter. Image parameters `rgb`, `width`, and `height` follow chapter 4.

### 7.1 Status and authorization

| Method and parameters | Purpose |
| --- | --- |
| `init()` | Perform one explicit initialization; normal connection already initializes |
| `queryLicenseModels(String activationCode)` | Query models available to the supplied activation code |
| `queryDeviceModels()` | Query authorized device models without business parameters |
| `activate(String activationCode, String modelCode)` | Activate the selected model |
| `refreshStatus()` | Query status and update its cache |
| `refreshCapabilities()` | Query capabilities and update their cache |
| `getCertInfo()` | Query the certificate, SDK version, and related metadata |
| `getDownloadProgress()` | Query model download byte progress |

### 7.2 Recognition and person samples

| Method and parameters | Purpose |
| --- | --- |
| `identify(rgb, width, height)` | Identify an employee |
| `detectFace(rgb, width, height, int maxFaces)` | Detect face boxes and landmarks |
| `checkHead(rgb, width, height, int maxDetections)` | Detect head targets |
| `checkHand(rgb, width, height, int maxDetections)` | Detect hand targets |
| `register(rgb, width, height, long personId)` | Register one sample for an employee |
| `forget(long personId)` | Clear all samples for an employee |
| `forgetByUid(long uid)` | Delete one sample |
| `userCount()` | Query the registered person count |
| `userDetail(long personId)` | Query sample UIDs and image metadata |
| `refreshClassTable()` | Query the current model's head and hand class tables |

Invalid arguments to named methods generally throw `IllegalArgumentException` synchronously; this does not mean a request was sent. Successful response shapes depend on the method. Parse the success structure only for `code=0`.

### 7.3 Optional cloud APIs

| Method and parameters | Purpose |
| --- | --- |
| `bindDevice(String externalShopId)` | Bind an external shop using the distribution account |
| `setBaseUrl(String url)` | Change the SDK cloud service URL |
| `setCloudStatus(boolean enabled)` | Set the SDK cloud hosting switch |
| `getCloudStatus()` | Query the actual cloud hosting switch |
| `uploadPhoto(byte[] encoded, String format)` | Upload a JPEG or PNG image |
| `submitFeedback(JSONObject feedback)` | Submit feedback; fields are described in chapter 9 |

These three business API groups contain 24 named methods in total. `setBaseUrl` changes the SDK's cloud endpoint, which is separate from the host App's own server address.

### 7.4 Client controls and caches

| Method | Behavior |
| --- | --- |
| `connect(SdkConfig config)` | Connect asynchronously and initialize automatically |
| `isConnected()` / `isReady()` | Read transport connectivity / local business readiness |
| `getActiveChannel()` | Selected transport; null before connect or after close; retained during automatic reconnect |
| `getStatus()` | Read-only cached status; empty object when no cache exists |
| `getCapabilities()` | Read-only cached capabilities |
| `getClassTable()` | Read-only cached class tables |
| `cancel(String requestId)` | Stop waiting for a request |
| `close()` | Disconnect, allowing another explicit connect |
| `destroy()` | Permanently release the client |

### 7.5 Generic requests

`request(String command, JSONObject params, byte[] image, Callback callback)` accepts unified protocol parameters. Prefer named methods to avoid assembling fields manually. Parameters are copied on submission; keep the image buffer unchanged until the callback.

A generic `register` request uses `person_id`, `width`, `height`, and `format="rgb"`. `queryDeviceModels` accepts no business parameters. The client maps `getClassTable` to WebSocket `get_class_table` and handles field differences between AIDL and WebSocket.

## 8 Callbacks and request control

### 8.1 Callback and request ID

```java
String requestId = client.userDetail(personId,
        (code, message, data) -> {
            if (code == 0) {
                // Consume the successful result for this request.
            } else {
                // Use the error code and actual message.
            }
        });
// When the page no longer needs to wait: client.cancel(requestId);
```

Cancellation only stops waiting. It does not establish that the SDK stopped executing or that its in-flight image slot was immediately released. Discard callbacks for an old page or employee after navigating or switching employees. Do not let callbacks from a previous client lifecycle update a new page.

### 8.2 Common error codes

| Code | Meaning and handling |
| --- | --- |
| `0` | Success; parse data according to this API |
| `-20001` | Not connected or definitely not sent; resolve connection before submitting the business request |
| `-20002` | Waiting cancelled; an operation already started in the shell is not necessarily revoked |
| `-20003` | Invalid client parameters or account configuration; correct them before submission |
| `40010` | Busy or explicitly rejected; reduce concurrent requests and use business-level backoff |
| `40009` | Disconnection or timeout after sending; the outcome is unknown; reconcile actual state first |
| `-3` and other shell errors | Preserve the real cause and inspect status; do not label every NotReady error as cleanup |

Do not automatically replay writes such as registration, deletion, binding, or feedback after `40009`. Query person details, binding state, or other actual state before deciding whether a manual retry is appropriate.

### 8.3 Waiting budgets

The client waits up to 130 seconds for `init`, `activate`, and `bindDevice`; 70 seconds for `uploadPhoto` and `submitFeedback`; and 40 seconds for other commands. Before sending `bindDevice`, waiting for the current automatic initialization has a separate 130-second limit.

The shell can time out earlier. A client budget neither extends server execution nor cancels a cloud write. Internal connection initialization retries only confirmed transient failures within a bounded budget. Explicit `init(callback)` returns one actual response.

## 9 Deployment and optional APIs

### 9.1 Install the SDK shell

The default deployment address retains its original spelling:

`http://f.huoyan.cc/apk/morningcheck_sdk_lastest.apk`

`cc.huoyan.morningcheck.sdk.client.deployment.SdkShell` provides the package name, installed version lookup, and default download URL. `SdkApkDownloader(url, directory)` downloads to an existing host-provided cache directory. `start(listener)` begins downloading and `cancel()` stops it. Start a downloader instance only once.

Download callbacks run in the background. A negative total size means unknown. The host owns file verification, installation permission, FileProvider setup, installer UI, and cleanup of a successful download. Failed or cancelled downloads remove incomplete files. The client holds no Activity and does not install automatically. Allow the required cleartext destination when using the default HTTP URL.

### 9.2 Bind a shop

```java
client.bindDevice(externalShopId, (code, message, data) -> {
    if (code != 0) return;
    JSONObject response = data.optJSONObject("response");
    // Require nonempty response.account_id and response.shop_id.
});
```

Pass `externalShopId` unchanged. The client reads the distribution account from `raw/config` in the JAR. Only `code=0` and valid ownership fields in `data.response` establish complete ownership. `data.request` is only an echo. An account configuration error explicitly reports that binding was not sent.

Binding does not activate the SDK or enable cloud hosting. While initialization is pending, the client keeps the binding request locally and submits it once initialization finishes; it does not additionally require model `READY`.

### 9.3 Upload images and submit feedback

`uploadPhoto(encoded, "jpeg", callback)` and `uploadPhoto(encoded, "png", callback)` accept the corresponding compressed image bytes. Read `data.photo_file_url` on success. `submitFeedback(feedback, callback)` takes `request_id`, `photo_file_url`, optional `feedback_type`, and a `goods` array.

Each goods entry can contain `goods_id`, `goods_name`, `goods_plu`, `source`, `selected`, `distance`, `width`, `height`, `x`, and `y`. Use these optional APIs only when the business needs them. Face enrollment does not implicitly upload a photo or submit feedback.

The distribution account associates bindings with a customer; it is not client access authentication. This client version adds no pairing, AIDL authentication, or WebSocket authentication. Possession of the JAR is not proof of trusted access.

## 10 Integration checks and troubleshooting

### 10.1 Suggested acceptance sequence

| Step | Check |
| --- | --- |
| 1 Build and install | Debug and Release builds succeed; the SDK shell is installed |
| 2 Connect | The expected transport is selected; remote WebSocket reaches the intended device |
| 3 Authorize | The activation code selects a model and `isReady()` eventually becomes true |
| 4 Manage samples | Enrollment returns a new UID; details load available images; single-delete and clear work |
| 5 Identify and detect | Enrolled staff match correctly; no-face and unmatched-staff cases remain distinct |
| 6 Render detections | Rotation, mirroring, and boxes align; head and hand class tables are applied correctly |
| 7 Exercise lifecycle | Backgrounding, reconnection, and employee switching discard stale responses without duplicate writes |
| 8 Recover failures | Disconnection, busy, certificate expiry, and unknown outcomes have appropriate handling |

### 10.2 Common problems

| Symptom | Check first |
| --- | --- |
| AIDL cannot bind | Package visibility, installation, Android API level, and contract version |
| WebSocket cannot connect | Address, port, LAN reachability, cleartext policy, and whether the shell started |
| Activation succeeds but recognition is unavailable | Actual SDK state, model preparation, and certificate; do not repeatedly stack init requests |
| Invalid image arguments | RGB order, exact length, dimensions, byte limit, and buffer reuse |
| Displaced boxes | The submitted frame and preview rotation, mirroring, crop, and scaling matrices |
| UID exists but no image is available | `image_available`; missing historical images do not remove enrollment |
| Image URL stops working | Query `userDetail` again after reconnecting; do not reuse an old dynamic port |
| Employee number cannot be used directly for enrollment | The API requires a positive long; use a stable internal person ID |

For diagnostics, record the client version, actual transport, request ID, command, error code, message, and relevant state snapshot. Redact activation codes and personal information. Mock tests and example compilation do not establish camera quality, authorization connectivity, or model accuracy on actual hardware.

This guide follows the public client JAR 2.0.0 API and its paired shell contract. After replacing the JAR or shell, verify capability versions, image interfaces, person ID types, and activation readiness again.
