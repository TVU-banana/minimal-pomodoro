package com.akira.clock.timer

import java.util.UUID

/** Supplies identifiers so domain transitions remain deterministic in tests. */
internal fun interface IdentifierSource {
    /** Returns a new globally unique identifier. */
    fun newId(): String
}

/** Production identifier source backed by UUID version 4. */
internal object UuidIdentifierSource : IdentifierSource {
    override fun newId(): String = UUID.randomUUID().toString()
}
