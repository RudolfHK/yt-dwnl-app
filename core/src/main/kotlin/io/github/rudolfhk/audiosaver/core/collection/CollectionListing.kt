package io.github.rudolfhk.audiosaver.core.collection

import io.github.rudolfhk.audiosaver.core.engine.CollectionItem
import io.github.rudolfhk.audiosaver.core.engine.CollectionPage
import io.github.rudolfhk.audiosaver.core.engine.PageToken
import io.github.rudolfhk.audiosaver.core.link.CollectionKind
import io.github.rudolfhk.audiosaver.core.link.CollectionRef
import io.github.rudolfhk.audiosaver.core.link.VideoId
import io.github.rudolfhk.audiosaver.core.pipeline.BatchRequest

/**
 * What the pick-your-songs list shows: the songs loaded so far and which are ticked. Immutable;
 * every change returns a new listing, so it can sit directly in UI state.
 *
 * Everything selectable starts ticked - the user opened the link to get the songs - and songs added
 * by "Load more" are ticked as well. Mixes are endless, so loading stops at [MAX_ITEMS].
 */
data class CollectionListing(
    val ref: CollectionRef,
    val title: String,
    val uploader: String?,
    val items: List<CollectionItem>,
    val selected: Set<VideoId>,
    val totalCount: Long?,
    val next: PageToken?,
) {
    val kind: CollectionKind get() = ref.kind
    val canLoadMore: Boolean get() = next != null && items.size < MAX_ITEMS
    val selectableCount: Int get() = items.count { it.available }
    val selectedCount: Int get() = selected.size
    val allSelected: Boolean get() = selectedCount == selectableCount

    fun toggle(id: VideoId): CollectionListing {
        if (items.none { it.videoId == id && it.available }) return this
        return copy(selected = if (id in selected) selected - id else selected + id)
    }

    fun selectAll(): CollectionListing = copy(selected = selectableIds(items))
    fun selectNone(): CollectionListing = copy(selected = emptySet())

    /** Adds the next page: skips songs already listed (mixes repeat themselves) and respects the cap. */
    fun appending(page: CollectionPage): CollectionListing {
        val known = items.mapTo(HashSet()) { it.videoId }
        val fresh = page.items.filter { known.add(it.videoId) }.take(MAX_ITEMS - items.size)
        val reachedCap = items.size + fresh.size >= MAX_ITEMS
        return copy(
            items = items + fresh,
            selected = selected + selectableIds(fresh),
            // A page with nothing new means the mix is looping: stop offering "Load more".
            next = if (fresh.isEmpty() || reachedCap) null else page.next,
            totalCount = page.totalCount ?: totalCount,
        )
    }

    fun toBatchRequest(): BatchRequest = BatchRequest.forSelection(
        title = title,
        kind = kind,
        uploader = uploader,
        allItems = items,
        selected = selected,
        albumTotal = totalCount?.toInt()?.takeIf { kind == CollectionKind.ALBUM && it >= items.size },
    )

    companion object {
        const val MAX_ITEMS = 200

        fun of(page: CollectionPage): CollectionListing =
            CollectionListing(page.ref, page.title, page.uploader, emptyList(), emptySet(), page.totalCount, null)
                .appending(page)

        private fun selectableIds(items: List<CollectionItem>): Set<VideoId> =
            items.filter { it.available }.mapTo(LinkedHashSet()) { it.videoId }
    }
}
