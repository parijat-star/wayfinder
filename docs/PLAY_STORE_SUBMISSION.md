# Google Play Store Submission & Release Guide

This document contains the ready-to-paste declarations, questionnaire answers, store listing copy, and build commands required to publish **Wayfinder** (`com.droid.wayfinder`) on Google Play for Googlebooks, Chromebooks, tablets, and Android desktop devices.

---

## 1. Building Release Artifacts (`.aab` & `.apk`)

Google Play requires an **Android App Bundle (`.aab`)** for store uploads, while direct GitHub Releases use a universal **APK (`.apk`)**.

```bash
# 1. Build the R8-minified Google Play App Bundle (.aab)
#    Note: app/build.gradle.kts sets bundle.language.enableSplit = false so all
#    packaged locales remain available offline for Android 13+ per-app language preferences.
./gradlew :app:bundleRelease
# Output: app/build/outputs/bundle/release/Wayfinder-release.aab

# 2. Build the R8-minified universal APK (pure Kotlin/JVM — runs on arm64-v8a & x86_64)
./gradlew :app:assembleRelease
# Output: app/build/outputs/apk/release/Wayfinder-release-unsigned.apk
```

When signing for production outside Play App Signing (or before uploading if not using Gradle signing configs):

```bash
# Sign the Play App Bundle with your upload keystore:
jarsigner -sigalg SHA256withRSA -digestalg SHA-256 \
  -keystore upload-keystore.jks \
  app/build/outputs/bundle/release/Wayfinder-release.aab upload

# Align and sign the universal APK for direct distribution:
$ANDROID_HOME/build-tools/36.0.0/zipalign -f -p 4 \
  app/build/outputs/apk/release/Wayfinder-release-unsigned.apk \
  Wayfinder-release.apk
$ANDROID_HOME/build-tools/36.0.0/apksigner sign \
  --ks release-keystore.jks \
  --out Wayfinder-v2.0.apk \
  Wayfinder-release.apk
```

---

## 2. Hosting the Public Privacy Policy URL

Google Play Console (**Policy -> App content -> Privacy policy**) requires an HTTPS URL accessible without authentication.

1. In GitHub, open **Settings -> Pages**.
2. Under **Build and deployment**, set **Source** to **Deploy from a branch**, select branch **`main`** and folder **`/docs`**, and click **Save**.
3. Paste the published URL into Play Console:
   - `https://parijat-star.github.io/wayfinder/privacy.html`

---

## 3. Sensitive Permissions Declarations (`App content -> Sensitive permissions and APIs`)

### 3.1 All Files Access (`android.permission.MANAGE_EXTERNAL_STORAGE`)

- **Permitted Use Case Checkbox**: Select **`File management`** (*Browse, organize, copy, move, rename, archive, and search files across device storage*).
- **Core Purpose Explanation** *(copy & paste into Play Console)*:

```text
Wayfinder is a full-featured desktop file manager designed for Android laptops, Googlebooks, and tablets. Its primary purpose is browsing, organizing, copying, moving, batch-renaming, searching, checksum-verifying, previewing (Quick Look), and archiving/extracting (.zip, .tar, .tar.gz) files and folders across the user's shared internal storage and removable USB/SD volumes. Scoped Storage (ACTION_OPEN_DOCUMENT_TREE) and MediaStore cannot support multi-tab filesystem navigation, Miller columns, dual-pane commander workflows, non-media developer/document file management, or cross-directory archive extraction across arbitrary folders without prompting on every directory.
```

- **Required Video Walkthrough Script (30–45 seconds, `.mp4` / YouTube Unlisted link)**:
  Google Play requires a short screen recording demonstrating the in-app onboarding flow and core file-management workflow:
  1. **0:00–0:08 — Onboarding & Permission Grant**: Launch Wayfinder on a clean install showing the `Storage access needed` screen (`PermissionScreen`), click **Open settings**, toggle **Allow access to manage all files** on, and return to Wayfinder.
  2. **0:08–0:20 — Multi-Tab & Dual-Pane File Management**: Open a second tab in the desktop title bar (`Ctrl+T`), switch to **Dual-Pane Commander** (`Ctrl+4`), and copy/move non-media project files (`.md`, `.json`, `.pdf`, `.kt`) between two directories across shared storage.
  3. **0:20–0:32 — Archive Browsing, Batch Rename & Quick Look**: Press `Space` to preview a file in **Quick Look**, browse inside a `.zip` archive as a virtual folder and extract an item, and open **Properties** (`Ctrl+I`) showing POSIX metadata and SHA-256 checksums.

### 3.2 Nearby Devices (`android.permission.BLUETOOTH_CONNECT`)

- Declared in `AndroidManifest.xml` with `android:usesPermissionFlags="neverForLocation"`.
- Requested at runtime strictly when the user clicks the **Phone** sidebar item so Wayfinder can detect whether a paired smartphone is currently connected and surface Cross-Device / USB MTP mount options. Never used for location.

---

## 4. Play Console Data Safety Questionnaire (`App content -> Data safety`)

Wayfinder executes locally on-device with zero developer servers, zero analytics, and zero ads. Use the answers below in Play Console's Data Safety wizard:

| Play Console Question | Answer | Notes |
| :--- | :--- | :--- |
| **Does your app collect or share any of the required user data types?** | **No** | Wayfinder has no developer backend, analytics SDK, crash reporter, or ad network. All file indexing, search, archiving, and Keystore encryption stay strictly on-device. User-configured SMB/WebDAV/FTP/SAF connections transmit data directly between the user's device and the user-chosen server/provider. |
| **Is all of the user data collected by your app encrypted in transit?** | *N/A (No data collected)* | Optional user-configured WebDAVS, FTPS, and SMB3 connections support transport encryption, and saved server passwords are encrypted at rest via hardware-backed `AndroidKeyStore` AES-256-GCM. |
| **Do you provide a way for users to request that their data is deleted?** | *N/A (No data collected)* | Users can clear server credentials, unpin/unmount folders, or uninstall the app locally at any time; `android:allowBackup="false"` prevents cloud backup retention. |

---

## 5. Additional `App content` Declarations

- **App access**: Select **All functionality in my app is available without any access restrictions** (all local file management, archive browsing, dual-pane, Quick Look, and picker features work immediately after granting storage access; network shares are optional user-supplied LAN/cloud servers).
- **Ads**: Select **No, my app does not contain ads**.
- **Content ratings (IARC)**: Category **Utility, Productivity, Communication, or Other** -> Answer **No** to all violence, sexuality, profanity, controlled substances, user-to-user sharing, location sharing, and digital purchases prompts. Expected ratings: **ESRB Everyone**, **PEGI 3**, **USK 0**.
- **Target audience and content**: Select age groups **13–15**, **16–17**, and **18 and over** (do **not** opt into "Designed for Families" / under-13).
- **News apps / COVID-19 / Financial / Health**: **No / None**.
- **App category**: Application -> **Tools** (Tags: *File manager*, *Productivity*, *Tools*).

---

## 6. Store Listing Copy (`Grow -> Store presence -> Main store listing`)

All blocks below are validated by `AppBundleAndPlayDocsTest` against Google Play Console's strict character limits.

### App Name (`<= 30` characters)

<!-- PLAY_APP_NAME_START -->
```text
Wayfinder File Manager
```
<!-- PLAY_APP_NAME_END -->

### Short Description (`<= 80` characters)

<!-- PLAY_SHORT_DESC_START -->
```text
Desktop-grade tabbed file manager with dual-pane, archives, and network shares.
```
<!-- PLAY_SHORT_DESC_END -->

### Full Description (`<= 4000` characters)

<!-- PLAY_FULL_DESC_START -->
```text
Wayfinder brings the speed, keyboard efficiency, and spatial clarity of a true desktop file manager to Android laptops, Googlebooks, Chromebooks, and large-screen tablets.

Designed from the ground up for keyboard, trackpad, mouse, and touch workflows, Wayfinder places browser-style folder tabs directly inside the desktop window title bar so every pixel of vertical workspace goes to your files.

DESKTOP-CLASS NAVIGATION & VIEW MODES
• Title-Bar Tabs & Multi-Window: Open tabs (Ctrl+T), close (Ctrl+W), reopen closed tabs (Ctrl+Shift+T), middle-click folders into background tabs, or tear tabs off into independent desktop windows (Ctrl+N).
• Four View Modes: Switch effortlessly between resizable Details tables (Ctrl+1), zoomable Thumbnail Grids (Ctrl+2), cascading Miller Columns (Ctrl+3), and side-by-side Dual-Pane Commander mode (Ctrl+4 / F3) with F5 copy and F6 move.
• Command Palette (Ctrl+K): Jump to any folder, storage volume, or action in milliseconds with fuzzy search.

QUICK LOOK & ARCHIVE BROWSING
• Instant Quick Look (Space): Preview high-resolution images, PDF documents, video frames, audio playback, folder summaries, and syntax-highlighted source code with line numbers without leaving your folder.
• Built-In Archive Filesystem: Browse inside .zip, .tar, .tar.gz, and .tgz archives as virtual folders, inspect entries, extract files, or compress selections into new ZIP archives.
• Deep File Properties (Ctrl+I): Inspect POSIX permissions, octal modes, storage volume capacity, and streaming SHA-256 & CRC32 checksums.

NETWORK, CLOUD & CONNECTED DEVICES
• Network Shares: Connect directly to SMB2/SMB3 (NAS), WebDAV/WebDAVS, and FTP/FTPS servers with automatic local-network share discovery and hardware-backed Android Keystore password encryption.
• Cloud & Removable Storage: Mount Google Drive and external document trees via Android's Storage Access Framework, browse USB-C SSDs and SD cards, and copy directly to the Linux development environment.
• Paired Phone Quick Access: Quickly jump to connected phone folders, USB MTP storage, and incoming Quick Share downloads from the sidebar.

FULL KEYBOARD, MOUSE & TRACKPAD PRECISION
• Single-click select, double-click open, right-click context menus, rubber-band marquee selection, drag-and-drop across tabs and panes, batch rename (Ctrl+Shift+R), color tags, starred items, undo/redo (Ctrl+Z / Ctrl+Y), and a built-in F1 shortcut reference.

PRIVATE & OPEN SOURCE BY DESIGN
• Zero telemetry, zero analytics, and zero ads.
• Licensed under Apache 2.0.
```
<!-- PLAY_FULL_DESC_END -->

### Release Notes (`en-US`, `<= 500` characters)

<!-- PLAY_RELEASE_NOTES_START -->
```text
Initial 2.0 release of Wayfinder for Android desktop, Googlebooks, Chromebooks, and tablets:
• Browser-style folder tabs in the desktop window title bar
• Details, Grid, Miller Columns, and Dual-Pane Commander views
• Spacebar Quick Look for images, PDFs, media, archives, and code
• Native .zip, .tar, and .tar.gz archive browsing and compression
• SMB2/3, WebDAV/S, FTP/S, Google Drive (SAF), USB/SD, and paired Phone support
• Full keyboard shortcuts, marquee selection, and zero telemetry
```
<!-- PLAY_RELEASE_NOTES_END -->

---

## 7. Store Graphic Assets & Large-Screen Quality Checklist

| Asset | Specification | Purpose |
| :--- | :--- | :--- |
| **App Icon** | `512 x 512 px`, 32-bit PNG (max 1 MB) | Play Store listing icon |
| **Feature Graphic** | `1024 x 500 px`, PNG or 24-bit JPEG | Top banner on Play Store listing |
| **Chromebook / Large-Screen Screenshots** | 4–8 landscape PNGs (`16:10` `2880x1800` or `16:9` `1920x1080`) | Required to earn Play Store's **Large-Screen / Chromebook** showcase layout (show Title-Bar Tabs, Dual-Pane Commander, Quick Look, and Connect to Server / Dark Theme) |
| **Phone / Base Listing Screenshots** | At least 2 PNGs (`1080x1920` or `1920x1080`, 16:9) | Required by Play Console's base store listing validator even when targeting primarily large-screen devices |
