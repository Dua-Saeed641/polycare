package org.polycare.app

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import org.polycare.app.ui.PolyCareRoot
import org.polycare.app.ui.theme.PolyCareTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PolyCareTheme {
                PolyCareRoot(autoBenchPoints = debugBenchRequest())
            }
        }
    }

    /**
     * Debug builds only: `adb shell am start -n org.polycare.app/.MainActivity --ei bench_points 10000`
     * opens System and runs the vector benchmark; results go to logcat tag PolyCareBench.
     */
    private fun debugBenchRequest(): Int? {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return intent.getIntExtra(EXTRA_BENCH_POINTS, 0).takeIf { debuggable && it > 0 }
    }

    private companion object {
        const val EXTRA_BENCH_POINTS = "bench_points"
    }
}
