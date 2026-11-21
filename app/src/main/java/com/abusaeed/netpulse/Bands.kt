package com.abusaeed.netpulse

/** EARFCN / UARFCN / ARFCN / NR-ARFCN -> band and frequency (MHz). */
object Bands {
    data class Info(val band: String?, val dl: Double?, val ul: Double?)

    // kind: F = FDD, T = TDD, S = supplemental downlink
    private data class Lte(
        val band: Int, val fdlLow: Double, val nOffDl: Int, val nMax: Int,
        val fulLow: Double, val kind: Char
    )

    private val lteBands = listOf(
        Lte(1, 2110.0, 0, 599, 1920.0, 'F'),
        Lte(2, 1930.0, 600, 1199, 1850.0, 'F'),
        Lte(3, 1805.0, 1200, 1949, 1710.0, 'F'),
        Lte(4, 2110.0, 1950, 2399, 1710.0, 'F'),
        Lte(5, 869.0, 2400, 2649, 824.0, 'F'),
        Lte(7, 2620.0, 2750, 3449, 2500.0, 'F'),
        Lte(8, 925.0, 3450, 3799, 880.0, 'F'),
        Lte(12, 729.0, 5010, 5179, 699.0, 'F'),
        Lte(13, 746.0, 5180, 5279, 777.0, 'F'),
        Lte(17, 734.0, 5730, 5849, 704.0, 'F'),
        Lte(20, 791.0, 6150, 6449, 832.0, 'F'),
        Lte(25, 1930.0, 8040, 8689, 1850.0, 'F'),
        Lte(26, 859.0, 8690, 9039, 814.0, 'F'),
        Lte(28, 758.0, 9210, 9659, 703.0, 'F'),
        Lte(32, 1452.0, 9920, 10359, 0.0, 'S'),
        Lte(38, 2570.0, 37750, 38249, 0.0, 'T'),
        Lte(39, 1880.0, 38250, 38649, 0.0, 'T'),
        Lte(40, 2300.0, 38650, 39649, 0.0, 'T'),
        Lte(41, 2496.0, 39650, 41589, 0.0, 'T'),
        Lte(42, 3400.0, 41590, 43589, 0.0, 'T'),
        Lte(43, 3600.0, 43590, 45589, 0.0, 'T'),
        Lte(66, 2110.0, 66436, 67335, 1710.0, 'F'),
        Lte(71, 617.0, 68586, 68935, 663.0, 'F'),
    )

    private val lteNames = mapOf(
        1 to "2100", 2 to "1900", 3 to "1800", 4 to "AWS", 5 to "850", 7 to "2600",
        8 to "900", 12 to "700", 13 to "700", 17 to "700", 20 to "800", 25 to "1900",
        26 to "850", 28 to "700", 32 to "1500", 38 to "2600", 39 to "1900", 40 to "2300",
        41 to "2500", 42 to "3500", 43 to "3700", 66 to "AWS-3", 71 to "600"
    )

    fun lteLabel(band: Int): String = lteNames[band]?.let { "B$band ($it)" } ?: "B$band"

    fun lte(earfcn: Int): Info {
        val b = lteBands.firstOrNull { earfcn in it.nOffDl..it.nMax } ?: return Info(null, null, null)
        val dl = b.fdlLow + 0.1 * (earfcn - b.nOffDl)
        val ul = when (b.kind) {
            'F' -> b.fulLow + 0.1 * (earfcn - b.nOffDl)
            'T' -> dl
            else -> null
        }
        return Info(lteLabel(b.band), dl, ul)
    }

    fun umts(uarfcn: Int): Info = when (uarfcn) {
        in 10562..10838 -> Info("B1 (2100)", uarfcn / 5.0, uarfcn / 5.0 - 190)
        in 9662..9938 -> Info("B2 (1900)", uarfcn / 5.0, uarfcn / 5.0 - 80)
        in 1537..1738 -> Info("B4 (AWS)", 1805 + uarfcn / 5.0, 1805 + uarfcn / 5.0 - 400)
        in 4357..4458 -> Info("B5 (850)", uarfcn / 5.0, uarfcn / 5.0 - 45)
        in 2937..3088 -> Info("B8 (900)", 340 + uarfcn / 5.0, 340 + uarfcn / 5.0 - 45)
        else -> Info(null, uarfcn / 5.0, null)
    }

    fun gsm(arfcn: Int): Info = when (arfcn) {
        in 0..124 -> Info("GSM900", 935 + 0.2 * arfcn, 890 + 0.2 * arfcn)
        in 975..1023 -> Info("E-GSM900", 935 + 0.2 * (arfcn - 1024), 890 + 0.2 * (arfcn - 1024))
        in 512..885 -> Info("DCS1800", 1805.2 + 0.2 * (arfcn - 512), 1710.2 + 0.2 * (arfcn - 512))
        in 128..251 -> Info("GSM850", 869.2 + 0.2 * (arfcn - 128), 824.2 + 0.2 * (arfcn - 128))
        else -> Info(null, null, null)
    }

    fun nr(arfcn: Int): Info {
        val f = when {
            arfcn < 600000 -> 0.005 * arfcn
            arfcn < 2016667 -> 3000 + 0.015 * (arfcn - 600000)
            else -> 24250.08 + 0.06 * (arfcn - 2016667)
        }
        val band = when (arfcn) {
            in 620000..653333 -> "n78"
            in 653334..680000 -> "n77"
            in 499200..537999 -> "n41"
            in 460000..480000 -> "n40"
            in 422000..434000 -> "n1"
            in 361000..376000 -> "n3"
            in 185000..192000 -> "n8"
            in 151600..160600 -> "n28"
            else -> null
        }
        return Info(band, f, null)
    }
}
