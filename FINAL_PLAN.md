# PolyCare - Final Implementation Plan
**Time:** 2026-10-03 08:00 UTC
**Decision:** Keep core-llm + Add model download + Cloud sync

---

## ✅ FINAL APPROACH

### Keep What Works
- ✅ core-llm module (llama.cpp already integrated)
- ✅ GGUF model support
- ✅ LoRA adapters
- ✅ Hardware acceleration

### Add What's Missing
- 📥 Model download from Hugging Face (first launch)
- ☁️ Cloud sync for knowledge base (Qdrant)
- ⚙️ One-time setup flow UI
- 🔐 Encrypted local storage (already done)

---

## 🚀 IMPLEMENTATION (Next 60 Minutes)

### 1. Restore core-llm Module ✅
- Re-add to settings.gradle.kts
- Re-add to app/build.gradle.kts

### 2. Revert PocketPal Changes
- Restore original AskViewModel.kt
- Restore original TriageViewModel.kt
- Restore original HomeStatusViewModel.kt
- Remove PocketPalClient.kt

### 3. Add Model Downloader (NEW)
\\\kotlin
class ModelDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val events: EventLog,
) {
    suspend fun downloadModel(
        modelId: String = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
        filename: String = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        onProgress: (Float) -> Unit = {}
    ): Result<File>
}
\\\

### 4. Add Cloud Sync (NEW)
\\\kotlin
class CloudSyncRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun downloadKnowledgeBase(
        qdrantUrl: String = "https://7fe0bfaa-...",
        apiKey: String,
    ): Result<Unit>
    
    suspend fun uploadCoverageStats(stats: CoverageStats): Result<Unit>
}
\\\

### 5. Add Setup Flow UI (NEW)
\\\kotlin
@Composable
fun SetupScreen(onComplete: () -> Unit) {
    // Step 1: Download knowledge base from Qdrant (5.4 MB)
    // Step 2: Download LLM model from HuggingFace (100-500 MB)
    // Step 3: Extract and verify
    // Step 4: Navigate to home
}
\\\

---

## 📱 USER EXPERIENCE

### First Launch (One Time Only)
\\\
[User installs PolyCare - 50 MB APK]
  ↓
[Setup screen]
"Welcome to PolyCare"
"Setting up offline AI assistant..."
  ↓
[Automatic setup - no user input needed]
✓ Downloading health protocols (5 MB) - 10 sec
✓ Downloading AI model (500 MB) - 2-5 min
✓ Configuring encryption - 1 sec
  ↓
✅ "Ready! Everything works offline now."
[Tap Continue → Home]
\\\

### Subsequent Launches
- Instant! Everything cached locally
- No setup screen
- 100% offline

---

## 🎯 IMMEDIATE NEXT STEPS

Since Gradle is locked, I'll:
1. ✅ Restore core-llm references
2. ✅ Revert all PocketPal changes
3. ✅ Create ModelDownloader.kt
4. ✅ Create CloudSyncRepository.kt  
5. ✅ Create SetupScreen.kt
6. ⏳ Wait for you to restart/unlock Gradle
7. ⏳ Build & test

**Do you want me to proceed with steps 1-5 now?**
