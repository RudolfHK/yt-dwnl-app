package io.github.rudolfhk.audiosaver.download

import io.github.rudolfhk.audiosaver.core.pipeline.TagProposal
import io.github.rudolfhk.audiosaver.core.pipeline.TagReviewer
import io.github.rudolfhk.audiosaver.core.tag.TrackMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A tag proposal waiting for the user, answered through [TagReviewQueue.answer]. */
class PendingReview internal constructor(val proposal: TagProposal) {
    internal val answer = CompletableDeferred<TrackMetadata?>()
}

/**
 * "Edit tags before saving": the pipeline suspends in [review] (while the download carries on)
 * until the dialog answers. Only one download runs at a time, so there is at most one pending.
 */
class TagReviewQueue : TagReviewer {
    private val _pending = MutableStateFlow<PendingReview?>(null)
    val pending: StateFlow<PendingReview?> = _pending.asStateFlow()

    override suspend fun review(proposal: TagProposal): TrackMetadata? {
        val request = PendingReview(proposal)
        _pending.value = request
        try {
            return request.answer.await()
        } finally {
            // Answered, or the download was cancelled: either way the dialog goes away.
            _pending.compareAndSet(request, null)
        }
    }

    /** [metadata] = save with these tags; null = cancel the download. */
    fun answer(review: PendingReview, metadata: TrackMetadata?) {
        review.answer.complete(metadata)
        _pending.compareAndSet(review, null)
    }
}
