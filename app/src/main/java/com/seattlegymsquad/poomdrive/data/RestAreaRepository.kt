package com.seattlegymsquad.poomdrive.data

import android.content.Context
import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class RestArea(
    val name: String,
    val route: String,
    val direction: String,
    val lat: Double,
    val lng: Double,
    val toilet: Boolean,
)

/** Drowsy-driving rest areas from the national standard data (한국도로공사, 253 places), bundled as an asset. */
class RestAreaRepository(private val context: Context) {

    val items: List<RestArea> by lazy {
        val json = context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val array = JSONObject(json).getJSONArray("items")
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            RestArea(
                name = o.getString("name"),
                route = o.getString("route"),
                direction = o.getString("direction"),
                lat = o.getDouble("lat"),
                lng = o.getDouble("lng"),
                toilet = o.getBoolean("toilet"),
            )
        }
    }

    /** Closest rest area by straight-line distance, optionally only those with a toilet. */
    fun nearest(lat: Double, lng: Double, needToilet: Boolean): Pair<RestArea, Double>? =
        items.asSequence()
            .filter { !needToilet || it.toilet }
            .map { it to distanceKm(lat, lng, it.lat, it.lng) }
            .minByOrNull { it.second }

    private fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(a))
    }

    private companion object {
        const val ASSET = "rest_areas/drowsy_rest_areas.json"
        const val EARTH_RADIUS_KM = 6371.0
    }
}
