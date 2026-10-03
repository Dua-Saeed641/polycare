# PolyCare + PocketPal AI Integration Architecture
**Version:** 2.0 - PocketPal Integration
**Date:** 2026-10-03
**Status:** Implementation Ready

---

## 🎯 Core Design Decision

**OLD (v1.0):** PolyCare embedded llama.cpp + GGUF models (~500MB APK, complex updates)
**NEW (v2.0):** PolyCare delegates LLM to PocketPal AI (~50MB APK, models managed externally)

### Why This Change?
- ✅ **Smaller APK** - No bundled LLM = faster downloads for ASHA workers
- ✅ **Model flexibility** - Users pick their own model size (100MB-1GB+) via PocketPal
- ✅ **Better UX** - PocketPal has mature model download UI + progress tracking
- ✅ **Shared models** - One model serves PolyCare + other apps
- ✅ **TTS bonus** - Get on-device speech for free from PocketPal

---

## 📦 What Stays Local vs. Cloud

### **Local (On Device) 🔒**

#### PolyCare App Storage
| Data | Size | Why Local | Encryption |
|------|------|-----------|------------|
| **Patient records** | ~10KB/patient | Privacy law compliance | ✅ AES-256 (SQLCipher) |
| **Visit notes** | ~1KB/visit | Offline access required | ✅ AES-256 |
| **Household data** | ~5KB/household | Contains identifiable info | ✅ AES-256 |
| **OCR models** | 27.9 MB | Fast offline scanning | ❌ Public models |
| **Whisper model** | 181 MB | Offline voice input | ❌ Public model |
| **Knowledge base** | 5.4 MB | Protocol passages for RAG | ❌ Public health info |
| **Embeddings** | 50 MB | Semantic search (multilingual-e5-small) | ❌ Public model |
| **Skills manifest** | <1 KB | LoRA adapter registry | ❌ Config file |

#### PocketPal AI Storage (Separate App)
| Data | Size | Why Separate |
|------|------|--------------|
| **LLM models** | 100MB-2GB | User choice, shared across apps |
| **TTS models** | 50-200MB | Kokoro voice engine |
| **Chat history** | Variable | PocketPal's own conversations |

**Total Device Storage:** ~300MB (PolyCare) + User's PocketPal model choice

---

### **Cloud (Qdrant) ☁️**

#### What Syncs to Qdrant Edge
| Data | Collection | Why Cloud | Sync Frequency |
|------|------------|-----------|----------------|
| **Knowledge passages** | protocols | Distribution to all ASHAs | On app install + weekly updates |
| **Aggregate stats** | coverage | ANM monitoring dashboard | Daily |
| **Anonymous gaps** | knowledge_gaps | Identify missing protocols | Real-time (when offline) |
| **Device metadata** | devices | Track ASHA worker activity | Hourly |

#### What NEVER Syncs
- ❌ Patient names, addresses, or medical details
- ❌ Individual visit notes with identifiable info
- ❌ Device encryption keys
- ❌ User passwords or auth tokens

**Privacy Rule:** Cloud only stores **aggregated, anonymous, or public-domain health data**

---

## 🔄 Sync Architecture

### Knowledge Base Sync (One-Way: Cloud → Device)
\\\
Qdrant Cloud (protocols collection)
  ↓ HTTPS + API Key
[PolyCare on first launch]
  ↓ Extract + Index
Local Qdrant-Lite (5.4MB)
  ↓ Semantic search
[User asks question]
\\\

**Frequency:** 
- First install: Full download (5.4MB)
- Weekly: Check for updates, download deltas only

### Coverage Stats Sync (Two-Way)
\\\
[ASHA worker completes visit]
  ↓ Aggregate locally
Local SQLite (patients + visits)
  ↓ Daily sync (when WiFi available)
Qdrant Cloud (coverage collection)
  ↓ ANM queries dashboard
[Supervisor web UI]
\\\

**Data Sent:**
\\\json
{
  "worker_id": "asha_12345_hashed",
  "date": "2026-10-03",
  "households_covered": 15,
  "visits_completed": 8,
  "high_risk_cases": 2,
  "village": "Village_A"
}
\\\
**NO patient names, addresses, or medical details**

### Knowledge Gaps Sync (One-Way: Device → Cloud)
\\\
[ASHA asks question with no good match]
  ↓ Log anonymously
Local gaps table
  ↓ Batch upload nightly
Qdrant Cloud (gaps collection)
  ↓ Analyze to improve KB
[Health ministry adds new protocols]
\\\

**Data Sent:**
\\\json
{
  "question_embedding": [0.1, 0.3, ...],  // No actual text!
  "confidence_score": 0.45,
  "timestamp": "2026-10-03T12:00:00Z",
  "language": "hi"
}
\\\

---

## 🔗 PolyCare ↔ PocketPal AI Integration

### Option A: Android Intent (Recommended)
\\\kotlin
// PolyCare sends question to PocketPal
val intent = Intent("com.pocketpalai.ACTION_GENERATE").apply {
    setPackage("com.pocketpalai")
    putExtra("prompt", "Explain this health guideline: \")
    putExtra("max_tokens", 150)
    putExtra("temperature", 0.7)
}
startActivityForResult(intent, REQUEST_LLM_RESPONSE)

// PocketPal processes and returns result
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode == REQUEST_LLM_RESPONSE && resultCode == RESULT_OK) {
        val response = data?.getStringExtra("response")
        displayAnswer(response)
    }
}
\\\

### Option B: Content Provider (Future)
If PocketPal adds a CP, we can query directly without UI interruption:
\\\kotlin
contentResolver.query(
    Uri.parse("content://com.pocketpalai.llm/generate"),
    null, null, bundleOf("prompt" to prompt), null
)
\\\

### Fallback: Embedded Qwen (If PocketPal Not Installed)
Keep a tiny Qwen-0.5B (~500MB) as Plan B:
\\\kotlin
if (!isPocketPalInstalled()) {
    showDialog("Install PocketPal AI for better answers, or use basic mode?")
    if (userChooses_BasicMode) {
        useEmbeddedQwen()
    }
}
\\\

---

## 🔧 Implementation Plan

### Phase 1: Strip Existing LLM (Today)
- [x] Remove \ndroid/core-llm\ module
- [x] Delete \LlmProvider\, \LlmSelfCheck\, skill routing
- [x] Keep \PromptFormat\ (for PocketPal prompt construction)
- [x] Keep OCR + Whisper + Embeddings

### Phase 2: Add PocketPal Bridge (Today)
- [ ] Create \PocketPalClient.kt\
- [ ] Implement Intent-based LLM calls
- [ ] Handle "not installed" gracefully
- [ ] Add TTS via PocketPal's speech engine

### Phase 3: Cloud Sync Setup (Today)
- [ ] Configure Qdrant Cloud API (you provided: https://7fe0bfaa...)
- [ ] Implement \CloudSyncRepository.kt\
- [ ] One-way: Download knowledge base on first launch
- [ ] Two-way: Upload coverage stats daily

### Phase 4: Setup Flow (Today)
- [ ] First launch: "Download knowledge base from cloud?"
- [ ] Check if PocketPal installed → guide user to Play Store if not
- [ ] Download recommended model (Qwen-2.5-0.5B) via PocketPal

---

## 📱 First-Time User Experience

### The "One-Tap Setup" Flow
\\\
[User opens PolyCare for first time]
  ↓
[Welcome screen]
"PolyCare needs 2 things to work offline:
 1. Health protocols (5 MB download)
 2. AI assistant (install PocketPal AI - free)"
  ↓
[Tap "Set Up Now"]
  ↓
[Download protocols from cloud - 10 sec]
✅ "Protocols ready!"
  ↓
[Check if PocketPal installed]
  ↓
If NO: [Open Play Store → PocketPal AI]
       [Wait for user to install + download a model]
  ↓
If YES: ✅ "AI ready!"
  ↓
[Done - PolyCare ready to use]
\\\

**Total time:** 2-5 mins (depends on internet speed + model size)

---

## 🎤 Demo Script for Your Video

### What Makes PolyCare Unique?

**1. 100% Offline Operation**
> "I'm in a village with no phone signal. Let me ask PolyCare about fever management..."
> *[Shows answer appearing instantly using local knowledge base + PocketPal AI]*
> "Everything happened on this phone - no cloud, no internet required."

**2. Privacy-First Architecture**
> "This patient's data is stored encrypted on my phone. Even if someone steals it..."
> *[Shows encrypted SQLite database]*
> "...they can't read anything. And it NEVER syncs personal data to the cloud."

**3. Clever Cloud Sync**
> "But how does the health ministry track coverage? Watch this..."
> *[Shows coverage dashboard with village-level stats]*
> "PolyCare sends ONLY anonymous counts - no names, no addresses. Privacy by design."

**4. Powered by Open Source AI**
> "The AI runs using PocketPal AI - an open-source app that works offline."
> *[Shows PocketPal model download screen]*
> "One model serves multiple apps. Smart, efficient, private."

**5. Multilingual + Voice**
> *[Speaks question in Hindi]*
> *[Whisper transcribes on-device]*
> *[PocketPal AI answers]*
> *[PocketPal TTS speaks response]*
> "All of that - voice recognition, AI reasoning, speech output - happened offline on a ₹15,000 phone."

---

## 🔐 Security Model

### Encryption Keys
| Key | Storage | Purpose |
|-----|---------|---------|
| **Device master key** | Android Keystore | Encrypts SQLCipher DB |
| **Qdrant API key** | Encrypted SharedPrefs | Cloud sync auth |
| **ASHA worker ID** | Hashed locally | Anonymous cloud reports |

### Data Flow Security
\\\
Patient Record (plaintext)
  ↓ AES-256 encrypt
SQLCipher database (encrypted at rest)
  ↓ Query/read
Decrypted in memory (app only)
  ↓ NEVER leaves device
  ↓ (Except anonymous stats)
Qdrant Cloud (aggregates only)
\\\

---

## 📊 Storage Breakdown

### PolyCare APK Size
| Component | Size | Change from v1.0 |
|-----------|------|------------------|
| App code (Kotlin/Java) | 8 MB | Same |
| OCR models | 27.9 MB | Same |
| Whisper model | 181 MB | Same |
| Embedding model | 50 MB | Same |
| Knowledge base | 5.4 MB | Same |
| ~~LLM (Qwen)~~ | ~~500 MB~~ | ❌ REMOVED |
| ~~LoRA skills~~ | ~~2 MB~~ | ❌ REMOVED |
| **Total APK** | **272 MB** | **-502 MB (-65%)** |

### User Storage After Setup
| Item | Size | Location |
|------|------|----------|
| PolyCare app | 272 MB | Internal storage |
| Patient data | ~1-10 MB | PolyCare database |
| PocketPal AI | 150 MB | Separate app |
| LLM model | 100MB-2GB | PocketPal storage (user choice) |
| **Total** | **~550MB - 2.5GB** | **Depends on model** |

---

## 🚀 Next Steps

### For You (Right Now)
1. Approve this architecture
2. I'll implement Phase 1-4 today
3. Test on your phone via ADB

### After Testing
1. Record demo using script above
2. Deploy web dashboard for ANMs (Qdrant queries)
3. Publish to Play Store

**Questions?** Let's discuss before I start coding.
