package io.github.rudolfhk.audiosaver.core.tag

import kotlin.test.Test
import kotlin.test.assertEquals

class TitleParserTest {

    private fun check(title: String, channel: String?, artist: String?, song: String) =
        assertEquals(ParsedTitle(artist, song), TitleParser.parse(title, channel), "for \"$title\" by \"$channel\"")

    @Test fun `artist - song with noise`() {
        check("Rick Astley - Never Gonna Give You Up (Official Music Video)", "Rick Astley", "Rick Astley", "Never Gonna Give You Up")
        check("Queen – Bohemian Rhapsody (Official Video Remastered)", "Queen Official", "Queen", "Bohemian Rhapsody (Official Video Remastered)")
        check("Daft Punk - Get Lucky [HD]", "DaftPunkVEVO", "Daft Punk", "Get Lucky")
        check("Adele - Hello (Lyrics)", "7clouds", "Adele", "Hello")
        check("Coldplay - Yellow | Official Video", "Coldplay", "Coldplay", "Yellow")
        check("Imagine Dragons - Believer (Official Audio) [4K]", null, "Imagine Dragons", "Believer")
        check("Rammstein - Deutschland (Offizielles Video)", "Rammstein Official", "Rammstein", "Deutschland")
    }

    @Test fun `meaningful brackets stay`() {
        check("Calvin Harris - Summer (feat. Someone) (Official Video)", "CalvinHarrisVEVO", "Calvin Harris", "Summer (feat. Someone)")
        check("Nirvana - Lithium (Live at Reading 1992)", "Nirvana", "Nirvana", "Lithium (Live at Reading 1992)")
        check("Artist - Song (Acoustic) [Official Video]", null, "Artist", "Song (Acoustic)")
        check("Artist - Song (Remix)", null, "Artist", "Song (Remix)")
        check("Artist - Song (Live, 2019)", null, "Artist", "Song (Live, 2019)")
    }

    @Test fun `quoted titles`() {
        check("Taylor Swift \"Shake It Off\" (Official Video)", "TaylorSwiftVEVO", "Taylor Swift", "Shake It Off")
        check("Kraftwerk „Das Model“", null, "Kraftwerk", "Das Model")
    }

    @Test fun `topic channels keep the title whole and use the channel as artist`() {
        check("Never Gonna Give You Up", "Rick Astley - Topic", "Rick Astley", "Never Gonna Give You Up")
        check("Song - 2011 Remaster", "Some Band - Topic", "Some Band", "Song - 2011 Remaster")
    }

    @Test fun `no separator - channel is the artist`() {
        check("Big Buck Bunny 60fps 4K - Official Blender Foundation Short Film", "Blender", "Big Buck Bunny 60fps 4K", "Official Blender Foundation Short Film")
        check("Never Gonna Give You Up (Official Video)", "RickAstleyVEVO", "RickAstley", "Never Gonna Give You Up")
        check("Just a title", null, null, "Just a title")
    }

    @Test fun `never strips everything`() {
        check("Official Video", "Channel", "Channel", "Official Video")
        check("(Official Video)", null, null, "(Official Video)")
        assertEquals("HD", TitleParser.stripNoise("HD"))
    }

    @Test fun `noise words inside names are left alone`() {
        check("Audioslave - Like a Stone", "Audioslave", "Audioslave", "Like a Stone")
        check("The Video Game Band - Clean Bandit Cover", null, "The Video Game Band", "Clean Bandit Cover")
        check("Artist - Song Official", null, "Artist", "Song")
    }
}
