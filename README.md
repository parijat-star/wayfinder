<p align="center">
  <img src="docs/images/icon.svg" width="112" alt="Wayfinder icon: an orange folder" />
</p>

<h1 align="center">Wayfinder</h1>

<p align="center">
  <b>A desktop-grade, tabbed file manager for Googlebooks.</b><br>
  Tabs in the title bar, Miller columns, dual-pane commander, paired Phone &amp; Google Drive integration, network shares (SMB, WebDAV, FTP), archive browsing (<code>.zip</code>, <code>.tar.gz</code>, <code>.tgz</code>, <code>.tar</code>), and full keyboard &amp; mouse navigation: one download for every Googlebook, Intel and Snapdragon.
</p>

<p align="center">
  <a href="../../releases/latest/download/Wayfinder.apk"><b>⬇ Download Wayfinder.apk</b></a>
  &nbsp;·&nbsp; <a href="#what-it-is">Features</a>
  &nbsp;·&nbsp; <a href="#phone-and-cloud">Phone &amp; Cloud</a>
  &nbsp;·&nbsp; <a href="#made-for-the-googlebook">Googlebook Design</a>
  &nbsp;·&nbsp; <a href="#limitations">Limitations</a>
  &nbsp;·&nbsp; <a href="#install">Install</a>
  &nbsp;·&nbsp; <a href="#privacy">Privacy</a>
  &nbsp;·&nbsp; <a href="#how-its-built">Architecture</a>
  &nbsp;·&nbsp; <a href="#licenses">Licenses</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Googlebook_OS-Android_15+-133F43" alt="Googlebook OS, Android 15+" />
  <img src="https://img.shields.io/badge/developed_on-a_Googlebook-E0407E?style=flat" alt="Developed on a Googlebook" />
  <img src="https://img.shields.io/badge/developed_for-Googlebooks-1F6F66" alt="Developed for Googlebooks" />
  <img src="https://img.shields.io/badge/license-Apache_2.0-555555" alt="Apache 2.0 license" />
</p>

<p align="center">
  <sub>A personal passion project, proudly developed on and for a Googlebook in its Linux Terminal.<br>
  Not affiliated with or endorsed by Google or any employer (<a href="#about-this-project">more</a>).</sub>
</p>

<p align="center">
  <img src="docs/images/mock_1_main.jpg" width="900" alt="Wayfinder main window in light theme showing browser-style tabs in the title bar, tree navigation, and details file view" />
  <br>
  <sub>Browser-style tabs live right inside the desktop caption bar with no wasted vertical space.</sub>
</p>

---

## What it is

**Wayfinder** brings the full desktop file management experience of macOS Finder and Windows File Explorer to Android laptops and Googlebooks. Built entirely from scratch in Kotlin with Jetpack Compose and Material 3 Expressive, Wayfinder turns your Googlebook into a serious productivity workstation:

* **Tabs directly in the title bar:** Drawn straight into Android 15's desktop caption bar alongside window controls. Open new tabs (`Ctrl+T`), close (`Ctrl+W`), switch (`Ctrl+Tab` or `Alt+1…9`), duplicate, reopen recently closed tabs, or tear off into independent desktop windows (`Ctrl+N`).
* **Four versatile view modes + clean Recents:**
  * **Details View (`Ctrl+1`):** Resizable, sortable columns for Name, Date modified, Type, Size, POSIX permissions, and Color tags.
  * **Grid View (`Ctrl+2`):** Rich thumbnail previews with smooth trackpad zoom (`Ctrl+=` / `Ctrl+-` / `Ctrl+0`).
  * **Miller Columns (`Ctrl+3`):** macOS Finder-style multi-column cascading hierarchy navigation.
  * **Dual-Pane Commander (`Ctrl+4` / `F3`):** Two independent side-by-side browser panes with `F5` copy and `F6` move.
  * **Distraction-free Recents:** Shows recently modified files across storage with clean titles and no redundant file-path clutter underneath.
* **First-class desktop input:** Single-click selects, double-click opens, right-click opens context menus, middle-click opens in a background tab, rubber-band marquee drag selection, and drag-and-drop between folders or from other apps.
* **Quick Look (`Space`):** Instant desktop preview for images, video frames, PDFs, audio waveforms, archives, and syntax-highlighted code with line numbers without launching heavy external viewers.
* **Archive filesystem:** Navigate into `.zip`, `.tar.gz`, `.tgz`, and `.tar` archives as virtual read-only folders, view contents, copy files out, and compress selections with one shortcut.
* **Network & cloud storage:** Connect directly to local and remote servers via **SMB 2/3** (NTLM), **WebDAV / WebDAVS**, **FTP / FTPS**, and Android **Storage Access Framework** providers (**Google Drive**, external USB SSDs).
* **Connected Phone Integration:** Automatically discovers paired and connected Android phones over **Googlebook Cross-Device Services** (`QuickAccessDocumentsProvider`), **USB MTP**, and **Bluetooth**, browsing live phone directories (`Alarms`, `Android`, `DCIM`, `Documents`, `Download`, `Movies`, `Music`, `Pictures`, etc.) wirelessly with human-friendly breadcrumbs (`Pixel 8a / Documents`) and incoming **Quick Share** integration.
* **Googlebook Ecosystem (`Linux files` & Work Profile):** Auto-discovers the Googlebook Linux development environment (`Linux files`) with one-click **Share with Linux** transfers and supports managed **Work / Personal Profile** switching via `CrossProfileApps`.
* **Command palette (`Ctrl+K`):** Spotlight-style fuzzy command launcher to run any operation or jump to any folder in milliseconds.
* **Complete system file & folder picker:** Registers for `GET_CONTENT`, `OPEN_DOCUMENT`, `OPEN_DOCUMENT_TREE` (folder access grants), and `ACTION_CREATE_DOCUMENT` ("Save As"), returning `DocumentsContract` URIs with persistable read/write permissions.

---

<h2 id="phone-and-cloud">Phone &amp; Google Drive Integration</h2>

Wayfinder integrates with your paired Android phone and Google Drive right from the left navigation sidebar:

* **Wireless & Wired Phone Access (`Pixel 8a` / `Pixel 9`):** When your phone is paired with your Googlebook via Cross-Device Services, Wayfinder connects directly to `QuickAccessDocumentsProvider` with zero prompts—letting you browse, open, copy, and move files on your phone's live internal storage wirelessly using clean, logical paths (`Pixel 8a/Documents`). Wired USB MTP and incoming **Quick Share** folders are also supported out of the box.
* **Google Drive in Network & Cloud:** Google Drive appears natively in the **Network & cloud** sidebar section with its multicolor Drive icon. Clicking **Google Drive** launches the system document tree selector directly at your Google Drive root and persists read/write access across reboots.

<p align="center">
  <img src="docs/images/mock_4_phone_drive.jpg" width="900" alt="Wayfinder Phone and Google Drive integration showing Pixel 9 paired in Quick access with the 13 standard Android folders and Google Drive in Network and cloud" />
  <br>
  <sub>Paired Phone ("Pixel 9") showing the 13 standard Android folders with clean labels alongside Google Drive in the Network &amp; cloud sidebar.</sub>
</p>

<p align="center">
  <img src="docs/images/mock_2_smb.jpg" width="900" alt="Wayfinder Connect to Server dialog showing SMB, WebDAV, and FTP configuration over a network folder" />
  <br>
  <sub>Connect to local NAS, WebDAV clouds, and FTP servers with credentials safely encrypted in Android Keystore.</sub>
</p>

---

## Made for the Googlebook

Mobile file managers were made for small touchscreens and vertical phone layouts. Wayfinder is tailored specifically for the Googlebook form factor:

* **Full-height desktop windowing:** Wayfinder opts into Android 15 desktop caption management (`android:defaultWidth="1360dp"`, `android:defaultHeight="1000dp"`), matching the vertical and horizontal footprint of the system Files app while placing folder tabs directly into the native caption area.
* **Complete keyboard workflow:** Every desktop shortcut you expect works out of the box — `Ctrl+C`, `Ctrl+X`, `Ctrl+V`, `Ctrl+D` (Duplicate), `F2` (Rename), `Ctrl+Shift+R` (Batch rename), `Del` (Trash), `Shift+Del` (Permanent delete), `Ctrl+Z` (Undo), `Ctrl+Y` (Redo), and `Ctrl+L` (Address bar). Press `F1` anytime for the complete shortcut cheatsheet.
* **Trackpad & mouse precision:** Smooth scrolling, right-click context menus, horizontal wheel panning, and multi-file drag-and-drop with hover targets.
* **Theme matching:** Expressive Light, Dark, and System Auto appearance, adapting window captions and tonal color palettes dynamically.
* **Deep system info:** Detailed POSIX properties (`Ctrl+I` / `Alt+Enter`), inode, octal permissions (`rwxr-xr-x`), file system free/total capacity, and background streaming SHA-256 and CRC32 checksum generation.

<p align="center">
  <img src="docs/images/mock_3_dark_contextmenu.jpg" width="900" alt="Wayfinder Dark Theme design mockup with rich thumbnail grid and desktop right-click context menu" />
  <br>
  <sub>Design concept mockup: Expressive Dark theme with thumbnail grid view, color tags, and full desktop right-click menu.</sub>
</p>

---

<h2 id="limitations">Known Limitations</h2>

Because Wayfinder can be installed as a standalone open-source application (without requiring platform OS signing keys or the signature-level `MANAGE_DOCUMENTS` permission), a few Android OS security boundaries apply to specific providers:

* **Wired USB MTP One-Time Folder Grant (Wireless Cross-Device Works Directly):** Wireless phone browsing over **Googlebook Cross-Device Services** (`com.google.ambient.streaming.multidevicesync.quickaccess`) works directly out of the box with zero prompts. By contrast, Android's wired USB MTP provider (`com.android.mtp.documents`) restricts direct root enumeration to packages holding `MANAGE_DOCUMENTS`. When connecting a phone strictly via a USB-C MTP cable without Cross-Device Services, Wayfinder delegates a one-time **Mount Phone Storage (SAF)** folder grant to the system `DocumentsUI` picker and persists access.
* **Google Drive One-Time SAF Folder Grant:** Android's Google Drive `DocumentsProvider` (`com.google.android.apps.docs.storage`) restricts zero-prompt root listing to pre-installed system apps holding `MANAGE_DOCUMENTS`, and standalone open-source APKs cannot embed a proprietary Google Cloud OAuth client secret tied to a single developer signing key. Wayfinder therefore integrates with Google Drive through Android's official **Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`)**: clicking **Google Drive** delegates directly to the system `DocumentsUI` picker for a one-time folder selection, after which Wayfinder persists the access token across reboots.

---

## Install

**[⬇ Download Wayfinder.apk](../../releases/latest/download/Wayfinder.apk)** (about 25 MB; universal for Intel and Snapdragon Googlebooks).

1. **Download the APK:** Click the link above to download **`Wayfinder.apk`**.
2. **Open the file:** Click the download notification in Chrome, or locate `Wayfinder.apk` in your `Downloads` folder.
3. **Allow install:** If Android asks to permit installing unknown apps, click **Settings**, enable **Allow from this source**, and tap **Install**.
4. **Grant storage permission:** On first launch, grant **All files access** (`MANAGE_EXTERNAL_STORAGE`) so Wayfinder can manage your local directories, SD cards, and external drives.

> **Updating:** Simply install newer versions of `Wayfinder.apk` directly over your existing installation. Your pinned folders, bookmarks, saved server credentials, and settings will remain preserved.

---

## Privacy & Security

* **Zero telemetry, zero ads:** Wayfinder contains no trackers, advertising SDKs, analytics, or third-party tracking scripts.
* **Credentials secured with Keystore:** Server passwords for SMB, WebDAV, and FTP shares are encrypted using hardware-backed AES-256-GCM via the Android Keystore.
* **Hardened file operations:** Built-in safeguards against zip-slip and tar-symlink vulnerabilities, symlink recursion loops, `FileProvider` symlink traversal, HMAC-authenticated internal window intents, and corrupted network data.
* **Strict backup exclusions:** Credentials and server auth tokens are explicitly excluded from cloud backups (`dataExtractionRules` & `backup_rules`).
* **Audited & tested:** Backed by 265+ automated unit tests, property-based fuzzers (537,000+ iterations), WCAG 2.1 AA accessibility checks, and headless Robolectric UI integration tests. See [SECURITY.md](SECURITY.md) for vulnerability reporting.

---

## How it's built

Wayfinder is written in 100% modern Kotlin with Jetpack Compose:

* **Language & Tooling:** Kotlin 2.1, Jetpack Compose 1.7, Material 3 Expressive, Gradle 8.11, Android SDK 36 (Android 15+ Desktop Windowing).
* **Storage & Network Engines:**
  * Local & Phone: Java NIO, Android StorageManager & Cross-Device discovery (`LocalFileSystem`, `PhoneFileSystem`, `SafFileSystem`).
  * Archives: Apache Commons Compress (`ZipFileSystem` supporting `.zip`, `.tar.gz`, `.tgz`, `.tar` with symlink/traversal guards).
  * SMB: `smbj` SMB2/SMB3 client with NTLM authentication.
  * WebDAV: OkHttp with streaming bodies and safe XML pull parser.
  * FTP/FTPS: Apache Commons Net with TLS explicit encryption.
  * Image Loading: Coil Compose with thumbnail downsampling.

```bash
# Clone and build directly in the Googlebook Linux Terminal
git clone https://github.com/parijat-star/wayfinder.git
cd wayfinder
bash build.sh assembleDebug        # Builds app/build/outputs/apk/debug/Wayfinder-debug.apk
bash build.sh testDebugUnitTest    # Runs all 231 unit, UI, security, and fuzz tests
```

---

## Made on a Googlebook

Wayfinder was created, built, and tested directly on a Googlebook:

* **Developed in the Linux Terminal:** Kotlin sources, Gradle builds, tests, and packaging were executed inside the Googlebook's built-in Linux VM.
* **Tested headlessly on-device:** Fully verified using Robolectric and Compose UI testing harnesses simulating desktop resolutions (1360×1000 landscape) with virtual mouse and keyboard events.
* **Pure JVM portability:** No native C/C++ dependencies (`.so`), allowing a single APK to run with optimal performance on both Intel x86_64 and Qualcomm Snapdragon ARM64 hardware.

---

<h2 id="about-this-project">About this project</h2>

Wayfinder is an independent, open-source passion project developed on and for Googlebooks to give Googlebook users the power, elegance, and keyboard-driven efficiency of a desktop operating system.

It is not affiliated with, endorsed by, or sponsored by Google or any employer. See [CONTRIBUTING.md](CONTRIBUTING.md) if you'd like to contribute.

---

## Licenses

* **Wayfinder source code:** Licensed under the [Apache License, Version 2.0](LICENSE).
* **Third-Party Libraries:** Built using open-source libraries including AndroidX, Jetpack Compose, smbj, OkHttp, Commons Compress, and Commons Net. Complete license texts and attributions are available in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

<p align="center"><sub>Crafted with passion on a Googlebook, for the Googlebook community.</sub></p>
