# Wayfinder Privacy Policy

Wayfinder is a standalone, open-source file manager for Android desktop and large-screen devices.

## Data Collection & Telemetry

Wayfinder does **not** collect, transmit, store, sell, or share any personal data, analytics, crash telemetry, advertising identifiers, or usage logs on external servers operated by the project authors.

## Permissions & On-Device Processing

All file browsing, indexing, previewing, archiving, and search operations run locally on your device:

- **All Files Access (`MANAGE_EXTERNAL_STORAGE`)**: Used solely on your device so you can browse, search, preview, archive, copy, move, rename, and organize folders across shared and removable storage volumes.
- **Nearby Devices (`BLUETOOTH_CONNECT` with `neverForLocation`)**: Optional runtime permission used strictly to detect whether a paired smartphone is currently connected so Wayfinder can surface Cross-Device or USB file-transfer mount shortcuts in the sidebar. Bluetooth is never used to infer or track physical location.
- **Network Access (`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`)**: Used exclusively when you explicitly browse local-network SMB shares (mDNS / NetBIOS discovery) or connect to an SMB, WebDAV/WebDAVS, or FTP/FTPS server that you configure. Files only leave your device when you explicitly copy, upload, or share them to a server or app of your choice.
- **Cloud & Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE` / optional `MANAGE_DOCUMENTS`)**: On standard installs, when you mount a cloud provider (such as Google Drive) or connected phone folder via the Android system document picker, Android grants Wayfinder scoped URI access only to the folder trees you select. On developer or rooted devices where you explicitly grant `MANAGE_DOCUMENTS` via ADB, Wayfinder enumerates local and paired `DocumentsProvider` roots (Cross-Device Phone, Google Drive, Linux files, and USB MTP) strictly on-device.

## Credential & Session Storage

- **Saved Server Credentials**: When you choose to remember credentials for an SMB, WebDAV, or FTP server, passwords are encrypted locally on your device using an AES-GCM key stored in the hardware-backed `AndroidKeyStore`.
- **Backups & Device Transfers**: Cloud backups are disabled (`android:allowBackup="false"`), and device-to-device transfer rules explicitly exclude saved server credentials, intent-guard secrets, local session preferences (`settings.xml`), and temporary file caches.

## Contact & Source Code

Wayfinder is licensed under the Apache License, Version 2.0. You can inspect the complete source code and report security or privacy issues in the project repository on GitHub.
