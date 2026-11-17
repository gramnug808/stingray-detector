package art.phoenix.stingray

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stingray Detector — passive, defensive IMSI-catcher detector.
 * Reads only this phone's own serving cell. No transmitting, no
 * intercepting, no jamming, no IMSI capture. Monitoring runs only
 * while the app is open (no background service in v1.0).
 *
 * Loud alerts: a Medium/High anomaly shows a big red banner, vibrates,
 * and posts a persistent notification. The alert screen gives plain-
 * language "What to do now" steps. The app NEVER changes a phone
 * setting by itself — buttons only open the relevant Settings screen,
 * and only when the user taps them.
 */
class MainActivity : Activity(), LocationListener {

    private lateinit var db: StingrayDb
    private var tm: TelephonyManager? = null
    private var lm: LocationManager? = null
    private var monitoring = false
    private var learning = false
    private var prev: CellSnapshot? = null
    private var prevLat = 0.0
    private var prevLon = 0.0
    private var prevNeighbors: Int? = null
    private var lastStatus = "SAFE"
    private var lastScanAt = 0L
    private var lastError: String? = null
    private var lastCellCount = 0
    private var lastAlert: Alert? = null
    private var lastAlertCell: CellSnapshot? = null

    private val handler = Handler(Looper.getMainLooper())
    private val dateFmt = SimpleDateFormat("HH:mm:ss dd MMM", Locale.US)
    private val scanRunnable = object : Runnable {
        override fun run() {
            if (monitoring) {
                scanOnce()
                handler.postDelayed(this, 5000)
            }
        }
    }

    // UI refs
    private lateinit var statusBig: TextView
    private lateinit var statusSub: TextView
    private lateinit var cellText: TextView
    private lateinit var alertBanner: TextView
    private lateinit var alertsText: TextView
    private lateinit var alertDetailText: TextView
    private lateinit var historyText: TextView
    private lateinit var learnText: TextView
    private lateinit var testText: TextView
    private lateinit var mapDetailText: TextView
    private lateinit var monitorBtn: Button
    private lateinit var learnBtn: Button
    private var mapView: TileMapView? = null
    private val screens = mutableMapOf<String, LinearLayout>()

    private val REQ = 1001
    private val PERMS = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    private val NOTIF_CHANNEL = "stingray_alerts"
    private val NOTIF_ID = 9001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = StingrayDb(this)
        tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        createNotifChannel()
        copyBundledTiles()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (name in listOf("Status", "Alerts", "Map", "History", "Learn", "Test")) {
            val b = Button(this).apply {
                text = name
                textSize = 12f
                setOnClickListener { showScreen(name) }
            }
            nav.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(nav)

        screens["Status"] = buildStatusScreen()
        screens["Alerts"] = buildTextScreen("Alerts — heuristic only, NOT proof of a stingray", "alerts")
        screens["Map"] = buildMapScreen()
        screens["History"] = buildTextScreen("History — latest cell sightings (local only)", "history")
        screens["Learn"] = buildLearnScreen()
        screens["Test"] = buildTextScreen("Test — diagnostics (expected results in brackets)", "test")
        for ((_, v) in screens) root.addView(v)
        setContentView(root)
        showScreen("Status")
        ensurePermissions()
        refreshAll()
    }

    // ---------- screens ----------

    private fun tv(text: String, size: Float = 16f, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun buildStatusScreen(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusBig = tv("SAFE", 44f, true).apply { gravity = Gravity.CENTER }
        statusSub = tv("Not monitoring yet — tap Start.", 16f).apply { gravity = Gravity.CENTER }
        alertBanner = tv("", 18f, true).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.RED)
            setTextColor(Color.WHITE)
            setPadding(16, 24, 16, 24)
            visibility = TextView.GONE
            setOnClickListener { showScreen("Alerts") }
        }
        cellText = tv("No cell read yet.", 16f)
        monitorBtn = Button(this).apply {
            text = "▶ Start monitoring"
            setOnClickListener { toggleMonitoring() }
        }
        val warn = tv(
            "⚠ Alerts are heuristics, NOT proof of a stingray. " +
                "A real tower can trigger an alert (handover, coverage gap, new area). " +
                "This app only reads your own phone's serving cell — it never transmits, " +
                "intercepts calls/SMS, or captures IMSI.",
            14f
        )
        val exportBtn = Button(this).apply {
            text = "Export CSV (sightings + alerts)"
            setOnClickListener { exportCsv() }
        }
        col.addView(statusBig); col.addView(statusSub); col.addView(alertBanner)
        col.addView(cellText); col.addView(monitorBtn); col.addView(warn); col.addView(exportBtn)
        val sv = ScrollView(this).apply { addView(col) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(sv, LinearLayout.LayoutParams(-1, -1))
        }
    }

    private fun buildTextScreen(title: String, kind: String): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(title, 18f, true))
        val body = tv("", 15f)
        when (kind) {
            "alerts" -> {
                alertsText = body
                alertDetailText = tv("", 15f)
                val detailTitle = tv("Latest alert — What to do now", 17f, true)
                val airplaneBtn = Button(this).apply {
                    text = "Open Airplane mode settings"
                    setOnClickListener { openSettings("android.settings.AIRPLANE_MODE_SETTINGS", "Airplane mode settings") }
                }
                val twoGBtn = Button(this).apply {
                    text = "Open Mobile network settings (turn off 2G)"
                    setOnClickListener { openSettings("android.settings.MOBILE_NETWORK_SETTINGS", "Mobile network settings") }
                }
                val mapBtn = Button(this).apply {
                    text = "Show this alert on the map"
                    setOnClickListener { showScreen("Map") }
                }
                val dismissBtn = Button(this).apply {
                    text = "Dismiss banner"
                    setOnClickListener {
                        alertBanner.visibility = TextView.GONE
                        clearNotification()
                    }
                }
                val sv = ScrollView(this).apply { addView(body) }
                col.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
                col.addView(detailTitle); col.addView(alertDetailText)
                col.addView(airplaneBtn); col.addView(twoGBtn); col.addView(mapBtn); col.addView(dismissBtn)
            }
            "history" -> historyText = body
            "test" -> testText = body
        }
        if (kind != "alerts") {
            val sv = ScrollView(this).apply { addView(body) }
            col.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val refresh = Button(this).apply {
            text = "Refresh"
            setOnClickListener { refreshAll() }
        }
        col.addView(refresh)
        return col
    }

    private fun buildMapScreen(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv("Alert map — offline, bundled Honolulu tiles", 18f, true))
        col.addView(tv("Red = High risk, orange = Medium, yellow = Low. Tap a pin for full details.", 14f))
        val mv = TileMapView(this).apply {
            centreOn(21.3099, -157.858, 15.0)
            onMarkerTap = { m -> showPinDetail(m.id) }
        }
        mapView = mv
        col.addView(mv, LinearLayout.LayoutParams(-1, 0, 1f))
        val zoomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val zin = Button(this).apply { text = "+"; setOnClickListener { mv.zoomIn() } }
        val zout = Button(this).apply { text = "−"; setOnClickListener { mv.zoomOut() } }
        val centreBtn = Button(this).apply {
            text = "◎ Latest alert"
            setOnClickListener {
                val pins = db.alertPins()
                if (pins.isNotEmpty()) mv.centreOn(pins[0].lat, pins[0].lon, 16.0)
                else toast("No alerts yet — nothing to centre on")
            }
        }
        zoomRow.addView(zin, LinearLayout.LayoutParams(0, -2, 1f))
        zoomRow.addView(zout, LinearLayout.LayoutParams(0, -2, 1f))
        zoomRow.addView(centreBtn, LinearLayout.LayoutParams(0, -2, 2f))
        col.addView(zoomRow)
        mapDetailText = tv("Tap a pin to see its full details here.", 15f)
        col.addView(mapDetailText)
        return col
    }

    private fun buildLearnScreen(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv("Learn my area", 18f, true))
        col.addView(tv(
            "Spend a few days at home / work with Learn ON. " +
                "Every tower your phone connects to there is recorded as known-good. " +
                "After that, a tower you have never seen while barely moving will raise an alert. " +
                "Learning does NOT whitelist a tower forever — the other rules (2G fallback, " +
                "operator mismatch, same ID far away) still fire.",
            15f
        ))
        learnText = tv("", 15f)
        learnBtn = Button(this).apply {
            text = "▶ Start learning"
            setOnClickListener { toggleLearning() }
        }
        val clearBtn = Button(this).apply {
            text = "Clear learned baseline"
            setOnClickListener { db.clearBaseline(); refreshAll(); toast("Baseline cleared") }
        }
        col.addView(learnText); col.addView(learnBtn); col.addView(clearBtn)
        return col
    }

    private fun showScreen(name: String) {
        for ((k, v) in screens) v.visibility = if (k == name) LinearLayout.VISIBLE else LinearLayout.GONE
        if (name == "Map") refreshMap()
        if (name == "Alerts" || name == "History" || name == "Test" || name == "Learn") refreshAll()
    }

    /** Opens a system Settings screen. The app changes NOTHING itself —
     *  the user taps the switch in Settings. */
    private fun openSettings(action: String, label: String) {
        try {
            val i = Intent(action)
            if (i.resolveActivity(packageManager) != null) startActivity(i)
            else toast("$label is not available on this phone")
        } catch (e: Exception) {
            toast("Could not open $label: ${e.message}")
        }
    }

    // ---------- monitoring ----------

    private fun toggleMonitoring() {
        monitoring = !monitoring
        monitorBtn.text = if (monitoring) "■ Stop monitoring" else "▶ Start monitoring"
        if (monitoring) {
            ensurePermissions()
            startLocation()
            handler.post(scanRunnable)
            statusSub.text = "Monitoring — scanning every 5 s while this app is open."
        } else {
            handler.removeCallbacks(scanRunnable)
            statusSub.text = "Monitoring stopped."
        }
    }

    private fun toggleLearning() {
        learning = !learning
        learnBtn.text = if (learning) "■ Stop learning" else "▶ Start learning"
        if (learning && !monitoring) toggleMonitoring()
        refreshAll()
    }

    private fun scanOnce() {
        val loc = lastLocation()
        val lat = loc?.latitude ?: prevLat
        val lon = loc?.longitude ?: prevLon
        val now = System.currentTimeMillis()
        val res = CellReader.read(tm, lat, lon, now)
        lastScanAt = now
        lastCellCount = res.allCount
        lastError = res.error
        val cur = res.snapshot
        if (cur == null) {
            cellText.text = "No cell info — ${res.error ?: "unknown"}\nSee Test screen."
            refreshAll()
            return
        }
        if (loc != null) { prevLat = lat; prevLon = lon }
        val movedSincePrev = if (prev == null) 0.0
            else distanceMeters(prev!!.lat, prev!!.lon, lat, lon)
        val (simMcc, simMnc) = CellReader.simOperator(tm)
        val alerts = Detector.analyze(
            prev, cur, simMcc, simMnc, db.baselineKeys(),
            db.seenCellLocations(), movedSincePrev, prevNeighbors
        )
        db.addSighting(cur)
        for (a in alerts) db.addAlert(now, cur, a)
        if (learning) db.learnCell(cur)
        lastStatus = Detector.status(alerts)
        val loud = alerts.filter { it.risk == Risk.MEDIUM || it.risk == Risk.HIGH }
        if (loud.isNotEmpty()) {
            lastAlert = loud.maxByOrNull { if (it.risk == Risk.HIGH) 2 else 1 }
            lastAlertCell = cur
            fireLoudAlert(loud)
        }
        prev = cur
        prevNeighbors = cur.neighborCount
        cellText.text = cellSummary(cur, res.allCount)
        statusBig.text = lastStatus
        statusBig.setTextColor(
            when (lastStatus) {
                "ALERT" -> Color.RED
                "WATCH" -> Color.parseColor("#FF8800")
                else -> Color.parseColor("#00AA00")
            }
        )
        statusSub.text = when (lastStatus) {
            "ALERT" -> "High-risk anomaly — see Alerts. Still NOT proof of a stingray."
            "WATCH" -> "Something unusual — see Alerts for the reason."
            else -> "No anomaly on this scan. Last scan ${dateFmt.format(Date(now))}."
        }
        refreshAll()
    }

    /** Loud in-app alert: big red banner + vibrate + persistent notification. */
    private fun fireLoudAlert(alerts: List<Alert>) {
        val worst = alerts.maxByOrNull { if (it.risk == Risk.HIGH) 2 else 1 } ?: return
        val riskWord = Detector.riskLabel(worst.risk)
        alertBanner.text = "⚠ $riskWord RISK — ${worst.reason}\nTap for what to do now."
        alertBanner.visibility = TextView.VISIBLE
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= 26) {
                vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1))
            } else {
                @Suppress("DEPRECATION")
                vib?.vibrate(1000)
            }
        } catch (e: Exception) { /* vibrator unavailable — banner + notification still fire */ }
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notif = Notification.Builder(this, NOTIF_CHANNEL)
                .setContentTitle("Stingray Detector — $riskWord risk")
                .setContentText(worst.reason)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setOngoing(true)
                .build()
            nm.notify(NOTIF_ID, notif)
        } catch (e: Exception) { /* notification unavailable — banner still shows */ }
    }

    private fun clearNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_ID)
        } catch (e: Exception) { }
    }

    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(
                    NotificationChannel(NOTIF_CHANNEL, "Stingray alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Persistent alert when a Medium/High cell anomaly is detected"
                    }
                )
            } catch (e: Exception) { }
        }
    }

    private fun cellSummary(c: CellSnapshot, allCount: Int): String =
        "Operator: ${c.operator.ifEmpty { "—" }} (${c.mcc}/${c.mnc})\n" +
            "Radio: ${c.radio}   Signal: ${if (c.dbm == -999) "—" else "${c.dbm} dBm"}\n" +
            "LAC/TAC: ${if (c.lac < 0) "—" else c.lac}   CID: ${c.cid}\n" +
            "Cell key: ${c.cellKey()}\n" +
            "Neighbours: ${c.neighborCount} (cells seen: $allCount)\n" +
            "GPS: ${if (c.lat == 0.0) "—" else "%.5f, %.5f".format(c.lat, c.lon)}\n" +
            "Learned towers: ${db.baselineCount()}   Alerts total: ${db.alertCount()}"

    // ---------- bundled offline tiles ----------

    /** Copy the APK's bundled Honolulu tiles (assets/tiles, z12-15) into
     *  the tile cache on first launch, so the map works with data off. */
    private fun copyBundledTiles() {
        Thread {
            try {
                val destRoot = File(getExternalFilesDir(null), "osmdroid/tiles")
                val marker = File(destRoot, ".bundled-v1")
                if (marker.exists()) return@Thread
                copyAssetTiles("tiles", destRoot)
                destRoot.mkdirs()
                marker.createNewFile()
            } catch (e: Exception) { /* map still works as grid + pins without tiles */ }
        }.start()
    }

    private fun copyAssetTiles(assetPath: String, destDir: File) {
        val names = try { assets.list(assetPath) ?: emptyArray() } catch (e: Exception) { return }
        for (name in names) {
            val childAsset = if (assetPath.isEmpty()) name else "$assetPath/$name"
            val childDest = File(destDir, name)
            val sub = try { assets.list(childAsset) } catch (e: Exception) { null }
            if (sub != null && sub.isNotEmpty()) {
                copyAssetTiles(childAsset, childDest)
            } else if (name.endsWith(".png")) {
                childDest.parentFile?.mkdirs()
                if (!childDest.exists()) {
                    assets.open(childAsset).use { inp ->
                        FileOutputStream(childDest).use { out -> inp.copyTo(out) }
                    }
                }
            }
        }
    }

    // ---------- location ----------

    private var lastLoc: Location? = null

    @Suppress("MissingPermission")
    private fun startLocation() {
        if (!hasLocationPerm()) return
        try {
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 10f, this)
            lm?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 10f, this)
        } catch (e: Exception) { /* provider may be off */ }
    }

    private fun lastLocation(): Location? {
        if (!hasLocationPerm()) return lastLoc
        try {
            val gps = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val net = lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val best = if (gps != null && net != null) {
                if (gps.time >= net.time) gps else net
            } else gps ?: net
            if (best != null) lastLoc = best
        } catch (e: Exception) { }
        return lastLoc
    }

    override fun onLocationChanged(location: Location) { lastLoc = location }
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    // ---------- permissions ----------

    private fun hasLocationPerm(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensurePermissions() {
        val missing = PERMS.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ)
    }

    // ---------- refresh / map / export / diagnostics ----------

    private fun refreshAll() {
        if (!::alertsText.isInitialized) return
        val alerts = db.listAlerts(50)
        alertsText.text = if (alerts.isEmpty()) "No alerts yet."
        else alerts.joinToString("\n\n") {
            "${dateFmt.format(Date(it.at))} — ${it.risk}\n${it.reason}\n${it.detail}\n" +
                "Cell: ${it.cellKey} (${it.radio}, ${it.dbm} dBm) @ ${"%.5f".format(it.lat)}, ${"%.5f".format(it.lon)}\n" +
                "Canary: ${it.canaryId.ifEmpty { "—" }}"
        }
        val a = lastAlert
        val c = lastAlertCell
        alertDetailText.text = if (a == null || c == null) {
            "No Medium/High alert yet. When one fires, its full details and " +
                "what to do appear here, plus a big red banner, vibration, and a " +
                "persistent notification."
        } else {
            val pin = AlertPinData(
                lastScanAt, c.lat, c.lon, c.cellKey(), c.mcc, c.mnc, c.lac, c.cid,
                c.radio, c.dbm, a.risk, a.reason, a.detail, a.canaryId
            )
            MapPins.detail(pin) + "\n\nWHAT TO DO NOW:\n" +
                WhatToDo.steps(listOf(a), c.radio).joinToString("\n")
        }
        val hist = db.listSightings(50)
        historyText.text = if (hist.isEmpty()) "No sightings yet — start monitoring."
        else hist.joinToString("\n") {
            "${dateFmt.format(Date(it.at))}  ${it.cell.radio} ${it.cell.cid}  ${it.cell.dbm} dBm  " +
                "nbrs=${it.cell.neighborCount}  ${"%.4f".format(it.cell.lat)},${"%.4f".format(it.cell.lon)}"
        }
        learnText.text = "Learned towers: ${db.baselineCount()}   Learning: ${if (learning) "ON" else "OFF"}\n" +
            "Sightings: ${db.sightingCount()}   Alerts: ${db.alertCount()}"
        testText.text = diagnostics()
    }

    private fun refreshMap() {
        val mv = mapView ?: return
        val pins = db.alertPins()
        mv.markers = pins.map { pin ->
            MapMarker(pin.lat, pin.lon, MapPins.kind(pin), MapPins.title(pin), MapPins.detail(pin), pin.at)
        }
        mv.myLat = if (prevLat != 0.0) prevLat else null
        mv.myLon = if (prevLon != 0.0) prevLon else null
        if (pins.isNotEmpty()) mv.centreOn(pins[0].lat, pins[0].lon)
    }

    private fun showPinDetail(pinAt: Long) {
        val pin = db.alertPins().firstOrNull { it.at == pinAt } ?: return
        mapDetailText.text = MapPins.detail(pin)
    }

    private fun diagnostics(): String {
        val sb = StringBuilder()
        fun line(ok: Boolean, label: String, expect: String) {
            sb.append(if (ok) "✓ " else "✗ ").append(label).append("  [expected: ").append(expect).append("]\n")
        }
        val phonePerm = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        line(phonePerm, "READ_PHONE_STATE permission", "granted after you allow it")
        line(hasLocationPerm(), "Location permission", "granted after you allow it")
        line(tm != null, "TelephonyManager present", "yes on a phone with a SIM")
        val (simMcc, simMnc) = CellReader.simOperator(tm)
        line(simMcc.isNotEmpty(), "SIM operator: ${if (simMcc.isEmpty()) "unknown" else "$simMcc/$simMnc"}", "your carrier MCC/MNC, e.g. 310/260")
        val gpsOn = lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        line(gpsOn, "GPS provider enabled", "on for accurate stationary checks")
        line(lastScanAt > 0, "Cell scan done: ${if (lastScanAt == 0L) "not yet" else dateFmt.format(Date(lastScanAt))}", "within 5 s of Start")
        line(lastError == null, "Last scan error: ${lastError ?: "none"}", "none — if CellInfo is empty, your phone/carrier may not report cells")
        sb.append("Cells in last scan: $lastCellCount  [expected: 1+; 0 means this phone/carrier hides CellInfo]\n")
        sb.append("Status now: $lastStatus   Sightings: ${db.sightingCount()}   Alerts: ${db.alertCount()}   Learned: ${db.baselineCount()}\n")
        sb.append("Map pins: ${db.alertPins().size}   Bundled tiles: 132 Honolulu (z12-15), offline only\n")
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        line(vib?.hasVibrator() == true, "Vibrator present", "yes, for the loud alert")
        sb.append("\nIf CellInfo is empty on your phone: detection cannot work — Android limits " +
            "CellInfo on some devices/carriers. That is a phone limit, not an app bug.")
        return sb.toString()
    }

    private fun exportCsv() {
        try {
            val sightings = db.exportSightingsCsv()
            val alerts = db.exportAlertsCsv()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            saveToDownloads("stingray-sightings-$stamp.csv", sightings)
            saveToDownloads("stingray-alerts-$stamp.csv", alerts)
            toast("CSV exported to Downloads: stingray-sightings-$stamp.csv + stingray-alerts-$stamp.csv (alerts include canary_id)")
        } catch (e: Exception) {
            toast("Export failed: ${e.message}")
        }
    }

    private fun saveToDownloads(name: String, content: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw RuntimeException("Could not create $name")
        contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(scanRunnable)
        if (monitoring) handler.postDelayed(scanRunnable, 5000)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(scanRunnable)
        try { lm?.removeUpdates(this) } catch (e: Exception) { }
    }
}
