package com.akira.clock.band

import android.content.Context

/** Keeps ordinary builds independent of the optional proprietary Wear SDK. */
internal fun createBandConnection(context: Context): BandConnection = object : BandConnection {
    override val available = false
    override suspend fun connect() = Unit
}
