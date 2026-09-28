package org.polycare.app.ai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.polycare.common.InMemoryEventLog
import kotlin.time.Duration.Companion.minutes

/**
 * The embedder on the phone's ARM CPU vs the Python reference. Needs the models on the phone:
 * `bash tools/models/push_models.sh`.
 */
@RunWith(AndroidJUnit4::class)
class E5OnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun matchesPythonReferenceOnArm() = runTest(timeout = 5.minutes) {
        val provider = EmbedderProvider(instrumentation.targetContext, InMemoryEventLog())
        val ready = provider.get()
        assertNotNull("models missing: run tools/models/push_models.sh (${provider.state.value})", ready)
        val fixture = instrumentation.context.assets.open("e5_reference.json").bufferedReader().readText()
        val result = EmbedSelfCheck.run(ready!!.embedder, fixture)
        Log.i("PolyCareEmbed", "$result")
        assertTrue("token mismatches: ${result.tokenMismatches}", result.tokenMismatches.isEmpty())
        assertTrue("min cosine ${result.minCosine} for: ${result.worstText}", result.minCosine > EmbedSelfCheck.MIN_COSINE)
        assertTrue("nearest neighbour changed for ${result.cases - result.neighbourAgreement} cases", result.neighbourAgreement == result.cases)
    }
}
