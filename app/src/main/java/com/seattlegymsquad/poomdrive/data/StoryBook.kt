package com.seattlegymsquad.poomdrive.data

import android.content.Context

/**
 * Public-domain children's story read aloud in child mode: 방정환 「만년 셔츠」(1927), text from Korean Wikisource
 * (PD-old-70). Short paragraphs are merged so each TTS request is a few sentences long.
 */
class StoryBook(private val context: Context) {

    val title = "만년 셔츠"

    val parts: List<String> by lazy {
        val paragraphs = context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        }
        val merged = mutableListOf<String>()
        val buffer = StringBuilder()
        for (paragraph in paragraphs) {
            if (buffer.isNotEmpty() && buffer.length + paragraph.length > PART_CHARS) {
                merged += buffer.toString()
                buffer.clear()
            }
            if (buffer.isNotEmpty()) buffer.append(' ')
            buffer.append(paragraph)
        }
        if (buffer.isNotEmpty()) merged += buffer.toString()
        merged
    }

    private companion object {
        const val ASSET = "stories/mannyeon_shirt.txt"
        const val PART_CHARS = 220
    }
}
