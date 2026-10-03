package com.taolesi.nas.utils

import java.util.regex.Pattern

object NaturalOrderComparator : Comparator<String> {
    private val NUM_PATTERN = Pattern.compile("(\\d+)|(\\D+)")

    override fun compare(s1: String?, s2: String?): Int {
        if (s1 == null && s2 == null) return 0
        if (s1 == null) return -1
        if (s2 == null) return 1

        val m1 = NUM_PATTERN.matcher(s1)
        val m2 = NUM_PATTERN.matcher(s2)

        while (m1.find()) {
            if (!m2.find()) return 1

            val chunk1 = m1.group()
            val chunk2 = m2.group()

            val isDigit1 = chunk1.all { it in '0'..'9' }
            val isDigit2 = chunk2.all { it in '0'..'9' }

            val result: Int
            if (isDigit1 && isDigit2) {
                val b1 = runCatching { java.math.BigInteger(chunk1) }.getOrNull()
                val b2 = runCatching { java.math.BigInteger(chunk2) }.getOrNull()
                result = if (b1 != null && b2 != null) {
                    b1.compareTo(b2)
                } else {
                    chunk1.compareTo(chunk2, ignoreCase = true)
                }
            } else {
                result = chunk1.compareTo(chunk2, ignoreCase = true)
            }

            if (result != 0) return result
        }

        return if (m2.find()) -1 else s1.compareTo(s2, ignoreCase = true)
    }
}
