package io.github.rudolfhk.audiosaver.core.collection

import io.github.rudolfhk.audiosaver.core.engine.CollectionItem
import io.github.rudolfhk.audiosaver.core.engine.CollectionPage
import io.github.rudolfhk.audiosaver.core.engine.PageToken
import io.github.rudolfhk.audiosaver.core.link.CollectionKind
import io.github.rudolfhk.audiosaver.core.link.CollectionRef
import io.github.rudolfhk.audiosaver.core.link.VideoId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectionListingTest {

    private val mixRef = CollectionRef("RDdQw4w9WgXcQ", CollectionKind.MIX, VideoId.of("dQw4w9WgXcQ"))

    private fun id(n: Int) = VideoId.of("v" + n.toString().padStart(10, '0'))
    private fun item(n: Int, available: Boolean = true) = CollectionItem(id(n), "Song $n", "Artist", 180, available)
    private fun page(range: IntRange, next: Boolean = true, ref: CollectionRef = mixRef, total: Long? = null, unavailable: Set<Int> = emptySet()) =
        CollectionPage(ref, "Mix - Song", null, range.map { item(it, it !in unavailable) }, total, if (next) PageToken("cursor") else null)

    @Test fun `everything selectable starts ticked, unavailable rows can't be ticked`() {
        val listing = CollectionListing.of(page(1..5, unavailable = setOf(3)))
        assertEquals(5, listing.items.size)
        assertEquals(4, listing.selectedCount)
        assertEquals(4, listing.selectableCount)
        assertTrue(listing.allSelected)
        assertEquals(listing, listing.toggle(id(3)), "unavailable rows ignore taps")
    }

    @Test fun `toggle, select all and none`() {
        val listing = CollectionListing.of(page(1..3))
        val one = listing.toggle(id(2))
        assertEquals(setOf(id(1), id(3)), one.selected)
        assertFalse(one.allSelected)
        assertEquals(listing.selected, one.toggle(id(2)).selected)
        assertEquals(0, listing.selectNone().selectedCount)
        assertEquals(3, listing.selectNone().selectAll().selectedCount)
    }

    @Test fun `load more de-duplicates mix repeats and ticks new songs`() {
        val listing = CollectionListing.of(page(1..25)).toggle(id(1)).appending(page(20..45))
        assertEquals(45, listing.items.size)
        assertEquals((1..45).map(::id), listing.items.map { it.videoId })
        assertFalse(id(1) in listing.selected, "user's choices survive loading more")
        assertTrue(id(45) in listing.selected)
        assertTrue(listing.canLoadMore)
    }

    @Test fun `a page with nothing new ends the mix`() {
        val listing = CollectionListing.of(page(1..25)).appending(page(1..25))
        assertNull(listing.next)
        assertFalse(listing.canLoadMore)
    }

    @Test fun `mixes stop at the cap`() {
        var listing = CollectionListing.of(page(1..90))
        listing = listing.appending(page(91..180))
        listing = listing.appending(page(181..270))
        assertEquals(CollectionListing.MAX_ITEMS, listing.items.size)
        assertFalse(listing.canLoadMore)
    }

    @Test fun `album batch uses album fields and youtube's total`() {
        val albumRef = CollectionRef("OLAK5uy_kpkiHPDtH4uw0Ri5BqO6pIGVnHbbYzr6c", CollectionKind.ALBUM, null)
        val listing = CollectionListing.of(page(1..4, next = false, ref = albumRef, total = 4)).toggle(id(2))
        val batch = listing.toBatchRequest()
        assertEquals(listOf(1, 3, 4), batch.entries.map { it.context.trackNumber })
        assertTrue(batch.entries.all { it.context.trackTotal == 4 && it.context.album == "Mix - Song" })
    }

    @Test fun `mix batch carries no album fields`() {
        val batch = CollectionListing.of(page(1..3)).toBatchRequest()
        assertEquals(3, batch.entries.size)
        assertTrue(batch.entries.all { it.context.album == null && it.context.trackNumber == null && it.context.subfolder == null })
    }
}
