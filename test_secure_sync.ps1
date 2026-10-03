# PolyCare - Secure Sync Testing Plan
# Date: 2026-09-30 07:46 UTC (13:16 IST)

Write-Output "=== Testing Secure Cloud Sync Implementation ==="
Write-Output ""

# Test 1: Network Monitoring
Write-Output "[1/6] Testing Network Monitoring..."
adb shell "dumpsys connectivity | grep -E 'NetworkAgentInfo|CONNECTED'"

# Test 2: Check current sync state
Write-Output ""
Write-Output "[2/6] Checking Current Sync State..."
adb shell "am start -n org.polycare.app/.MainActivity --es open_route sync"
Start-Sleep -Seconds 5
adb logcat -d -s PolyCareEvent:* | Select-String "SYNC" | Select-Object -Last 10

# Test 3: Verify PII filtering (via op-log)
Write-Output ""
Write-Output "[3/6] Verifying PII Filtering..."
adb logcat -d -s PolyCareEvent:* | Select-String "Op-log" | Select-Object -Last 5

# Test 4: Check encryption status
Write-Output ""
Write-Output "[4/6] Checking Encryption Status..."
adb shell "ls -lh /data/data/org.polycare.app/files/ 2>/dev/null | grep -E 'household|oplog'"

# Test 5: Network quality detection
Write-Output ""
Write-Output "[5/6] Testing Network Quality Detection..."
$netInfo = adb shell "dumpsys connectivity"
if ($netInfo -match "WiFi") {
    Write-Output "✅ WiFi detected"
} elseif ($netInfo -match "MOBILE") {
    Write-Output "⚠️ Cellular detected"
} else {
    Write-Output "❌ No network"
}

# Test 6: Sync Gate validation
Write-Output ""
Write-Output "[6/6] Validating Sync Gate..."
adb logcat -d | Select-String "SyncGate|personal health data|PII" | Select-Object -Last 10

Write-Output ""
Write-Output "=== Secure Sync Test Complete ==="
