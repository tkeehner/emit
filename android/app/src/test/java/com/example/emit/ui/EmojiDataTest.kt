package com.example.emit.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EmojiDataTest {
    private val all = EmojiData.parse(File("src/main/assets/emoji.tsv").readLines().asSequence())
    @Test fun categoriesPresent() {
        val cats = all.map { it.cat }.toSet()
        EmojiData.CATS.drop(1).forEach { assertTrue(it.first, it.first in cats) }
        assertTrue(all.size > 1500)
    }
    @Test fun skinTones() { val thumbs = all.first { it.name == "thumbs up" }; assertEquals(5, thumbs.tones.size); assertEquals("👍🏽", thumbs.tones[2]) }
    @Test fun search() = assertEquals("❤️", EmojiData.search(all, "red heart").first().e)
    @Test fun recentsOrder() = assertEquals(listOf("b", "a"), EmojiRecents.push(listOf("a", "b"), "b"))
}
