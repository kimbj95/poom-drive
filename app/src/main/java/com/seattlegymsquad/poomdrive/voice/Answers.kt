package com.seattlegymsquad.poomdrive.voice

enum class Answer { YES, NO, UNKNOWN }

enum class PassengerType { CHILD, PARENT, PET, NONE }

/** Turns a recognized short answer into a decision. Negative words are checked first ("안 괜찮아", "아니요"). */
object Answers {
    private val NO_WORDS = listOf("아니", "싫어", "아뇨", "됐어", "안괜찮", "별로", "그만")
    private val YES_WORDS = listOf("응", "네", "예", "그래", "좋아", "가자", "괜찮")
    private val YES_EXACT = setOf("어", "웅", "엉", "콜", "오케이")

    fun yesNo(text: String): Answer {
        val t = normalize(text)
        return when {
            t.isEmpty() -> Answer.UNKNOWN
            NO_WORDS.any { t.contains(it) } -> Answer.NO
            t in YES_EXACT || YES_WORDS.any { t.contains(it) } -> Answer.YES
            else -> Answer.UNKNOWN
        }
    }

    /** Who sits in the back, from an answer like "아이요", "엄마", "강아지". */
    fun passenger(text: String): PassengerType? {
        val t = normalize(text)
        return when {
            listOf("강아지", "반려", "댕댕", "멍멍", "고양이", "개").any { t.contains(it) } -> PassengerType.PET
            listOf("아이", "애기", "아기", "딸", "아들", "꼬마", "어린이", "애들").any { t.contains(it) } -> PassengerType.CHILD
            listOf("부모", "엄마", "아빠", "어머니", "아버지", "할머니", "할아버지", "어르신").any { t.contains(it) } -> PassengerType.PARENT
            listOf("없어", "혼자", "아무도").any { t.contains(it) } -> PassengerType.NONE
            else -> null
        }
    }

    private fun normalize(text: String) = text.replace(" ", "").trimEnd('.', '?', '!', '~')
}
