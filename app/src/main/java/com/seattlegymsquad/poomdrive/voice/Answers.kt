package com.seattlegymsquad.poomdrive.voice

enum class Answer { YES, NO, UNKNOWN }

/** Kinds of passenger the care rules know. [subject] and [with] are the Korean forms for spoken alerts. */
enum class PassengerType(val noun: String, val subject: String, val with: String) {
    CHILD("아이", "아이가", "아이와"),
    PARENT("부모님", "부모님이", "부모님과"),
    PET("반려견", "반려견이", "반려견과"),
}

/** Turns a recognized short answer into a decision. Negative words are checked first ("안 괜찮아", "아니요"). */
object Answers {
    private val NO_WORDS = listOf("아니", "싫어", "아뇨", "됐어", "안괜찮", "별로", "그만")
    private val YES_WORDS = listOf("응", "네", "예", "그래", "좋아", "가자", "괜찮")
    private val YES_EXACT = setOf("어", "웅", "엉", "콜", "오케이")

    private val WORDS = mapOf(
        PassengerType.CHILD to listOf("아이", "애기", "아기", "딸", "아들", "꼬마", "어린이", "애들"),
        PassengerType.PARENT to listOf("부모", "엄마", "아빠", "어머니", "아버지", "할머니", "할아버지", "어르신"),
        PassengerType.PET to listOf("강아지", "반려", "댕댕", "멍멍", "고양이", "개"),
    )
    private val NOBODY_WORDS = listOf("없어", "혼자", "아무도")

    fun yesNo(text: String): Answer {
        val t = normalize(text)
        return when {
            t.isEmpty() -> Answer.UNKNOWN
            NO_WORDS.any { t.contains(it) } -> Answer.NO
            t in YES_EXACT || YES_WORDS.any { t.contains(it) } -> Answer.YES
            else -> Answer.UNKNOWN
        }
    }

    /**
     * Everyone named in an answer like "아이랑 강아지요" or "엄마". An empty set means nobody ("아무도 없어요"),
     * null means the answer named no one we know.
     */
    fun passengers(text: String): Set<PassengerType>? {
        val t = normalize(text)
        val found = WORDS.filterValues { words -> words.any { t.contains(it) } }.keys
        return when {
            found.isNotEmpty() -> found
            NOBODY_WORDS.any { t.contains(it) } -> emptySet()
            else -> null
        }
    }

    private fun normalize(text: String) = text.replace(" ", "").trimEnd('.', '?', '!', '~')
}
