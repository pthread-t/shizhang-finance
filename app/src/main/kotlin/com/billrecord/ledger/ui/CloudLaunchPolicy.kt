package com.billrecord.ledger.ui

internal fun requiresBlockingInitialSync(signedIn: Boolean, cachedBookCount: Int): Boolean =
    signedIn && cachedBookCount == 0
