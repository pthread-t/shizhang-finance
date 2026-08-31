package com.billrecord.ledger.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudLaunchPolicyTest {
    @Test fun `existing local book never blocks normal cold start`() {
        assertFalse(requiresBlockingInitialSync(signedIn = true, cachedBookCount = 1))
    }

    @Test fun `first signed in launch waits for initial cloud book`() {
        assertTrue(requiresBlockingInitialSync(signedIn = true, cachedBookCount = 0))
    }
}
