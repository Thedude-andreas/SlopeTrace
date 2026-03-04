package com.slopetrace.domain.coords

import kotlin.math.cos
import kotlin.math.sin

class CoordinateConverter {

    data class Geodetic(val latDeg: Double, val lonDeg: Double, val altM: Double)
    data class Enu(val eastM: Double, val northM: Double, val upM: Double)

    private var origin: Geodetic? = null
    private var originEcef: DoubleArray? = null

    fun reset() {
        origin = null
        originEcef = null
    }

    fun toEnu(latDeg: Double, lonDeg: Double, altM: Double): Enu {
        val current = Geodetic(latDeg, lonDeg, altM)
        if (origin == null) {
            origin = current
            originEcef = geodeticToEcef(current)
            return Enu(0.0, 0.0, 0.0)
        }

        val ref = origin!!
        val refEcef = originEcef!!
        val curEcef = geodeticToEcef(current)
        val dx = curEcef[0] - refEcef[0]
        val dy = curEcef[1] - refEcef[1]
        val dz = curEcef[2] - refEcef[2]

        val lat = Math.toRadians(ref.latDeg)
        val lon = Math.toRadians(ref.lonDeg)

        val east = -sin(lon) * dx + cos(lon) * dy
        val north = -sin(lat) * cos(lon) * dx - sin(lat) * sin(lon) * dy + cos(lat) * dz
        val up = cos(lat) * cos(lon) * dx + cos(lat) * sin(lon) * dy + sin(lat) * dz

        return Enu(east, north, up)
    }

    private fun geodeticToEcef(g: Geodetic): DoubleArray {
        val a = 6378137.0
        val e2 = 6.69437999014e-3

        val lat = Math.toRadians(g.latDeg)
        val lon = Math.toRadians(g.lonDeg)

        val sinLat = sin(lat)
        val cosLat = cos(lat)
        val cosLon = cos(lon)
        val sinLon = sin(lon)

        val n = a / kotlin.math.sqrt(1.0 - e2 * sinLat * sinLat)

        val x = (n + g.altM) * cosLat * cosLon
        val y = (n + g.altM) * cosLat * sinLon
        val z = (n * (1.0 - e2) + g.altM) * sinLat
        return doubleArrayOf(x, y, z)
    }
}
