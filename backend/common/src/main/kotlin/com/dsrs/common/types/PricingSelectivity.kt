package com.dsrs.common.types

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The selectivities CalculatePrice takes. Shared by the operator, which prices with them, and the
 * auditor, which recomputes them over its own history; the audit's independence comes from the
 * data, not from a second copy of the arithmetic. An unconstrained dimension is always 0.0.
 */
object PricingSelectivity {

    const val ADDRESS_FIELDS = 5

    const val TRUST_AVERAGE_WEIGHT = 0.75

    /**
     * The fraction of the corpus's spread the filter excludes. An open side falls back to the
     * corpus extreme. The denominator is the corpus spread and never the matched set's, which
     * would make the filter define the extremes it is measured against.
     */
    fun range(
        requestedLow: Double?, requestedHigh: Double?,
        corpusLow: Double?, corpusHigh: Double?
    ): Double {
        if (requestedLow == null && requestedHigh == null) return 0.0
        if (corpusLow == null || corpusHigh == null) return 0.0
        val corpusWidth = corpusHigh - corpusLow
        if (corpusWidth <= 0.0) return 0.0

        // asking for 0..1e9 of a corpus spanning 285000..520000 constrains nothing
        val lo = max(requestedLow ?: corpusLow, corpusLow)
        val hi = min(requestedHigh ?: corpusHigh, corpusHigh)
        val requestedWidth = max(0.0, hi - lo)
        return (1.0 - requestedWidth / corpusWidth).coerceIn(0.0, 1.0)
    }

    fun list(selected: Int, distinctInCorpus: Int): Double {
        if (selected <= 0 || distinctInCorpus <= 0) return 0.0
        return (1.0 - selected.toDouble() / distinctInCorpus).coerceIn(0.0, 1.0)
    }

    fun address(fieldsGiven: Int): Double =
        (fieldsGiven.toDouble() / ADDRESS_FIELDS).coerceIn(0.0, 1.0)

    /**
     * Quality of the data actually delivered relative to the corpus, in [-1, 1]. It applies even
     * when the query never mentions trust.
     *
     * The average alone cannot tell {20,20,10,10} from {15,15,15,15}, though only the first
     * contains the corpus's best records; the cubed concentration term does, at 25% weight.
     */
    fun trustQuality(matched: List<Double>, corpus: List<Double>): Double {
        if (matched.isEmpty() || corpus.isEmpty()) return 0.0
        val corpusMax = corpus.max()
        val corpusMean = corpus.average()
        if (corpusMax <= 0.0) return 0.0

        val qAvg = if (corpusMax - corpusMean <= 0.0) 0.0
                   else ((matched.average() - corpusMean) / (corpusMax - corpusMean)).coerceIn(-1.0, 1.0)

        val cube = { t: Double -> (t / corpusMax).pow(3.0) }
        val corpusCube = corpus.map(cube).average()
        val qConc = if (1.0 - corpusCube <= 0.0) 0.0
                    else ((matched.map(cube).average() - corpusCube) / (1.0 - corpusCube)).coerceIn(-1.0, 1.0)

        return (TRUST_AVERAGE_WEIGHT * qAvg + (1 - TRUST_AVERAGE_WEIGHT) * qConc).coerceIn(-1.0, 1.0)
    }
}
