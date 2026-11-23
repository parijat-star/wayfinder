// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Dynamically enables or disables [ALIAS_CLASS_NAME] (the `<activity-alias>` handling privileged
 * `DocumentsContract` intents: `ACTION_BROWSE`, `ACTION_MANAGE_DOCUMENT`, and `ACTION_VIEW` for
 * `content://` `vnd.android.document/directory`) based on whether Wayfinder holds
 * [Manifest.permission.MANAGE_DOCUMENTS].
 *
 * On retail Google Play / `user` builds, Wayfinder does not hold `MANAGE_DOCUMENTS`, so the alias
 * remains disabled (its manifest default). This prevents Wayfinder from intercepting system
 * `DocumentsProvider` intents that cause calling apps to crash with `SecurityException` when
 * Android's `UriGrantsManagerService` refuses to grant an un-owned `DocumentsProvider` URI to an
 * unprivileged target.
 *
 * When Wayfinder is installed as a privileged system app (or granted `MANAGE_DOCUMENTS` via ADB on
 * developer builds), [sync] enables the alias so Wayfinder handles those intents seamlessly.
 */
object PrivilegedDocumentsAlias {
    const val ALIAS_CLASS_NAME = "com.droid.wayfinder.DocumentsBrowseAlias"

    fun componentName(context: Context): ComponentName =
        ComponentName(context.packageName, ALIAS_CLASS_NAME)

    fun hasManageDocuments(context: Context): Boolean =
        runCatching {
            context.checkSelfPermission(Manifest.permission.MANAGE_DOCUMENTS) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    /**
     * Reconciles the enabled state of [ALIAS_CLASS_NAME] with [hasManageDocuments].
     * Returns true if the alias is enabled after syncing, false otherwise.
     */
    fun sync(context: Context): Boolean {
        val granted = hasManageDocuments(context)
        val pm = context.packageManager
        val component = componentName(context)
        val currentState = runCatching { pm.getComponentEnabledSetting(component) }
            .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        val effectivelyEnabled = currentState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        if (effectivelyEnabled != granted) {
            val desiredState = if (granted) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            runCatching {
                pm.setComponentEnabledSetting(component, desiredState, PackageManager.DONT_KILL_APP)
            }
        }
        return granted
    }
}
