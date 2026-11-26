// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.droid.wayfinder.data.DropboxOAuthCallbackBus

/**
 * Zero-UI OAuth 2.0 PKCE redirect receiver (`com.droid.wayfinder.dropbox://oauth`).
 *
 * Security invariants:
 * - Never reads or unmarshals `intent.extras` (`Bundle`), avoiding untrusted Parcelable deserialization.
 * - Only inspects query parameters on `intent.data` when an interactive [com.droid.wayfinder.data.DropboxAuthManager]
 *   coroutine is actively awaiting a redirect, where `state` is verified in constant time and `code` is
 *   bound to the in-memory RFC 7636 `code_verifier`.
 */
class DropboxOAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleCallbackIntent(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleCallbackIntent(intent)
        finish()
    }

    private fun handleCallbackIntent(incoming: Intent?) {
        val dataUri = runCatching { incoming?.data }.getOrNull()
        val delivered = DropboxOAuthCallbackBus.deliver(dataUri)
        if (delivered) {
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    },
                )
            }
        }
    }
}
