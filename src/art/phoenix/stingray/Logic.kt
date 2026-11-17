package art.phoenix.stingray

import kotlin.math.*

/**
 * Stingray Tower Detector v1.0 — PASSIVE, DEFENSIVE ONLY.
 *
 * This app NEVER transmits, intercepts, jams, spoofs, or acts as an
 * IMSI catcher. It only reads the phone's OWN serving-cell info via
 * Android TelephonyManager (CellInfo) and logs/alerts on anomalies.
 * It captures no IMSI, no call/SMS content, and forces nothing onto
 * any tower. Every alert is a heuristic — NO alert is proof of a
 * stingray / fake tower. That warning is shown in the UI.
 *
 * Pure logic in this file — no Android classes, JVM-testable.
 */

enum class Risk { LOW, MEDIUM, HIGH }

data class CellSnapshot(
    val mcc: String = "",
    val mnc: String = "",
    val lac: Int = -1,          // LAC (2G/3G) or TAC (4G/5G)
    val cid: String = "",       // CID (2G/3G/4G) or NCI (5G)
    val radio: String = "Unknown", // 2G / 3G / 4G / 5G / Unknown
    val dbm: Int = -999,        // signal strength, dBm; -999 = unknown
    val neighborCount: Int = 0,
    val operator: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val at: Long = 0L
) {
    fun cellKey(): String = "$mcc-$mnc-$lac-$cid"
    fun hasIdentity(): Boolean = cid.isNotEmpty() && cid != "-1" && mcc.isNotEmpty()
}

data class Alert(val reason: String, val risk: Risk, val detail: String, val canaryId: String = "")

/** OSM Web-Mercator tile math, copied from newtool (pure, testable). */
object TileMath {
    fun lonToTileX(lon: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    fun latToTileY(lat: Double, zoom: Int): Int {
        val n = 1 shl zoom
        val rad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        val y = (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * n
        return floor(y).toInt().coerceIn(0, n - 1)
    }

    fun tileXToLon(x: Int, zoom: Int): Double {
        val n = 1 shl zoom
        return x.toDouble() / n * 360.0 - 180.0
    }

    fun tileYToLat(y: Int, zoom: Int): Double {
        val n = 1 shl zoom
        val z = PI * (1.0 - 2.0 * y.toDouble() / n)
        return Math.toDegrees(atan(sinh(z)))
    }

    fun latLonToTilePixel(lat: Double, lon: Double, zoom: Int): Pair<Double, Double> {
        val n = (1 shl zoom).toDouble()
        val x = (lon + 180.0) / 360.0 * n
        val rad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        val y = (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * n
        return Pair((x - floor(x)) * 256.0, (y - floor(y)) * 256.0)
    }

    val OFFLINE_ZOOMS = 12..15
}

/**
 * Canary ID for an alert — a LOCAL tracking ID only.
 *
 * Format: STINGRAY-<uuid>-<epochMillis>-<cellFingerprint>
 * The cell fingerprint is a short hex hash of the cell key, so the same
 * tower at the same time cannot silently produce the same ID, while the
 * ID itself reveals no IMSI, phone number, or location.
 * It is NOT a canarytokens.org URL and is never posted anywhere.
 * An optional, opt-in canarytokens.org token is a separate, user-chosen
 * step; the app never creates one by itself and never uploads this ID.
 */
object Canary {
    fun fingerprint(cellKey: String): String {
        var h = 0
        for (c in cellKey) h = h * 31 + c.code
        return Integer.toHexString(kotlin.math.abs(h)).padStart(8, '0')
    }

    fun makeId(at: Long, cellKey: String): String =
        "STINGRAY-${java.util.UUID.randomUUID()}-$at-${fingerprint(cellKey)}"

    fun isValid(id: String): Boolean {
        if (!id.startsWith("STINGRAY-")) return false
        val parts = id.split("-")
        // STINGRAY + 5 UUID groups + timestamp + fingerprint = 8 parts
        if (parts.size != 8) return false
        return parts[6].toLongOrNull() != null && parts[7].length == 8
    }

    fun timestampOf(id: String): Long? =
        if (isValid(id)) id.split("-")[6].toLongOrNull() else null
}

/** "What to do now" — shown on the alert screen. Text only; the app
 *  never changes a phone setting by itself. Buttons in the UI open the
 *  relevant system Settings screen, and only when the user taps them. */
object WhatToDo {
    fun steps(alerts: List<Alert>, radio: String): List<String> {
        val out = mutableListOf<String>()
        out.add("1. Do NOT make sensitive calls or send sensitive texts/SMS right now — " +
            "a fake tower can see unencrypted calls and texts.")
        out.add("2. Turn on Airplane mode for 10 seconds, then turn it off. " +
            "This forces your phone to re-attach to a real tower. " +
            "(Button below opens Airplane mode settings — you tap the switch yourself.)")
        if (radio == "2G" || alerts.any { it.reason.contains("2G fallback") }) {
            out.add("3. Turn off 2G if your phone allows it (Settings > Mobile network > " +
                "Preferred network type > 4G/5G only). 2G has weak security — that is why " +
                "fake towers force a downgrade to it. Some phones/carriers do not offer this switch.")
        }
        out.add("4. Move away from this spot and, if you can, use Wi-Fi calling " +
            "instead of the mobile network until you are somewhere else.")
        out.add("5. Use encrypted apps only (e.g. Signal / WhatsApp) for anything sensitive — " +
            "their messages stay encrypted even if the tower is fake.")
        out.add("6. Note the time, place, and canary ID on this alert. Export the CSV " +
            "from the Status screen if you want a record. This alert is a heuristic, " +
            "not proof of a stingray — do not confront anyone about it.")
        return out
    }
}

/** One alert plotted on the map. Pure data — built from a stored alert row. */
data class AlertPinData(
    val at: Long, val lat: Double, val lon: Double, val cellKey: String,
    val mcc: String, val mnc: String, val lac: Int, val cid: String,
    val radio: String, val dbm: Int, val risk: Risk,
    val reason: String, val detail: String, val canaryId: String
)

object MapPins {
    fun title(pin: AlertPinData): String =
        "${Detector.riskLabel(pin.risk)} — ${pin.radio} ${pin.cid}"

    /** Full detail shown when a pin is tapped. */
    fun detail(pin: AlertPinData): String =
        "Time: ${pin.at}\n" +
            "Risk: ${Detector.riskLabel(pin.risk)}\n" +
            "Reason: ${pin.reason}\n${pin.detail}\n" +
            "MCC/MNC: ${pin.mcc}/${pin.mnc}   LAC/TAC: ${if (pin.lac < 0) "—" else pin.lac}\n" +
            "CID: ${pin.cid}   Radio: ${pin.radio}   Signal: ${if (pin.dbm == -999) "—" else "${pin.dbm} dBm"}\n" +
            "GPS: ${pin.lat}, ${pin.lon}\n" +
            "Cell key: ${pin.cellKey}\n" +
            "Canary ID: ${if (pin.canaryId.isEmpty()) "—" else pin.canaryId}"

    fun kind(pin: AlertPinData): String = when (pin.risk) {
        Risk.HIGH -> "alert_high"
        Risk.MEDIUM -> "alert_medium"
        else -> "alert_low"
    }

    fun sorted(pins: List<AlertPinData>): List<AlertPinData> =
        pins.sortedByDescending { it.at }
}

/** Haversine distance in metres between two lat/lon points. */
fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) *
        cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
    return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

object Detector {
    const val STATIONARY_METERS = 50.0
    const val MOVING_METERS = 200.0
    const val SAME_CELL_FAR_METERS = 5000.0
    const val STRONG_DBM = -85
    const val SIGNAL_JUMP_DB = 20

    /**
     * Analyse one new serving-cell reading against the previous reading,
     * the SIM operator, the learned baseline, and cells seen before.
     *
     * @param prev previous serving cell, or null on first scan
     * @param cur current serving cell
     * @param simMcc/simMnc from the SIM (TelephonyManager.simOperator);
     *   empty strings = unknown, mismatch rule is skipped
     * @param baselineKeys cell keys learned via "Learn my area"
     * @param seenCells cell key -> (lat, lon) where that key was seen before
     * @param movedMeters GPS distance moved since the previous scan
     * @param prevNeighborCount neighbour count on the previous scan, or null
     */
    fun analyze(
        prev: CellSnapshot?,
        cur: CellSnapshot,
        simMcc: String,
        simMnc: String,
        baselineKeys: Set<String>,
        seenCells: Map<String, Pair<Double, Double>>,
        movedMeters: Double,
        prevNeighborCount: Int?
    ): List<Alert> {
        val out = mutableListOf<Alert>()
        if (!cur.hasIdentity()) return out

        // 1. MCC/MNC mismatch vs the SIM operator — a tower claiming to
        //    be a different carrier than your SIM is a strong red flag.
        if (simMcc.isNotEmpty() && simMnc.isNotEmpty() &&
            (cur.mcc != simMcc || cur.mnc != simMnc)
        ) {
            out.add(Alert(
                "Operator mismatch",
                Risk.HIGH,
                "Tower says MCC/MNC ${cur.mcc}/${cur.mnc}, but your SIM is $simMcc/$simMnc. " +
                    "A real tower for your carrier should match your SIM."
            ))
        }

        // 2. Same CID seen far away — a real tower cannot move.
        val seenAt = seenCells[cur.cellKey()]
        if (seenAt != null && cur.lat != 0.0 && cur.lon != 0.0 &&
            seenAt.first != 0.0 && seenAt.second != 0.0
        ) {
            val d = distanceMeters(seenAt.first, seenAt.second, cur.lat, cur.lon)
            if (d > SAME_CELL_FAR_METERS) {
                out.add(Alert(
                    "Same tower ID at a distant location",
                    Risk.HIGH,
                    "Cell ${cur.cellKey()} was previously seen ${"%.1f".format(d / 1000.0)} km away. " +
                        "A real tower does not move; the same ID in two far-apart places is suspicious."
                ))
            }
        }

        if (prev != null && prev.hasIdentity()) {
            val cidChanged = prev.cid != cur.cid || prev.lac != cur.lac
            val stationary = movedMeters < STATIONARY_METERS

            // 3. Sudden 2G fallback from 4G/5G. IMSI catchers force 2G
            //    because 2G has weak/no mutual authentication.
            if (cur.radio == "2G" && (prev.radio == "4G" || prev.radio == "5G")) {
                if (cur.dbm != -999 && cur.dbm >= STRONG_DBM) {
                    out.add(Alert(
                        "Sudden 2G fallback with strong signal",
                        Risk.HIGH,
                        "Dropped from ${prev.radio} to 2G while the 2G signal is strong (${cur.dbm} dBm). " +
                            "A strong 2G cell appearing suddenly can be a fake tower forcing a downgrade."
                    ))
                } else {
                    out.add(Alert(
                        "Sudden 2G fallback",
                        Risk.MEDIUM,
                        "Dropped from ${prev.radio} to 2G (signal ${if (cur.dbm == -999) "unknown" else "${cur.dbm} dBm"}). " +
                            "Could be a real coverage gap — check whether 4G/5G returns nearby."
                    ))
                }
            }

            // 4. CID change while stationary — towers do not change
            //    under a phone that has barely moved.
            if (prev.cid != cur.cid && stationary) {
                out.add(Alert(
                    "Tower changed while you barely moved",
                    Risk.MEDIUM,
                    "Serving CID changed ${prev.cid} -> ${cur.cid} after moving only " +
                        "${"%.0f".format(movedMeters)} m. Real handovers usually happen when you move."
                ))
            }

            // 5. LAC/TAC change without moving.
            if (prev.lac != cur.lac && prev.lac >= 0 && cur.lac >= 0 && stationary) {
                out.add(Alert(
                    "Area code (LAC/TAC) changed without moving",
                    Risk.MEDIUM,
                    "LAC/TAC changed ${prev.lac} -> ${cur.lac} after moving only " +
                        "${"%.0f".format(movedMeters)} m."
                ))
            }

            // 6. Strong signal jump with no neighbour cells.
            if (prev.dbm != -999 && cur.dbm != -999 &&
                cur.dbm - prev.dbm >= SIGNAL_JUMP_DB && cur.neighborCount == 0
            ) {
                out.add(Alert(
                    "Strong signal jump, no neighbour cells",
                    if (cur.dbm - prev.dbm >= 30) Risk.HIGH else Risk.MEDIUM,
                    "Signal jumped ${prev.dbm} -> ${cur.dbm} dBm (+${cur.dbm - prev.dbm} dB) " +
                        "and this cell reports no neighbours. A nearby fake tower can look like this."
                ))
            }

            // 7. Neighbour list suddenly empty.
            if (prevNeighborCount != null && prevNeighborCount > 0 && cur.neighborCount == 0) {
                out.add(Alert(
                    "Neighbour cells suddenly gone",
                    Risk.MEDIUM,
                    "Previous cell reported $prevNeighborCount neighbour(s); this cell reports none. " +
                        "Real towers normally see neighbours; a lone cell with none is unusual."
                ))
            }
            @Suppress("UNUSED_VARIABLE")
            val unused = cidChanged
        }

        // 8. New / unseen CID vs the learned baseline. Only a real
        //    signal when stationary — driving into a new area legitimately
        //    hands over to new towers all the time.
        if (baselineKeys.isNotEmpty() && cur.cellKey() !in baselineKeys) {
            if (movedMeters < 100.0) {
                out.add(Alert(
                    "New tower never seen in your learned area",
                    Risk.MEDIUM,
                    "Cell ${cur.cellKey()} is not in your learned baseline for this area, " +
                        "and you have barely moved (${"%.0f".format(movedMeters)} m)."
                ))
            } else {
                out.add(Alert(
                    "New tower (you are moving)",
                    Risk.LOW,
                    "Cell ${cur.cellKey()} is not in your learned baseline, but you moved " +
                        "${"%.0f".format(movedMeters)} m — new towers are normal while travelling."
                ))
            }
        }
        // Canary ID: every Medium/High alert gets a unique local tracking
        // ID (UUID + timestamp + cell fingerprint). Low alerts get none.
        return out.map { a ->
            if (a.risk == Risk.MEDIUM || a.risk == Risk.HIGH) a.copy(canaryId = Canary.makeId(cur.at, cur.cellKey()))
            else a
        }
    }

    /** Normal handover: same operator + area, neighbours present, moving. */
    fun isNormalHandover(prev: CellSnapshot, cur: CellSnapshot, movedMeters: Double): Boolean =
        prev.cid != cur.cid &&
            prev.mcc == cur.mcc && prev.mnc == cur.mnc && prev.lac == cur.lac &&
            cur.neighborCount > 0 && movedMeters >= MOVING_METERS

    /** Overall status from a list of alerts. */
    fun status(alerts: List<Alert>): String = when {
        alerts.any { it.risk == Risk.HIGH } -> "ALERT"
        alerts.any { it.risk == Risk.MEDIUM } -> "WATCH"
        else -> "SAFE"
    }

    fun riskLabel(r: Risk): String = when (r) {
        Risk.LOW -> "Low"
        Risk.MEDIUM -> "Medium"
        Risk.HIGH -> "High"
    }
}

/** CSV helpers (pure, testable). */
object Csv {
    fun escape(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun sightingsHeader(): String =
        "time,mcc,mnc,lac,cid,radio,dbm_db,neighbors,operator,lat,lon\n"

    fun sightingRow(at: Long, c: CellSnapshot): String =
        listOf(
            at.toString(), c.mcc, c.mnc, c.lac.toString(), c.cid, c.radio,
            c.dbm.toString(), c.neighborCount.toString(), escape(c.operator),
            c.lat.toString(), c.lon.toString()
        ).joinToString(",")

    fun alertsHeader(): String = "time,lat,lon,cell_key,mcc,mnc,lac,cid,radio,dbm,risk,reason,detail,canary_id\n"

    fun alertRow(at: Long, lat: Double, lon: Double, cellKey: String, a: Alert): String =
        alertRowFull(at, lat, lon, cellKey, "", "", -1, "", "", -999, a)

    fun alertRowFull(
        at: Long, lat: Double, lon: Double, cellKey: String,
        mcc: String, mnc: String, lac: Int, cid: String, radio: String, dbm: Int, a: Alert
    ): String =
        listOf(
            at.toString(), lat.toString(), lon.toString(), escape(cellKey),
            mcc, mnc, lac.toString(), cid, radio, dbm.toString(),
            Detector.riskLabel(a.risk), escape(a.reason), escape(a.detail), a.canaryId
        ).joinToString(",")
}
