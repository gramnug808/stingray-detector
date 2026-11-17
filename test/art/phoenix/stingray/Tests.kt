package art.phoenix.stingray

import kotlin.math.abs

/** JVM unit tests for Stingray Detector pure logic. Run via build.sh. */
object StingrayTests {
    private var passed = 0
    private var failed = 0

    private fun check(name: String, cond: Boolean) {
        if (cond) { passed++; println("PASS $name") }
        else { failed++; println("FAIL $name") }
    }

    private fun cell(
        radio: String = "4G", cid: String = "1001", lac: Int = 200,
        dbm: Int = -80, nbrs: Int = 3, mcc: String = "310", mnc: String = "260",
        lat: Double = 21.3099, lon: Double = -157.858, at: Long = 1000L
    ) = CellSnapshot(mcc, mnc, lac, cid, radio, dbm, nbrs, "TestCarrier", lat, lon, at)

    private fun analyze(
        prev: CellSnapshot?, cur: CellSnapshot,
        simMcc: String = "310", simMnc: String = "260",
        baseline: Set<String> = emptySet(),
        seen: Map<String, Pair<Double, Double>> = emptyMap(),
        moved: Double = 0.0, prevNbrs: Int? = null
    ) = Detector.analyze(prev, cur, simMcc, simMnc, baseline, seen, moved, prevNbrs)

    @JvmStatic
    fun main(args: Array<String>) {
        // --- helpers ---
        check("cell key format", cell().cellKey() == "310-260-200-1001")
        check("cell has identity", cell().hasIdentity())
        check("empty cell no identity", !CellSnapshot().hasIdentity())
        check("honolulu distance ~0", distanceMeters(21.3099, -157.858, 21.3099, -157.858) < 1.0)
        val dKm = distanceMeters(21.3099, -157.858, 21.35, -157.858) / 1000.0
        check("honolulu 0.04 deg lat ~4.5 km", dKm > 4.0 && dKm < 5.0)
        check("status safe empty", Detector.status(emptyList()) == "SAFE")
        check("status watch medium", Detector.status(listOf(Alert("x", Risk.MEDIUM, "y"))) == "WATCH")
        check("status alert high wins", Detector.status(listOf(Alert("x", Risk.MEDIUM, "y"), Alert("z", Risk.HIGH, "w"))) == "ALERT")
        check("risk labels", Detector.riskLabel(Risk.LOW) == "Low" && Detector.riskLabel(Risk.HIGH) == "High")

        // --- Rule 1: 2G fallback ---
        val a2gStrong = analyze(cell(radio = "4G"), cell(radio = "2G", dbm = -70, cid = "1001"))
        check("2G fallback strong = HIGH", a2gStrong.any { it.reason.contains("2G fallback") && it.risk == Risk.HIGH })
        val a2gWeak = analyze(cell(radio = "5G"), cell(radio = "2G", dbm = -105, cid = "1001"))
        check("2G fallback weak = MEDIUM", a2gWeak.any { it.reason.contains("2G fallback") && it.risk == Risk.MEDIUM })
        check("4G->4G no 2G alert", analyze(cell(radio = "4G"), cell(radio = "4G")).none { it.reason.contains("2G") })
        check("2G alone, no prev = no 2G alert (legit 2G area)", analyze(null, cell(radio = "2G")).none { it.reason.contains("2G") })
        check("2G->2G staying = no fallback alert", analyze(cell(radio = "2G"), cell(radio = "2G", cid = "1002")).none { it.reason.contains("2G fallback") })

        // --- Rule 2: new CID vs baseline ---
        val base = setOf(cell().cellKey())
        check("known baseline cell = no new-tower alert", analyze(cell(), cell(), baseline = base).none { it.reason.contains("New tower") })
        val aNewStill = analyze(cell(), cell(cid = "9999"), baseline = base, moved = 10.0)
        check("new CID stationary = MEDIUM", aNewStill.any { it.reason.contains("New tower never seen") && it.risk == Risk.MEDIUM })
        val aNewMoving = analyze(cell(), cell(cid = "9999"), baseline = base, moved = 500.0)
        check("new CID moving = LOW only", aNewMoving.any { it.risk == Risk.LOW } && aNewMoving.none { it.risk == Risk.MEDIUM && it.reason.contains("New tower never") })
        check("empty baseline = no new-tower alert", analyze(cell(), cell(cid = "9999"), baseline = emptySet()).none { it.reason.contains("New tower") })

        // --- Rule 3: operator mismatch ---
        val aMismatch = analyze(null, cell(mcc = "310", mnc = "410"))
        check("MNC mismatch = HIGH", aMismatch.any { it.reason.contains("Operator mismatch") && it.risk == Risk.HIGH })
        check("MCC mismatch = HIGH", analyze(null, cell(mcc = "311")).any { it.reason.contains("Operator mismatch") })
        check("operator match = no alert", analyze(null, cell()).none { it.reason.contains("Operator mismatch") })
        check("unknown SIM = mismatch skipped", analyze(null, cell(mcc = "999"), simMcc = "", simMnc = "").none { it.reason.contains("Operator mismatch") })

        // --- Rule 4: CID change while stationary vs moving ---
        check("CID change stationary = MEDIUM", analyze(cell(), cell(cid = "1002"), moved = 5.0).any { it.reason.contains("barely moved") && it.risk == Risk.MEDIUM })
        check("CID change while driving = no stationary alert", analyze(cell(), cell(cid = "1002"), moved = 800.0).none { it.reason.contains("barely moved") })
        check("same CID stationary = no alert", analyze(cell(), cell(), moved = 0.0).none { it.reason.contains("barely moved") })

        // --- Rule 5: LAC/TAC change without moving ---
        check("LAC change stationary = MEDIUM", analyze(cell(), cell(lac = 201), moved = 5.0).any { it.reason.contains("LAC/TAC") })
        check("LAC change while moving = no alert", analyze(cell(), cell(lac = 201), moved = 800.0).none { it.reason.contains("LAC/TAC") })

        // --- Rule 6: strong signal jump, no neighbours ---
        val aJump = analyze(cell(dbm = -100, nbrs = 3), cell(dbm = -70, nbrs = 0), prevNbrs = 3)
        check("signal jump +30dB no nbrs = HIGH", aJump.any { it.reason.contains("signal jump") && it.risk == Risk.HIGH })
        val aJump20 = analyze(cell(dbm = -95, nbrs = 3), cell(dbm = -75, nbrs = 0), prevNbrs = 3)
        check("signal jump +20dB no nbrs = MEDIUM", aJump20.any { it.reason.contains("signal jump") && it.risk == Risk.MEDIUM })
        check("signal jump WITH neighbours = no alert", analyze(cell(dbm = -100), cell(dbm = -70, nbrs = 2)).none { it.reason.contains("signal jump") })
        check("small signal change = no alert", analyze(cell(dbm = -85), cell(dbm = -80, nbrs = 0)).none { it.reason.contains("signal jump") })

        // --- Rule 7: neighbour list suddenly empty ---
        check("neighbours suddenly empty = MEDIUM", analyze(cell(nbrs = 3), cell(nbrs = 0), prevNbrs = 3).any { it.reason.contains("Neighbour cells suddenly gone") })
        check("neighbours 0 from first scan = no alert", analyze(null, cell(nbrs = 0), prevNbrs = null).none { it.reason.contains("Neighbour cells suddenly gone") })
        check("neighbours stay >0 = no alert", analyze(cell(nbrs = 3), cell(nbrs = 2), prevNbrs = 3).none { it.reason.contains("Neighbour cells suddenly gone") })

        // --- Rule 8: same CID far away ---
        val seenFar = mapOf(cell().cellKey() to Pair(21.3099, -157.858))
        val aFar = analyze(null, cell(lat = 21.50, lon = -157.858), seen = seenFar)
        check("same CID 21 km away = HIGH", aFar.any { it.reason.contains("distant location") && it.risk == Risk.HIGH })
        val aNear = analyze(null, cell(lat = 21.3105, lon = -157.858), seen = seenFar)
        check("same CID 70 m away = no alert", aNear.none { it.reason.contains("distant location") })
        check("different CID far away = no alert", analyze(null, cell(cid = "5555", lat = 21.50), seen = seenFar).none { it.reason.contains("distant location") })

        // --- False positives: normal handover while moving ---
        val normal = cell(cid = "1002", nbrs = 2)
        check("normal handover recognised", Detector.isNormalHandover(cell(), normal, 500.0))
        check("normal handover moving = no stationary alerts", analyze(cell(), normal, moved = 500.0).none { it.reason.contains("barely moved") || it.reason.contains("LAC/TAC") })
        check("handover same area no LAC alert", Detector.isNormalHandover(cell(), cell(cid = "1002", lac = 200), 300.0))
        check("stationary CID change is NOT normal handover", !Detector.isNormalHandover(cell(), normal, 10.0))

        // --- CSV ---
        check("csv header", Csv.sightingsHeader().startsWith("time,mcc,mnc"))
        check("csv row fields", Csv.sightingRow(1000L, cell()).split(",").size == 11)
        check("csv escape comma", Csv.escape("a,b") == "\"a,b\"")
        check("csv escape quote", Csv.escape("a\"b") == "\"a\"\"b\"")
        check("csv plain unchanged", Csv.escape("abc") == "abc")
        check("alert csv header", Csv.alertsHeader().startsWith("time,lat,lon"))

        // --- Canary IDs (local tracking ID per Medium/High alert) ---
        val can1 = Canary.makeId(1700000000000L, "310-260-200-1001")
        val can2 = Canary.makeId(1700000000000L, "310-260-200-1001")
        check("canary format valid", Canary.isValid(can1))
        check("canary starts STINGRAY-", can1.startsWith("STINGRAY-"))
        check("canary contains timestamp", Canary.timestampOf(can1) == 1700000000000L)
        check("canary unique per alert", can1 != can2)
        check("canary fingerprint stable for same cell", Canary.fingerprint("310-260-200-1001") == Canary.fingerprint("310-260-200-1001"))
        check("canary fingerprint differs per cell", Canary.fingerprint("310-260-200-1001") != Canary.fingerprint("310-260-200-9999"))
        check("canary invalid rejected", !Canary.isValid("not-a-canary") && !Canary.isValid("STINGRAY-abc"))
        check("canary fingerprint 8 hex", Canary.fingerprint("310-260-200-1001").length == 8)
        val highWithCanary = analyze(cell(radio = "4G"), cell(radio = "2G", dbm = -70))
        check("HIGH alert carries canary", highWithCanary.any { it.risk == Risk.HIGH && Canary.isValid(it.canaryId) })
        val medWithCanary = analyze(cell(), cell(cid = "1002"), moved = 5.0)
        check("MEDIUM alert carries canary", medWithCanary.any { it.risk == Risk.MEDIUM && Canary.isValid(it.canaryId) })
        val lowNoCanary = analyze(cell(), cell(cid = "9999"), baseline = setOf(cell().cellKey()), moved = 500.0)
        check("LOW alert carries no canary", lowNoCanary.filter { it.risk == Risk.LOW }.all { it.canaryId.isEmpty() })
        check("no alert = no canary", analyze(cell(), cell()).all { it.canaryId.isEmpty() })
        check("canary timestamp matches scan time", highWithCanary.filter { it.risk == Risk.HIGH }.all { Canary.timestampOf(it.canaryId) == 1000L })

        // --- What to do now ---
        val steps2g = WhatToDo.steps(listOf(Alert("Sudden 2G fallback", Risk.HIGH, "x")), "2G")
        check("what-to-do has airplane step", steps2g.any { it.contains("Airplane mode") })
        check("what-to-do has turn-off-2G step for 2G", steps2g.any { it.contains("Turn off 2G") })
        check("what-to-do has wifi-calling step", steps2g.any { it.contains("Wi-Fi calling") })
        check("what-to-do has encrypted-apps step", steps2g.any { it.contains("encrypted apps") })
        check("what-to-do says not proof", steps2g.any { it.contains("not proof") })
        val steps4g = WhatToDo.steps(listOf(Alert("Operator mismatch", Risk.HIGH, "x")), "4G")
        check("what-to-do no 2G step for 4G alert", steps4g.none { it.contains("Turn off 2G") })
        check("what-to-do always has airplane step", steps4g.any { it.contains("Airplane mode") })

        // --- Map pins ---
        val pin = AlertPinData(1700000000000L, 21.3099, -157.858, "310-260-200-1001", "310", "260", 200, "1001", "2G", -70, Risk.HIGH, "Sudden 2G fallback", "Dropped from 4G to 2G", can1)
        check("pin kind high = alert_high", MapPins.kind(pin) == "alert_high")
        check("pin kind medium", MapPins.kind(pin.copy(risk = Risk.MEDIUM)) == "alert_medium")
        check("pin kind low", MapPins.kind(pin.copy(risk = Risk.LOW)) == "alert_low")
        val pinDetail = MapPins.detail(pin)
        check("pin detail has time", pinDetail.contains("1700000000000"))
        check("pin detail has MCC/MNC", pinDetail.contains("310/260"))
        check("pin detail has LAC/CID", pinDetail.contains("200") && pinDetail.contains("1001"))
        check("pin detail has radio + dBm", pinDetail.contains("2G") && pinDetail.contains("-70 dBm"))
        check("pin detail has GPS", pinDetail.contains("21.3099") && pinDetail.contains("-157.858"))
        check("pin detail has risk + reason", pinDetail.contains("High") && pinDetail.contains("Sudden 2G fallback"))
        check("pin detail has canary", pinDetail.contains(can1))
        check("pin title", MapPins.title(pin) == "High — 2G 1001")
        val pinsSorted = MapPins.sorted(listOf(pin.copy(at = 1000L), pin.copy(at = 3000L), pin.copy(at = 2000L)))
        check("pins sorted newest first", pinsSorted[0].at == 3000L && pinsSorted[2].at == 1000L)

        // --- TileMath (offline map) ---
        check("tile honolulu x", TileMath.lonToTileX(-157.858, 15) in 2000..2100)
        check("tile honolulu y", TileMath.latToTileY(21.3099, 15) in 14000..15000)
        check("tile round-trip lon", abs(TileMath.tileXToLon(TileMath.lonToTileX(-157.858, 15), 15) - (-157.858)) < 0.05)
        check("tile round-trip lat", abs(TileMath.tileYToLat(TileMath.latToTileY(21.3099, 15), 15) - 21.3099) < 0.05)
        check("offline zooms 12..15", TileMath.OFFLINE_ZOOMS == 12..15)

        // --- CSV carries canary + cell fields ---
        check("alert csv header has canary_id", Csv.alertsHeader().contains("canary_id"))
        check("alert csv header has radio/dbm", Csv.alertsHeader().contains("radio") && Csv.alertsHeader().contains("dbm"))
        val csvRow = Csv.alertRowFull(1000L, 21.3099, -157.858, "310-260-200-1001", "310", "260", 200, "1001", "2G", -70, Alert("Sudden 2G fallback", Risk.HIGH, "x", can1))
        check("alert csv row has canary", csvRow.contains(can1))
        check("alert csv row has 14 fields", csvRow.split(",").size == 14)

        println("\n$passed passed, $failed failed")
        if (failed > 0) throw RuntimeException("$failed test(s) failed")
    }
}
