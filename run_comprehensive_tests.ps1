# PolyCare - Comprehensive Automated Test Suite
# Date: 2026-09-30 07:16 UTC (12:46 IST)
# Device: Xiaomi 2406ERN9CI

Write-Output "=== PolyCare Comprehensive Test Suite ==="
Write-Output "Starting automated testing of all features..."
Write-Output ""

# Test 1: Embedder
Write-Output "[1/10] Testing Embedder..."
adb shell "am force-stop org.polycare.app"
Start-Sleep -Seconds 2
adb logcat -c
adb shell "am start -n org.polycare.app/.MainActivity --ez embed_check true" | Out-Null
Start-Sleep -Seconds 8
$embed = adb logcat -d -s PolyCareEmbed:* | Select-String "embed_check"
if ($embed -match "PASS") {
    Write-Output "✅ Embedder: PASS"
} else {
    Write-Output "❌ Embedder: FAIL"
}

# Test 2: Voice Recording
Write-Output "[2/10] Testing Voice Recording..."
adb shell "am force-stop org.polycare.app"
Start-Sleep -Seconds 2
adb logcat -c
adb shell "am start -n org.polycare.app/.MainActivity --ez voice_check true" | Out-Null
Start-Sleep -Seconds 8
$voice = adb logcat -d -s PolyCareEvent:* | Select-String "Voice capture"
if ($voice -match "looksLikeAudio") {
    Write-Output "✅ Voice Recording: PASS"
} else {
    Write-Output "❌ Voice Recording: FAIL"
}

# Test 3: LLM Inference
Write-Output "[3/10] Testing LLM..."
adb shell "am force-stop org.polycare.app"
Start-Sleep -Seconds 2
adb logcat -c
adb shell "am start -n org.polycare.app/.MainActivity --ez llm_check true" | Out-Null
Start-Sleep -Seconds 15
$llm = adb logcat -d -s PolyCareLlm:* | Select-String "llm_check"
if ($llm -match "tokPerSec") {
    Write-Output "✅ LLM Inference: PASS"
} else {
    Write-Output "❌ LLM Inference: FAIL"
}

# Test 4: Knowledge Search
Write-Output "[4/10] Testing Knowledge Search..."
adb shell "am force-stop org.polycare.app"
Start-Sleep -Seconds 2
adb logcat -c
adb shell "am start -n org.polycare.app/.MainActivity --es search_query 'ORS preparation'" | Out-Null
Start-Sleep -Seconds 8
$search = adb logcat -d -s PolyCareEvent:* | Select-String "SEARCH"
if ($search) {
    Write-Output "✅ Knowledge Search: PASS"
} else {
    Write-Output "❌ Knowledge Search: FAIL"
}

# Test 5: Household Management
Write-Output "[5/10] Testing Household Management..."
adb shell "am force-stop org.polycare.app"
Start-Sleep -Seconds 2
adb logcat -c
adb shell "am start -n org.polycare.app/.MainActivity --ez household_check true" | Out-Null
Start-Sleep -Seconds 5
$household = adb logcat -d -s PolyCareEvent:* | Select-String "withConsentAllowed"
if ($household -match "true") {
    Write-Output "✅ Household Management: PASS"
} else {
    Write-Output "❌ Household Management: FAIL"
}

# Test 6: Due List
Write-Output "[6/10] Testing Due List..."
adb shell "am force-stop org.polycare.app"
Start-Sleep -Seconds 2
adb logcat -c
adb shell "am start -n org.polycare.app/.MainActivity --ez due_list_check true" | Out-Null
Start-Sleep -Seconds 5
$duelist = adb logcat -d -s PolyCareEvent:* | Select-String "visitRecorded"
if ($duelist) {
    Write-Output "✅ Due List: PASS"
} else {
    Write-Output "❌ Due List: FAIL"
}

# Test 7: Vector Search Performance (10K)
Write-Output "[7/10] Testing Vector Search (10K points)..."
adb shell "am force-stop org.polycare.app"
Start-Sleep -Seconds 2
adb logcat -c
adb shell "am start -n org.polycare.app/.MainActivity --ei bench_points 10000" | Out-Null
Start-Sleep -Seconds 20
$bench = adb logcat -d -s PolyCareBench:* | Select-String "points=10000"
if ($bench -match "p50") {
    Write-Output "✅ Vector Search 10K: PASS"
} else {
    Write-Output "❌ Vector Search 10K: FAIL"
}

# Test 8: Triage
Write-Output "[8/10] Testing Triage Screen..."
adb shell "am force-stop org.polycare.app"
Start-Sleep -Seconds 2
adb logcat -c
adb shell "am start -n org.polycare.app/.MainActivity --ez open_triage true" | Out-Null
Start-Sleep -Seconds 5
$triage = adb logcat -d -s PolyCareEvent:* | Select-String "APP.*started"
if ($triage) {
    Write-Output "✅ Triage Screen: PASS (opened without crash)"
} else {
    Write-Output "❌ Triage Screen: FAIL"
}

# Test 9: Device Monitoring
Write-Output "[9/10] Testing Device Monitoring..."
$battery = adb shell "dumpsys battery | grep level"
$thermal = adb shell "cat /sys/class/thermal/thermal_zone0/temp 2>nul"
$memory = adb shell "dumpsys meminfo org.polycare.app | grep 'TOTAL PSS'"
if ($battery -and $memory) {
    Write-Output "✅ Device Monitoring: PASS"
    Write-Output "   Battery: $battery"
    Write-Output "   Thermal: $thermal"
} else {
    Write-Output "❌ Device Monitoring: FAIL"
}

# Test 10: Op-Log
Write-Output "[10/10] Testing Op-Log..."
adb shell "am force-stop org.polycare.app"
Start-Sleep -Seconds 2
adb logcat -c
adb shell "am start -n org.polycare.app/.MainActivity" | Out-Null
Start-Sleep -Seconds 5
$oplog = adb logcat -d -s PolyCareEvent:* | Select-String "Op-log opened"
if ($oplog -match "ops") {
    Write-Output "✅ Op-Log: PASS"
} else {
    Write-Output "❌ Op-Log: FAIL"
}

Write-Output ""
Write-Output "=== Test Suite Complete ==="
