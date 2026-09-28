package org.polycare.app.device

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.polycare.vector.VectorBenchmark
import org.polycare.vector.edge.EdgeStoreOptions
import org.polycare.vector.edge.QdrantEdgeVectorStore
import java.io.File
import javax.inject.Inject

sealed interface BenchState {
    data object Idle : BenchState
    data class Running(val points: Int, val step: String) : BenchState
    data class Done(val result: VectorBenchmark.Result, val quantization: String) : BenchState
    data class Failed(val message: String) : BenchState
}

/** Runs [VectorBenchmark] against a real Qdrant Edge shard in app storage, then deletes it. */
@HiltViewModel
class VectorBenchViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow<BenchState>(BenchState.Idle)
    val state: StateFlow<BenchState> = _state.asStateFlow()

    fun run(points: Int, options: EdgeStoreOptions = EdgeStoreOptions()) {
        if (_state.value is BenchState.Running) return
        _state.value = BenchState.Running(points, "Opening shard")
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.Default) {
                val dir = File(context.noBackupFilesDir, "bench/${points}_${System.nanoTime()}")
                try {
                    QdrantEdgeVectorStore.open(dir, modelId = "bench-random-384", dim = 384, options = options).use { store ->
                        val result = VectorBenchmark().run(store, points) { step ->
                            _state.value = BenchState.Running(points, step)
                        }
                        Log.i(TAG, "points=${result.points} quant=${options.quantization} load=${result.loadMs}ms " +
                            "optimize=${result.optimizeMs}ms p50=${"%.2f".format(result.p50Ms)}ms " +
                            "p95=${"%.2f".format(result.p95Ms)}ms recall@${result.k}=${"%.2f".format(result.recallAtK)} " +
                            "disk=${result.diskBytes}")
                        BenchState.Done(result, options.quantization.label)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "benchmark failed", e)
                    BenchState.Failed(e.message ?: e.javaClass.simpleName)
                } finally {
                    logDiskBreakdown(dir)
                    dir.deleteRecursively()
                }
            }
        }
    }

    /** Largest files in the shard, to see where disk space goes. */
    private fun logDiskBreakdown(dir: File) {
        dir.walkBottomUp().filter { it.isFile }
            .map { it to android.system.Os.stat(it.absolutePath).st_blocks * 512 }
            .sortedByDescending { it.second }.take(8)
            .forEach { (f, used) -> Log.i(TAG, "disk ${used / 1024} KB used (${f.length() / 1024} KB size) ${f.relativeTo(dir)}") }
    }

    companion object {
        const val TAG = "PolyCareBench"
    }
}
