package com.luyan.wifiscout

import android.graphics.Color

/** Classificação do sinal a partir do RSSI (dBm). Quanto mais perto de 0, melhor. */
fun qualityLabel(rssi: Int): String = when {
    rssi >= -50 -> "Excelente"
    rssi >= -60 -> "Muito bom"
    rssi >= -70 -> "Bom"
    rssi >= -80 -> "Fraco"
    else -> "Muito fraco"
}

fun qualityColor(rssi: Int): Int = when {
    rssi >= -50 -> Color.parseColor("#22C55E")
    rssi >= -60 -> Color.parseColor("#84CC16")
    rssi >= -70 -> Color.parseColor("#FACC15")
    rssi >= -80 -> Color.parseColor("#F97316")
    else -> Color.parseColor("#EF4444")
}

/** -50 dBm ou mais = 100%, -100 dBm ou menos = 0%. */
fun signalPercent(rssi: Int): Int = (2 * (rssi + 100)).coerceIn(0, 100)

fun bandLabel(freqMhz: Int): String = when {
    freqMhz in 2400..2500 -> "2,4 GHz"
    freqMhz in 4900..5900 -> "5 GHz"
    freqMhz >= 5925 -> "6 GHz"
    else -> "$freqMhz MHz"
}
