package com.akira.clock.band

/**
 * Bounded process-local replay protection, used only under the coordinator mutex.
 *
 * Fresh unpredictable tokens after every transition and process restart reject evicted requests.
 * This deliberately provides no durable ACK replay or offline command queue.
 */
internal class BandCommandGate(private val newToken: () -> String) {
    var token: String = newToken()
        private set
    private val replies = linkedMapOf<String, Pair<BandRequest, BandReply>>()

    fun invalidate() {
        token = newToken()
    }

    fun replay(request: BandRequest, current: BandState): BandReply? {
        val previous = replies[request.id] ?: return null
        return if (previous.first == request) previous.second else {
            BandReply(request.id, BandCode.CONFLICT, current)
        }
    }

    fun remember(request: BandRequest, reply: BandReply) {
        replies[request.id] = request to reply
        if (replies.size > 64) replies.remove(replies.keys.first())
    }
}
