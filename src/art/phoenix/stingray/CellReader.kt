package art.phoenix.stingray

import android.annotation.SuppressLint
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager

/**
 * Reads the phone's OWN serving cell only, via TelephonyManager.allCellInfo.
 * Passive read — nothing is transmitted, no IMSI is read, no tower is
 * contacted beyond what the phone already does to stay connected.
 *
 * Known Android limits (shown on the Test screen):
 * - allCellInfo may return an empty list on some phones/carriers, or
 *   only the serving cell with no neighbours.
 * - CID/LAC may be Integer.MAX_VALUE (unknown) on some carriers.
 * - Precise cell data needs READ_PHONE_STATE + location permission.
 */
object CellReader {

    data class ReadResult(
        val snapshot: CellSnapshot?,
        val allCount: Int,
        val error: String?
    )

    @SuppressLint("MissingPermission")
    fun read(tm: TelephonyManager?, lat: Double, lon: Double, now: Long): ReadResult {
        if (tm == null) return ReadResult(null, 0, "No TelephonyManager (no cellular radio?)")
        val infos: List<CellInfo>
        try {
            infos = tm.allCellInfo ?: emptyList()
        } catch (e: SecurityException) {
            return ReadResult(null, 0, "Permission denied: ${e.message}")
        } catch (e: Exception) {
            return ReadResult(null, 0, "CellInfo read failed: ${e.message}")
        }
        if (infos.isEmpty()) return ReadResult(null, 0, "CellInfo empty — carrier/phone reports no cells")
        var serving: CellSnapshot? = null
        var neighbours = 0
        for (info in infos) {
            if (info.isRegistered) {
                serving = toSnapshot(info, lat, lon, now)
            } else {
                neighbours++
            }
        }
        if (serving == null) return ReadResult(null, infos.size, "No registered (serving) cell in list")
        return ReadResult(serving.copy(neighborCount = neighbours), infos.size, null)
    }

    private fun valid(v: Int): Int = if (v == Int.MAX_VALUE) -1 else v

    private fun toSnapshot(info: CellInfo, lat: Double, lon: Double, now: Long): CellSnapshot {
        return when (info) {
            is CellInfoGsm -> {
                val id = info.cellIdentity as? CellIdentityGsm
                CellSnapshot(
                    mcc = id?.mccString ?: "", mnc = id?.mncString ?: "",
                    lac = valid(id?.lac ?: Int.MAX_VALUE), cid = (id?.cid ?: -1).toString(),
                    radio = "2G", dbm = info.cellSignalStrength.dbm,
                    operator = id?.operatorAlphaLong?.toString() ?: "",
                    lat = lat, lon = lon, at = now
                )
            }
            is CellInfoWcdma -> {
                val id = info.cellIdentity as? CellIdentityWcdma
                CellSnapshot(
                    mcc = id?.mccString ?: "", mnc = id?.mncString ?: "",
                    lac = valid(id?.lac ?: Int.MAX_VALUE), cid = (id?.cid ?: -1).toString(),
                    radio = "3G", dbm = info.cellSignalStrength.dbm,
                    operator = id?.operatorAlphaLong?.toString() ?: "",
                    lat = lat, lon = lon, at = now
                )
            }
            is CellInfoLte -> {
                val id = info.cellIdentity as? CellIdentityLte
                CellSnapshot(
                    mcc = id?.mccString ?: "", mnc = id?.mncString ?: "",
                    lac = valid(id?.tac ?: Int.MAX_VALUE), cid = (id?.ci ?: -1).toString(),
                    radio = "4G", dbm = info.cellSignalStrength.dbm,
                    operator = id?.operatorAlphaLong?.toString() ?: "",
                    lat = lat, lon = lon, at = now
                )
            }
            is CellInfoNr -> {
                val id = info.cellIdentity as? CellIdentityNr
                CellSnapshot(
                    mcc = id?.mccString ?: "", mnc = id?.mncString ?: "",
                    lac = valid(id?.tac ?: Int.MAX_VALUE), cid = (id?.nci ?: -1L).toString(),
                    radio = "5G", dbm = info.cellSignalStrength.dbm,
                    operator = id?.operatorAlphaLong?.toString() ?: "",
                    lat = lat, lon = lon, at = now
                )
            }
            else -> CellSnapshot(radio = "Unknown", lat = lat, lon = lon, at = now)
        }
    }

    /** SIM operator as (MCC, MNC); empty strings when unknown. */
    fun simOperator(tm: TelephonyManager?): Pair<String, String> {
        val op = try { tm?.simOperator ?: "" } catch (e: Exception) { "" }
        if (op.length < 5) return Pair("", "")
        return Pair(op.substring(0, 3), op.substring(3))
    }
}
