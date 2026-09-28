package org.polycare.app

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.polycare.app.ai.EmbedSelfCheck
import org.polycare.app.ai.EmbedderProvider
import org.polycare.app.ui.PolyCareRoot
import org.polycare.app.ui.theme.PolyCareTheme
import org.polycare.common.EventLog
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var embedderProvider: EmbedderProvider
    @Inject lateinit var events: EventLog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (isDebuggable() && intent.getBooleanExtra(EXTRA_EMBED_CHECK, false)) runEmbedCheck()
        setContent {
            PolyCareTheme {
                PolyCareRoot(
                    autoBenchPoints = debugBenchRequest(),
                    debugSearch = debugSearchRequest(),
                    debugAsk = debugAskRequest(),
                    debugTriage = debugTriageRequest(),
                )
            }
        }
    }

    /**
     * Debug builds only: `adb shell am start -n org.polycare.app/.MainActivity --ei bench_points 10000`
     * opens System and runs the vector benchmark; results go to logcat tag PolyCareBench.
     */
    /** Debug builds only: `--es search_query "how to prepare ORS"` opens Search with that query. */
    private fun debugSearchRequest(): String? =
        intent.getStringExtra(EXTRA_SEARCH_QUERY)?.takeIf { isDebuggable() && it.isNotBlank() }

    /** Debug builds only: `--es ask_query "baby has fast breathing"` opens Ask with that question. */
    private fun debugAskRequest(): String? =
        intent.getStringExtra(EXTRA_ASK_QUERY)?.takeIf { isDebuggable() && it.isNotBlank() }

    /** Debug builds only: `--ez open_triage true` opens Triage — for verifying it renders without a tap. */
    private fun debugTriageRequest(): Boolean =
        intent.getBooleanExtra(EXTRA_OPEN_TRIAGE, false) && isDebuggable()

    private fun debugBenchRequest(): Int? =
        intent.getIntExtra(EXTRA_BENCH_POINTS, 0).takeIf { isDebuggable() && it > 0 }

    /**
     * Debug builds only: `adb shell am start -n org.polycare.app/.MainActivity --ez embed_check true`
     * checks the embedder against the Python reference on this phone; results in logcat tag PolyCareEmbed.
     */
    private fun runEmbedCheck() {
        lifecycleScope.launch(Dispatchers.Default) {
            val ready = embedderProvider.get()
            if (ready == null) {
                Log.w(EMBED_TAG, "embed_check: ${embedderProvider.state.value}")
                return@launch
            }
            val fixture = assets.open("e5_reference.json").bufferedReader().use { it.readText() }
            val r = EmbedSelfCheck.run(ready.embedder, fixture)
            Log.i(
                EMBED_TAG,
                "embed_check ${if (r.passed) "PASS" else "FAIL"} load=${ready.loadMs}ms cases=${r.cases} " +
                    "tokenMismatches=${r.tokenMismatches.size} minCos=${"%.6f".format(r.minCosine)} " +
                    "sameNearestNeighbour=${r.neighbourAgreement}/${r.cases} worst=\"${r.worstText}\" " +
                    "query p50=${"%.1f".format(r.queryP50Ms)}ms p95=${"%.1f".format(r.queryP95Ms)}ms",
            )
            r.tokenMismatches.forEach { Log.w(EMBED_TAG, "token mismatch: $it") }
            events.record(
                EventLog.Category.MODEL,
                "Embedder self-check ${if (r.passed) "passed" else "FAILED"}",
                mapOf(
                    "cases" to r.cases, "tokenMismatches" to r.tokenMismatches.size,
                    "minCos" to "%.4f".format(r.minCosine), "sameNeighbour" to "${r.neighbourAgreement}/${r.cases}",
                    "queryP50Ms" to "%.1f".format(r.queryP50Ms),
                ),
                if (r.passed) EventLog.Level.INFO else EventLog.Level.ERROR,
            )
        }
    }

    private fun isDebuggable() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private companion object {
        const val EXTRA_BENCH_POINTS = "bench_points"
        const val EXTRA_EMBED_CHECK = "embed_check"
        const val EXTRA_SEARCH_QUERY = "search_query"
        const val EXTRA_ASK_QUERY = "ask_query"
        const val EXTRA_OPEN_TRIAGE = "open_triage"
        const val EMBED_TAG = "PolyCareEmbed"
    }
}
