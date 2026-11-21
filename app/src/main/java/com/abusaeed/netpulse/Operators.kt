package com.abusaeed.netpulse

/** Bangladesh operators (MCC 470) — name and brand colour by MNC. */
object Operators {
    data class Op(val name: String, val short: String, val color: Int)

    private val bd = mapOf(
        "01" to Op("Grameenphone", "GP", 0xFF1BA0E2.toInt()),
        "02" to Op("Robi", "Robi", 0xFFE4002B.toInt()),
        "03" to Op("Banglalink", "BL", 0xFFF47920.toInt()),
        "04" to Op("Teletalk", "TT", 0xFF8DC63F.toInt()),
        "07" to Op("Airtel", "Airtel", 0xFFED1C24.toInt()),
    )

    fun find(mcc: String?, mnc: String?): Op? {
        if (mcc != "470" || mnc == null) return null
        return bd[mnc.padStart(2, '0')]
    }

    /** "470-01" style code -> operator, using the network operator string ("47001"). */
    fun fromCode(code: String): Op? = if (code.length >= 5) find(code.take(3), code.drop(3)) else null

    fun label(mcc: String?, mnc: String?): String =
        find(mcc, mnc)?.name ?: if (mcc != null && mnc != null) "$mcc-$mnc" else "--"
}
