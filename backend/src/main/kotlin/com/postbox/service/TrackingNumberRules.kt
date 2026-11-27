package com.postbox.service

import com.postbox.carrier.CarrierId

/**
 * Format rules that map a tracking number to a ranked candidate carrier list.
 * A format is a hint, never proof: the parallel probe decides. Rules are listed
 * strongest first, so the first candidate is the provisional pick when no carrier
 * knows the number yet. v1 covers DHL, CTT, PostNL, Paack and EcoScooting; the DPD
 * rules ship with its adapter.
 *
 * Letter-prefixed formats are matched case-insensitively (carriers normalize to
 * uppercase themselves); EcoScooting's own lookup is case-sensitive, so its rules
 * match the number as given.
 */
object TrackingNumberRules {

    private const val MinPaackCandidateLength = 4

    private val cttS10Pt = Regex("""^[A-Z]{2}\d{9}PT$""")
    private val postnlS10Nl = Regex("""^[A-Z]{2}\d{9}NL$""")
    private val postnl3s = Regex("""^3S[A-Z]{1,4}\d{7,12}$""")
    private val cttNumeric = Regex("""^\d{22}(\d{3})?$""")
    private val dhlExpress = Regex("""^\d{10}$""")
    private val dhlEcommerce = Regex("""^GM\d{16}$""")
    private val ecoscootingCn = Regex("""^CN[A-Z]{3}\d{18,20}$""")
    private val paackNumeric = Regex("""^\d{18}$""")
    private val dhlParcelInternational = Regex("""^JVGL\d{12,20}$""")
    private val ecoscootingNumeric = Regex("""^\d{12,18}$""")

    private val s10Weights = intArrayOf(8, 6, 4, 2, 3, 5, 9, 7)

    /** Ranked candidate carriers for [number]; empty when no v1 rule matches. */
    fun candidatesFor(number: String): List<CarrierId> {
        val upper = number.uppercase()
        val candidates = linkedSetOf<CarrierId>()
        if (cttS10Pt.matches(upper) && hasValidS10CheckDigit(upper)) candidates += CarrierId.CTT
        if (postnlS10Nl.matches(upper) && hasValidS10CheckDigit(upper)) candidates += CarrierId.POSTNL
        if (postnl3s.matches(upper)) candidates += CarrierId.POSTNL
        if (cttNumeric.matches(number)) candidates += CarrierId.CTT
        if (dhlExpress.matches(number)) candidates += CarrierId.DHL
        if (dhlEcommerce.matches(upper)) candidates += CarrierId.DHL
        if (ecoscootingCn.matches(number)) candidates += CarrierId.ECOSCOOTING
        if (paackNumeric.matches(number)) candidates += CarrierId.PAACK
        if (dhlParcelInternational.matches(upper)) candidates += CarrierId.DHL
        if (ecoscootingNumeric.matches(number)) candidates += CarrierId.ECOSCOOTING
        if (number.length >= MinPaackCandidateLength) candidates += CarrierId.PAACK
        return candidates.toList()
    }

    /**
     * UPU S10 check digit over the 8 serial digits of a 13-char code
     * (`LL DDDDDDDD C LL`): weights 8,6,4,2,3,5,9,7, `check = 11 - (sum mod 11)`,
     * with 10 mapped to 0 and 11 to 5.
     */
    private fun hasValidS10CheckDigit(s10: String): Boolean {
        val sum = s10.substring(2, 10).mapIndexed { index, c -> c.digitToInt() * s10Weights[index] }.sum()
        val expected = when (val check = 11 - (sum % 11)) {
            10 -> 0
            11 -> 5
            else -> check
        }
        return s10[10].digitToInt() == expected
    }
}
