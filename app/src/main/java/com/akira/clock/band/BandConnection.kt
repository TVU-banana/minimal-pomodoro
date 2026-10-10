package com.akira.clock.band

/**
 * Process-lifetime band listener, activated by an explicit setup action.
 *
 * This is not a persistent service: process death ends reception and setup must be repeated.
 * Permission, signing, and device compatibility failures propagate to the setup UI.
 */
internal interface BandConnection {
    val available: Boolean
    suspend fun connect()
}
