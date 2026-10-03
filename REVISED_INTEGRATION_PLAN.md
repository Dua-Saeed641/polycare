# PolyCare - Embedded LLM Integration Plan (Revised)
**Date:** 2026-10-03 08:00 UTC
**Strategy:** Embed llama.rn directly, no separate PocketPal app

---

## 🎯 NEW APPROACH: Direct llama.rn Integration

### What We'll Use from PocketPal AI
PocketPal AI uses these core libraries:
- **llama.rn** v0.13.0-rc.5 → React Native bindings for llama.cpp
- **llama.cpp** b11118 → The actual C++ inference engine (GGUF support)
- **onnxruntime-react-native** 1.23.2 → For TTS (Kokoro)

### But PolyCare is Native Android (Kotlin), Not React Native!

**Solution:** Use the same underlying native libraries PocketPal uses:
1. **llama.cpp** - Direct Android JNI bindings (not React Native wrapper)
2. **GGUF models** - Same format, downloaded from Hugging Face
3. **ONNX Runtime Android** - For TTS

---

## 📦 Technology Stack

### Option 1: llama.cpp Android JNI (Recommended)
**Repo:** https://github.com/ggerganov/llama.cpp/tree/master/examples/llama.android

**Pros:**
- ✅ Official llama.cpp Android example
- ✅ Direct JNI, no React Native overhead
- ✅ Full hardware acceleration (CPU, GPU, NPU)
- ✅ Small binary size (~15MB native lib)
- ✅ GGUF support out of the box

**Implementation:**
\\\kotlin
// Use llama.cpp's Android JNI wrapper
dependencies {
    implementation 'com.github.ggerganov.llama:llama-android:b11118'
}
\\\

### Option 2: llama.rn Native Modules (Complex)
**Repo:** https://github.com/mybigday/llama.rn

**Pros:**
- ✅ What PocketPal uses
- ✅ Proven to work on Android

**Cons:**
- ❌ Requires React Native bridge even though we're pure Kotlin
- ❌ More complex integration
- ❌ Larger binary size

### Option 3: Keep Our Current Setup (core-llm)
**What we already have:**
- ✅ llama.cpp already integrated in core-llm module
- ✅ Working GGUF support
- ✅ LoRA adapter support

**Issues:**
- ⚠️ Large APK size (~500MB with bundled model)
- ⚠️ Model updates require new APK

---

## 🔄 REVISED INTEGRATION PLAN

### Phase 1: Keep Existing LLM, Add Model Download
**Instead of removing core-llm, enhance it:**

1. **Keep** core-llm module (it already uses llama.cpp)
2. **Add** model download from Hugging Face
3. **Add** one-time setup flow
4. **Add** cloud sync for knowledge base

### Phase 2: Model Management
\\\kotlin
// New file: ModelDownloader.kt
class ModelDownloader @Inject constructor(context: Context) {
    
    suspend fun downloadModel(modelId: String, progressCallback: (Float) -> Unit) {
        // Download GGUF from Hugging Face
        val url = "https://huggingface.co/\/resolve/main/model.gguf"
        // Save to: /data/data/org.polycare.app/files/models/\.gguf
    }
    
    fun getInstalledModels(): List<ModelInfo> {
        // List .gguf files in models directory
    }
}
\\\

### Phase 3: First-Launch Setup
\\\kotlin
// SetupScreen.kt
@Composable
fun SetupScreen() {
    var step by remember { mutableStateOf(1) }
    
    when (step) {
        1 -> KnowledgeBaseDownload(onComplete = { step = 2 })
        2 -> ModelSelection(onComplete = { step = 3 })
        3 -> ModelDownload(onComplete = { /* Navigate to app */ })
    }
}
\\\

---

## 🎯 WHAT YOU'LL GET

### Before (Current v0.1.0)
- ✅ Model bundled in APK (~500MB)
- ❌ Cannot change models
- ❌ First download takes forever

### After (v2.0 - Revised Plan)
- ✅ Small APK (~50MB without model)
- ✅ User picks model size (100MB - 2GB)
- ✅ Models download from Hugging Face
- ✅ Can update models without APK update
- ✅ Cloud sync for knowledge base

---

## 📱 USER FLOW (REVISED)

### First Launch
\\\
[User installs PolyCare APK - 50MB]
  ↓
[Setup screen appears]
"Welcome to PolyCare! Let's set up your offline AI assistant."
  ↓
[Step 1: Download health protocols]
"Downloading protocols from cloud... (5.4 MB)"
[Progress bar: 10 seconds]
✅ Done
  ↓
[Step 2: Choose AI model]
"Pick a model size for your phone:
 ⚪ Small (100 MB) - Fast, basic answers
 ⚪ Medium (500 MB) - Balanced (Recommended)
 ⚪ Large (2 GB) - Best quality, slower"
  ↓
[Step 3: Download model]
"Downloading Qwen-2.5-0.5B from Hugging Face..."
[Progress bar: 2-5 minutes depending on connection]
✅ Done
  ↓
[Ready!]
"PolyCare is ready. Everything works offline now."
[Tap "Start" → Home screen]
\\\

---

## 💾 STORAGE COMPARISON

| Component | v1.0 (Current) | v2.0 (Download) |
|-----------|----------------|-----------------|
| APK download | 500 MB | 50 MB |
| First-time setup | Install only | + 505 MB downloads |
| Total storage | 500 MB | 555 MB |
| **User experience** | **1 slow download** | **Fast APK + setup** |

---

## 🚀 IMPLEMENTATION STEPS

### Step 1: UNDO PocketPal Client Removal
\\\powershell
# Restore core-llm module
git checkout android/settings.gradle.kts
git checkout android/app/build.gradle.kts
\\\

### Step 2: Add Model Download
\\\kotlin
// android/app/src/main/kotlin/org/polycare/app/setup/ModelDownloader.kt
class ModelDownloader {
    suspend fun downloadFromHuggingFace(
        modelId: String,  // e.g., "Qwen/Qwen2.5-0.5B-Instruct-GGUF"
        filename: String, // e.g., "qwen2.5-0.5b-instruct-q4_k_m.gguf"
    ): File
}
\\\

### Step 3: Add Setup Flow
\\\kotlin
// android/app/src/main/kotlin/org/polycare/app/setup/SetupScreen.kt
@Composable
fun SetupScreen(onComplete: () -> Unit)
\\\

### Step 4: Cloud Sync for Knowledge Base
\\\kotlin
// android/app/src/main/kotlin/org/polycare/app/sync/CloudSyncRepository.kt
class CloudSyncRepository {
    suspend fun downloadKnowledgeBase(qdrantUrl: String, apiKey: String)
    suspend fun uploadCoverageStats(stats: CoverageReport)
}
\\\

---

## ⏰ TIME ESTIMATE (REVISED)

| Task | Time | Status |
|------|------|--------|
| Fix Gradle locks | 5 min | ⏳ Need restart |
| UNDO PocketPal removal | 2 min | Not started |
| Restore core-llm | 1 min | Not started |
| Add ModelDownloader | 15 min | Not started |
| Add SetupScreen UI | 20 min | Not started |
| Add CloudSync | 15 min | Not started |
| Test on device | 10 min | Not started |
| **Total** | **~70 min** | **Can finish today** |

---

## 🎤 DEMO SCRIPT (UPDATED)

### What Makes PolyCare Unique?

**1. One-Time Setup, Lifetime Offline**
> "First time you open PolyCare, it downloads everything once - health protocols and an AI model."
> *[Shows setup screen with progress bars]*
> "After that? Completely offline. No internet needed, ever."

**2. You Choose the Model Size**
> "Have an older phone with less storage? Pick the 100MB model."
> *[Shows model selection: Small/Medium/Large]*
> "Newer phone? Get the 2GB model for better answers."

**3. Everything Encrypted, Privacy First**
> *[Shows patient record screen]*
> "Patient data is encrypted on this phone using AES-256. Even if stolen, it's unreadable."

**4. Smart Cloud Sync - Anonymous Only**
> *[Shows coverage dashboard]*
> "PolyCare reports how many households were covered, not who they are."
> "Ministry gets insights. Patients keep privacy."

---

## ❓ WHICH APPROACH DO YOU WANT?

### Option A: Keep core-llm + Add Downloads (Recommended)
- ✅ Less work (just add download manager)
- ✅ Use what's already working
- ✅ Same outcome as PocketPal approach
- ⏰ Can finish in ~1 hour

### Option B: Full PocketPal Integration (Original Plan)
- ❌ More complex
- ❌ Requires React Native bridge or full rewrite
- ❌ You said "not installing PocketPal"
- ⏰ Would take 3-4 hours

### Option C: Keep Everything Bundled (Current)
- ✅ Simplest
- ❌ 500MB APK
- ❌ Can't update models
- ⏰ Just fix current bugs (30 min)

**I recommend Option A.** Which do you prefer?
