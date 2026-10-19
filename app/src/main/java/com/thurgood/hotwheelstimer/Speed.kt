package com.thurgood.hotwheelstimer

import java.util.Locale

/** Turns a track length and a time into speeds. Hot Wheels cars are 1:64 scale. */
object Speed {
    const val SCALE = 64.0
    private const val MPS_TO_MPH = 2.236936
    private const val MPS_TO_KMH = 3.6

    fun metresPerSecond(distanceM: Double, timeS: Double) = if (timeS > 0) distanceM / timeS else 0.0

    /** How fast a real, full-size car would be going at the same scale. */
    fun scaleMph(distanceM: Double, timeS: Double) = metresPerSecond(distanceM, timeS) * SCALE * MPS_TO_MPH

    fun short(distanceM: Double, timeS: Double) = String.format(
        Locale.UK, "%.2f m/s · %.0f scale mph",
        metresPerSecond(distanceM, timeS), scaleMph(distanceM, timeS)
    )

    fun detailed(distanceM: Double, timeS: Double) = String.format(
        Locale.UK,
        "Track: %.2f m\nSpeed: %.2f m/s (%.1f km/h)\nScale speed: %.0f mph 🚀",
        distanceM, metresPerSecond(distanceM, timeS),
        metresPerSecond(distanceM, timeS) * MPS_TO_KMH, scaleMph(distanceM, timeS)
    )

    fun length(distanceM: Double) = String.format(Locale.UK, "%.2f m", distanceM)
}
