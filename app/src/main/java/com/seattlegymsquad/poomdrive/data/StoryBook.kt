package com.seattlegymsquad.poomdrive.data

import android.content.Context
import com.seattlegymsquad.poomdrive.voice.StoryLine

/**
 * Stories read aloud in child mode: well-known Korean folk tales retold by the team in our own words (the tales
 * are public domain, the wording is ours). Each drive gets the next story in turn. An asset's first line is
 * "# <title>" and other "#" lines are notes. The story is read one sentence at a time with a short pause after
 * each (a longer one after a paragraph): the on-device voice has no speed setting, and a sentence cut off by an
 * urgent alert is read again from its start instead of the whole paragraph.
 */
class StoryBook(private val context: Context) {

    class Story(val title: String, val parts: List<StoryLine>)

    private val prefs = context.getSharedPreferences("stories", Context.MODE_PRIVATE)
    private val stories: List<Story> by lazy { ASSETS.map(::load) }

    /** The story offered for the next drive. */
    val next: Story get() = stories[prefs.getInt("next", 0) % stories.size]

    /** Moves on to the following story, so the next drive hears a different one. */
    fun advance() {
        prefs.edit().putInt("next", (prefs.getInt("next", 0) + 1) % stories.size).apply()
    }

    /** Makes story [index] the next one (demo and tests, so repeated runs read the same story). */
    fun select(index: Int) {
        prefs.edit().putInt("next", index.mod(stories.size)).apply()
    }

    private fun load(asset: String): Story {
        val lines = context.assets.open(asset).bufferedReader(Charsets.UTF_8).useLines { it.map(String::trim).toList() }
        val title = lines.first().removePrefix("#").trim()
        val paragraphs = lines.filter { it.isNotEmpty() && !it.startsWith("#") }
        val parts = paragraphs.flatMap { paragraph ->
            val sentences = SENTENCE.findAll(paragraph).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
            sentences.mapIndexed { i, s -> StoryLine(s, if (i == sentences.lastIndex) PARAGRAPH_PAUSE_MS else SENTENCE_PAUSE_MS) }
        }
        return Story(title, parts)
    }

    private companion object {
        val ASSETS = listOf("stories/haenim_dalnim.txt", "stories/heungbu_nolbu.txt")

        /** A sentence with its closing quote, so "떡 하나 주면 안 잡아먹지!" stays one piece. */
        val SENTENCE = Regex("[^.?!]+[.?!]+[\"”」']?")
        const val SENTENCE_PAUSE_MS = 550L
        const val PARAGRAPH_PAUSE_MS = 1100L
    }
}
