package works.earendil.pi.tui

data class FuzzyMatch(
    val matches: Boolean,
    val score: Double = 0.0,
)

fun fuzzyMatch(
    query: String,
    text: String,
): FuzzyMatch {
    val queryLower = query.lowercase()
    val textLower = text.lowercase()

    fun match(normalizedQuery: String): FuzzyMatch {
        if (normalizedQuery.isEmpty()) {
            return FuzzyMatch(matches = true)
        }
        if (normalizedQuery.length > textLower.length) {
            return FuzzyMatch(matches = false)
        }

        var queryIndex = 0
        var score = 0.0
        var lastMatchIndex = -1
        var consecutiveMatches = 0

        for (index in textLower.indices) {
            if (queryIndex >= normalizedQuery.length) {
                break
            }
            if (textLower[index] != normalizedQuery[queryIndex]) {
                continue
            }

            val isWordBoundary =
                index == 0 || textLower[index - 1].let { previous ->
                    previous.isWhitespace() || previous in "-_./:"
                }

            if (lastMatchIndex == index - 1) {
                consecutiveMatches++
                score -= consecutiveMatches * 5
            } else {
                consecutiveMatches = 0
                if (lastMatchIndex >= 0) {
                    score += (index - lastMatchIndex - 1) * 2
                }
            }

            if (isWordBoundary) {
                score -= 10
            }
            score += index * 0.1
            lastMatchIndex = index
            queryIndex++
        }

        if (queryIndex < normalizedQuery.length) {
            return FuzzyMatch(matches = false)
        }
        if (normalizedQuery == textLower) {
            score -= 100
        }
        return FuzzyMatch(matches = true, score = score)
    }

    val primary = match(queryLower)
    if (primary.matches) {
        return primary
    }

    val alphaNumeric = Regex("^([a-z]+)([0-9]+)$").matchEntire(queryLower)
    val numericAlpha = Regex("^([0-9]+)([a-z]+)$").matchEntire(queryLower)
    val swapped =
        when {
            alphaNumeric != null -> alphaNumeric.groupValues[2] + alphaNumeric.groupValues[1]
            numericAlpha != null -> numericAlpha.groupValues[2] + numericAlpha.groupValues[1]
            else -> return primary
        }
    val swappedMatch = match(swapped)
    return if (swappedMatch.matches) {
        FuzzyMatch(matches = true, score = swappedMatch.score + 5)
    } else {
        primary
    }
}

fun <T> fuzzyFilter(
    items: List<T>,
    query: String,
    getText: (T) -> String,
): List<T> {
    if (query.isBlank()) {
        return items
    }
    val tokens = query.trim().split(Regex("[\\s/]+")).filter(String::isNotEmpty)
    if (tokens.isEmpty()) {
        return items
    }

    return items
        .mapNotNull { item ->
            var totalScore = 0.0
            for (token in tokens) {
                val match = fuzzyMatch(token, getText(item))
                if (!match.matches) {
                    return@mapNotNull null
                }
                totalScore += match.score
            }
            item to totalScore
        }.sortedBy { it.second }
        .map { it.first }
}
