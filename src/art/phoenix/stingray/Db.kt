package art.phoenix.stingray

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SightingRow(
    val id: Long = 0, val at: Long = 0, val cell: CellSnapshot = CellSnapshot()
)

data class AlertRow(
    val id: Long = 0, val at: Long = 0, val lat: Double = 0.0, val lon: Double = 0.0,
    val cellKey: String = "", val risk: String = "", val reason: String = "", val detail: String = "",
    val canaryId: String = "", val radio: String = "", val dbm: Int = -999,
    val mcc: String = "", val mnc: String = "", val lac: Int = -1, val cid: String = ""
) {
    fun riskEnum(): Risk = when (risk) {
        "High" -> Risk.HIGH
        "Medium" -> Risk.MEDIUM
        else -> Risk.LOW
    }

    fun toPin(): AlertPinData = AlertPinData(
        at, lat, lon, cellKey, mcc, mnc, lac, cid, radio, dbm,
        riskEnum(), reason, detail, canaryId
    )
}

data class BaselineRow(
    val cellKey: String, val firstSeen: Long, val lastSeen: Long, val count: Int,
    val lat: Double, val lon: Double
)

/** Local-only SQLite store. Nothing leaves the phone except a CSV the user exports. */
class StingrayDb(ctx: Context) : SQLiteOpenHelper(ctx, "stingray.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sightings(id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER, " +
                "mcc TEXT, mnc TEXT, lac INTEGER, cid TEXT, radio TEXT, dbm INTEGER, " +
                "neighbors INTEGER, operator TEXT, lat REAL, lon REAL)"
        )
        db.execSQL(
            "CREATE TABLE alerts(id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER, " +
                "lat REAL, lon REAL, cell_key TEXT, risk TEXT, reason TEXT, detail TEXT, " +
                "canary_id TEXT, radio TEXT, dbm INTEGER, mcc TEXT, mnc TEXT, lac INTEGER, cid TEXT)"
        )
        db.execSQL(
            "CREATE TABLE baseline(cell_key TEXT PRIMARY KEY, first_seen INTEGER, " +
                "last_seen INTEGER, count INTEGER, lat REAL, lon REAL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    fun addSighting(c: CellSnapshot): Long {
        val v = ContentValues().apply {
            put("at", c.at); put("mcc", c.mcc); put("mnc", c.mnc); put("lac", c.lac)
            put("cid", c.cid); put("radio", c.radio); put("dbm", c.dbm)
            put("neighbors", c.neighborCount); put("operator", c.operator)
            put("lat", c.lat); put("lon", c.lon)
        }
        return writableDatabase.insert("sightings", null, v)
    }

    fun listSightings(limit: Int = 200): List<SightingRow> {
        val out = mutableListOf<SightingRow>()
        readableDatabase.rawQuery(
            "SELECT id,at,mcc,mnc,lac,cid,radio,dbm,neighbors,operator,lat,lon " +
                "FROM sightings ORDER BY at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cur ->
            while (cur.moveToNext()) out.add(
                SightingRow(
                    cur.getLong(0), cur.getLong(1),
                    CellSnapshot(
                        cur.getString(2) ?: "", cur.getString(3) ?: "", cur.getInt(4),
                        cur.getString(5) ?: "", cur.getString(6) ?: "", cur.getInt(7),
                        cur.getInt(8), cur.getString(9) ?: "", cur.getDouble(10),
                        cur.getDouble(11), cur.getLong(1)
                    )
                )
            )
        }
        return out
    }

    /** Store an alert with its full cell details and its canary ID. */
    fun addAlert(at: Long, cell: CellSnapshot, a: Alert): Long {
        val v = ContentValues().apply {
            put("at", at); put("lat", cell.lat); put("lon", cell.lon); put("cell_key", cell.cellKey())
            put("risk", Detector.riskLabel(a.risk)); put("reason", a.reason); put("detail", a.detail)
            put("canary_id", a.canaryId); put("radio", cell.radio); put("dbm", cell.dbm)
            put("mcc", cell.mcc); put("mnc", cell.mnc); put("lac", cell.lac); put("cid", cell.cid)
        }
        return writableDatabase.insert("alerts", null, v)
    }

    fun listAlerts(limit: Int = 200): List<AlertRow> {
        val out = mutableListOf<AlertRow>()
        readableDatabase.rawQuery(
            "SELECT id,at,lat,lon,cell_key,risk,reason,detail,canary_id,radio,dbm,mcc,mnc,lac,cid " +
                "FROM alerts ORDER BY at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cur ->
            while (cur.moveToNext()) out.add(
                AlertRow(
                    cur.getLong(0), cur.getLong(1), cur.getDouble(2), cur.getDouble(3),
                    cur.getString(4) ?: "", cur.getString(5) ?: "", cur.getString(6) ?: "",
                    cur.getString(7) ?: "", cur.getString(8) ?: "", cur.getString(9) ?: "",
                    cur.getInt(10), cur.getString(11) ?: "", cur.getString(12) ?: "",
                    cur.getInt(13), cur.getString(14) ?: ""
                )
            )
        }
        return out
    }

    fun alertCount(): Int = count("alerts")
    fun sightingCount(): Int = count("sightings")
    fun baselineCount(): Int = count("baseline")

    private fun count(t: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $t", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    /** "Learn my area": record this cell as a known-good tower. */
    fun learnCell(c: CellSnapshot) {
        val key = c.cellKey()
        val db = writableDatabase
        val existing = db.rawQuery("SELECT count FROM baseline WHERE cell_key=?", arrayOf(key)).use { cur ->
            if (cur.moveToFirst()) cur.getInt(0) else null
        }
        val v = ContentValues().apply {
            put("cell_key", key); put("last_seen", c.at); put("lat", c.lat); put("lon", c.lon)
            if (existing != null) put("count", existing + 1) else { put("first_seen", c.at); put("count", 1) }
        }
        if (existing != null) db.update("baseline", v, "cell_key=?", arrayOf(key))
        else db.insertWithOnConflict("baseline", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun baselineKeys(): Set<String> {
        val out = mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT cell_key FROM baseline", null).use { cur ->
            while (cur.moveToNext()) out.add(cur.getString(0) ?: "")
        }
        return out
    }

    fun listBaseline(): List<BaselineRow> {
        val out = mutableListOf<BaselineRow>()
        readableDatabase.rawQuery(
            "SELECT cell_key,first_seen,last_seen,count,lat,lon FROM baseline ORDER BY last_seen DESC LIMIT 500",
            null
        ).use { cur ->
            while (cur.moveToNext()) out.add(
                BaselineRow(
                    cur.getString(0) ?: "", cur.getLong(1), cur.getLong(2),
                    cur.getInt(3), cur.getDouble(4), cur.getDouble(5)
                )
            )
        }
        return out
    }

    /** cell key -> (lat, lon) of its first recorded sighting, for the "same CID far away" rule. */
    fun seenCellLocations(): Map<String, Pair<Double, Double>> {
        val out = LinkedHashMap<String, Pair<Double, Double>>()
        readableDatabase.rawQuery(
            "SELECT mcc,mnc,lac,cid,lat,lon FROM sightings WHERE lat != 0 AND lon != 0 ORDER BY at ASC",
            null
        ).use { cur ->
            while (cur.moveToNext()) {
                val key = "${cur.getString(0)}-${cur.getString(1)}-${cur.getInt(2)}-${cur.getString(3)}"
                if (key !in out) out[key] = Pair(cur.getDouble(4), cur.getDouble(5))
            }
        }
        return out
    }

    fun clearBaseline() { writableDatabase.execSQL("DELETE FROM baseline") }

    fun exportSightingsCsv(): String {
        val sb = StringBuilder(Csv.sightingsHeader())
        for (row in listSightings(10000)) sb.append(Csv.sightingRow(row.at, row.cell)).append('\n')
        return sb.toString()
    }

    fun exportAlertsCsv(): String {
        val sb = StringBuilder(Csv.alertsHeader())
        for (row in listAlerts(10000)) {
            sb.append(
                Csv.alertRowFull(
                    row.at, row.lat, row.lon, row.cellKey,
                    row.mcc, row.mnc, row.lac, row.cid, row.radio, row.dbm,
                    Alert(row.reason, row.riskEnum(), row.detail, row.canaryId)
                )
            ).append('\n')
        }
        return sb.toString()
    }

    /** Every alert as a map pin, newest first. */
    fun alertPins(): List<AlertPinData> =
        MapPins.sorted(listAlerts(10000).map { it.toPin() })
}
