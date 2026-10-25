package com.rjkyker.shizukuai.broker.sensing

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.rjkyker.shizukuai.broker.shell.ShellExecutor
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Honest Wi-Fi RSSI / link-variance presence-and-motion detector.
 *
 * WHAT THIS IS: it samples the phone's own Wi-Fi link (RSSI, link speed, and — when the OS exposes
 * them via `dumpsys wifi` — noise floor and frame delivery/retry counters) at a high rate, keeps a
 * sliding window, and computes variance plus a small discrete Fourier transform. Large, structured
 * fluctuation in the link usually means something near the device moved (a person walking past,
 * a hand near the antenna), so it emits a motion/presence score with a confidence.
 *
 * WHAT THIS IS NOT: this CANNOT see through walls and CANNOT identify, count, or locate people.
 * Android does not expose Wi-Fi Channel State Information (CSI) to normal apps, so real RF
 * tomography / "through-wall" imaging is impossible from an unprivileged (or even Shizuku-level)
 * app. Anything claiming otherwise on a stock phone would be fabricated. This is a coarse,
 * single-link occupancy/motion heuristic for the area immediately around the device only.
 */
object WifiPresenceEngine {

    private const val TAG = "WifiPresence"
    private const val SAMPLE_INTERVAL_MS = 200L
    private const val MIN_DURATION_MS = 1_000L
    private const val MAX_DURATION_MS = 30_000L
    private const val MAX_SAMPLES = 256

    /** One sample of the local link. */
    private data class Sample(
        val tMs: Long,
        val rssi: Int,
        val linkSpeed: Int,
        val freq: Int,
        val noise: Int?,     // dBm, when dumpsys exposes it
        val txGood: Long?,   // cumulative successful tx frames
        val rxGood: Long?,
        val txBad: Long?     // cumulative tx failures
    )

    /**
     * Sample for [durationMs] (clamped 1000..30000) and return a presence/motion assessment.
     * Must be called off the main thread (it sleeps between samples). Honest about limits: the
     * response always carries `cannot_see_through_walls=true` and a `disclaimer`.
     */
    fun scan(context: Context, durationMs: Long): JSONObject {
        val dur = durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return disclaim(err("WifiManager unavailable"))

        if (!wifi.isWifiEnabled) {
            return disclaim(
                err("Wi-Fi is disabled; no link to sample")
                    .put("hint", "Enable Wi-Fi (and stay associated to an AP) for presence sensing.")
            )
        }

        val samples = ArrayList<Sample>()
        val deadline = System.currentTimeMillis() + dur
        var dumpsysAvailable = false
        while (System.currentTimeMillis() < deadline && samples.size < MAX_SAMPLES) {
            val s = sampleOnce(wifi)
            if (s != null) {
                if (s.noise != null || s.txGood != null) dumpsysAvailable = true
                samples.add(s)
            }
            try { Thread.sleep(SAMPLE_INTERVAL_MS) } catch (_: InterruptedException) { break }
        }

        if (samples.size < 4) {
            return disclaim(
                err("not enough samples (${samples.size}); need an active Wi-Fi association")
                    .put("samples", samples.size)
            )
        }

        val rssi = samples.map { it.rssi.toDouble() }.filter { it != 0.0 && it > -127 }
        if (rssi.size < 4) {
            return disclaim(err("no valid RSSI readings; not associated to an AP").put("samples", samples.size))
        }

        val mean = rssi.average()
        val variance = rssi.map { (it - mean) * (it - mean) }.average()
        val stdDev = sqrt(variance)

        // Frame-delivery jitter (if dumpsys exposed counters): deltas between consecutive samples.
        val txBadDelta = deltaSum(samples.mapNotNull { it.txBad })
        val goodDelta = deltaSum(samples.mapNotNull { it.txGood } + samples.mapNotNull { it.rxGood })

        // Detrend RSSI (remove slow mean drift) then run a small DFT to find the dominant
        // fluctuation frequency. Human motion/breathing shows up as low-Hz structured variation;
        // flat background multipath is near-zero.
        val detrended = rssi.map { it - mean }
        val sampleRateHz = 1000.0 / SAMPLE_INTERVAL_MS
        val spectrum = dft(detrended)
        // bin 0 is DC (already removed by detrending); find the strongest non-DC bin.
        var peakBin = 0
        var peakMag = 0.0
        for (k in 1 until spectrum.size) {
            if (spectrum[k] > peakMag) { peakMag = spectrum[k]; peakBin = k }
        }
        val dominantHz = peakBin * sampleRateHz / detrended.size
        val totalPower = spectrum.drop(1).sum().coerceAtLeast(1e-9)
        val peakRatio = peakMag / totalPower // how concentrated the fluctuation is

        // Motion score: normalize std-dev (typical still link ~0.5-1.5 dB; motion often >3 dB),
        // weighted up when the fluctuation is spectrally structured and frames are being dropped.
        val rssiComponent = (stdDev / 6.0).coerceIn(0.0, 1.0)
        val structureComponent = peakRatio.coerceIn(0.0, 1.0)
        val frameComponent = if (goodDelta > 0) (txBadDelta.toDouble() / (goodDelta + 1)).coerceIn(0.0, 1.0) else 0.0
        val motionScore = (0.6 * rssiComponent + 0.3 * structureComponent + 0.1 * frameComponent)
            .coerceIn(0.0, 1.0)

        val presence = motionScore > 0.35
        // Confidence grows with sample count and with how much signal we actually captured.
        val sampleConf = (samples.size / 40.0).coerceIn(0.0, 1.0)
        val dataConf = if (dumpsysAvailable) 1.0 else 0.7 // less data without dumpsys counters
        val confidence = (sampleConf * dataConf).coerceIn(0.0, 1.0)

        val result = JSONObject()
            .put("ok", true)
            .put("samples", samples.size)
            .put("duration_ms", dur)
            .put("rssi_mean_dbm", round2(mean))
            .put("rssi_stddev_db", round2(stdDev))
            .put("rssi_variance", round2(variance))
            .put("dominant_motion_hz", round2(dominantHz))
            .put("spectral_peak_ratio", round2(peakRatio))
            .put("frame_fail_delta", txBadDelta)
            .put("frame_good_delta", goodDelta)
            .put("noise_floor_available", dumpsysAvailable)
            .put("motion_score", round2(motionScore))
            .put("presence_detected", presence)
            .put("confidence", round2(confidence))
            .put("link", JSONObject()
                .put("freq_mhz", samples.last().freq)
                .put("link_speed_mbps", samples.last().linkSpeed)
                .put("noise_dbm", samples.last().noise ?: JSONObject.NULL))
        return disclaim(result)
    }

    private fun sampleOnce(wifi: WifiManager): Sample? {
        return try {
            @Suppress("DEPRECATION")
            val info = wifi.connectionInfo ?: return null
            val dump = readWifiDump()
            Sample(
                tMs = System.currentTimeMillis(),
                rssi = info.rssi,
                linkSpeed = info.linkSpeed,
                freq = runCatching { info.frequency }.getOrDefault(0),
                noise = dump?.noise,
                txGood = dump?.txGood,
                rxGood = dump?.rxGood,
                txBad = dump?.txBad
            )
        } catch (e: Exception) {
            Log.d(TAG, "sample failed: ${e.message}")
            null
        }
    }

    private data class WifiDump(val noise: Int?, val txGood: Long?, val rxGood: Long?, val txBad: Long?)

    /**
     * Best-effort parse of `dumpsys wifi` for a noise floor and frame counters. Soft-fails (returns
     * null) when the shell path or the fields are unavailable — RSSI-only mode still works.
     */
    private fun readWifiDump(): WifiDump? {
        return try {
            val r = ShellExecutor.execSync(
                "dumpsys wifi 2>/dev/null | grep -iE 'noise|mLastTxGood|mLastRxGood|txBad|tx_bad|rx_good|mTxBad' | head -20",
                3L
            )
            if (r.exitCode != 0 || r.stdout.isBlank()) return null
            val text = r.stdout
            WifiDump(
                noise = Regex("noise[^-0-9]*(-?\\d{2,3})", RegexOption.IGNORE_CASE)
                    .find(text)?.groupValues?.get(1)?.toIntOrNull(),
                txGood = longAfter(text, "txgood", "mlasttxgood", "tx_good"),
                rxGood = longAfter(text, "rxgood", "mlastrxgood", "rx_good"),
                txBad = longAfter(text, "txbad", "mtxbad", "tx_bad")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun longAfter(text: String, vararg keys: String): Long? {
        for (k in keys) {
            val m = Regex("$k[^0-9]*(\\d+)", RegexOption.IGNORE_CASE).find(text)
            if (m != null) return m.groupValues[1].toLongOrNull()
        }
        return null
    }

    private fun deltaSum(series: List<Long>): Long {
        if (series.size < 2) return 0
        var sum = 0L
        for (i in 1 until series.size) {
            val d = series[i] - series[i - 1]
            if (d > 0) sum += d
        }
        return sum
    }

    /** Naive real-input DFT magnitude spectrum (small N, fine for <=256 samples). */
    private fun dft(x: List<Double>): DoubleArray {
        val n = x.size
        val mags = DoubleArray(n / 2 + 1)
        for (k in mags.indices) {
            var re = 0.0
            var im = 0.0
            for (t in 0 until n) {
                val ang = 2.0 * PI * k * t / n
                re += x[t] * cos(ang)
                im -= x[t] * sin(ang)
            }
            mags[k] = sqrt(re * re + im * im)
        }
        return mags
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    private fun disclaim(o: JSONObject): JSONObject = o
        .put("cannot_see_through_walls", true)
        .put("cannot_identify_people", true)
        .put(
            "disclaimer",
            "RSSI/link-variance occupancy heuristic for the area near this device only. " +
                "Not tomography; cannot see through walls or identify people — Android does not " +
                "expose Wi-Fi CSI to apps."
        )

    private fun err(msg: String): JSONObject = JSONObject().put("ok", false).put("error", msg)
}
