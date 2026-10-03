# PolyCare - Comprehensive Mobile Device Testing Script
# Tests all features on connected Android device via ADB

Write-Host "=== PolyCare Mobile Device Test Suite ===" -ForegroundColor Cyan
Write-Host ""

# Find ADB
$adbPath = "C:\Users\ZBook\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adbPath)) {
    $adbPath = (Get-Command adb -ErrorAction SilentlyContinue).Source
}

if (-not $adbPath) {
    Write-Host "❌ ADB not found. Install Android SDK Platform Tools" -ForegroundColor Red
    exit 1
}

function Run-ADB {
    param([string]$cmd)
    & $adbPath $cmd.Split(' ')
}

Write-Host "Step 1: Checking connected devices..." -ForegroundColor Yellow
$devices = Run-ADB "devices"
Write-Host $devices

if ($devices -notmatch "device$") {
    Write-Host "❌ No device connected. Connect device and enable USB debugging" -ForegroundColor Red
    exit 1
}

Write-Host "✅ Device connected" -ForegroundColor Green
Write-Host ""

Write-Host "Step 2: Getting device info..." -ForegroundColor Yellow
$manufacturer = Run-ADB "shell getprop ro.product.manufacturer"
$model = Run-ADB "shell getprop ro.product.model"
$android = Run-ADB "shell getprop ro.build.version.release"
$sdk = Run-ADB "shell getprop ro.build.version.sdk"

Write-Host "Device: $manufacturer $model" -ForegroundColor Cyan
Write-Host "Android: $android (SDK $sdk)" -ForegroundColor Cyan
Write-Host ""

Write-Host "Step 3: Checking PolyCare installation..." -ForegroundColor Yellow
$package = Run-ADB "shell pm list packages org.polycare.app"
if ($package -match "org.polycare.app") {
    Write-Host "✅ PolyCare is installed" -ForegroundColor Green
    
    $version = Run-ADB "shell dumpsys package org.polycare.app | findstr versionName"
    Write-Host "Version: $version" -ForegroundColor Cyan
} else {
    Write-Host "❌ PolyCare not installed" -ForegroundColor Red
    Write-Host "Installing from android/app/build/outputs/apk/debug/app-debug.apk..." -ForegroundColor Yellow
    
    $apkPath = "android\app\build\outputs\apk\debug\app-debug.apk"
    if (Test-Path $apkPath) {
        Run-ADB "install -r $apkPath"
        Write-Host "✅ Installed" -ForegroundColor Green
    } else {
        Write-Host "❌ APK not found. Run: cd android && ./gradlew.bat assembleDebug" -ForegroundColor Red
        exit 1
    }
}
Write-Host ""

Write-Host "Step 4: Testing network connectivity..." -ForegroundColor Yellow
$wifi = Run-ADB "shell dumpsys wifi | findstr 'mNetworkInfo'"
if ($wifi -match "CONNECTED") {
    Write-Host "✅ WiFi connected" -ForegroundColor Green
} else {
    Write-Host "⚠️  WiFi not connected - sync tests may fail" -ForegroundColor Yellow
}
Write-Host ""

Write-Host "Step 5: Checking app permissions..." -ForegroundColor Yellow
$perms = Run-ADB "shell dumpsys package org.polycare.app | findstr permission"
Write-Host $perms
Write-Host ""

Write-Host "Step 6: Getting app storage usage..." -ForegroundColor Yellow
$storage = Run-ADB "shell du -sh /data/data/org.polycare.app 2>&1"
Write-Host "App storage: $storage" -ForegroundColor Cyan
Write-Host ""

Write-Host "Step 7: Checking for crashes (last 50 lines)..." -ForegroundColor Yellow
$crashes = Run-ADB "logcat -d -s AndroidRuntime:E *:S | Select-Object -Last 50"
if ($crashes) {
    Write-Host "⚠️  Crash logs found:" -ForegroundColor Yellow
    Write-Host $crashes
} else {
    Write-Host "✅ No recent crashes" -ForegroundColor Green
}
Write-Host ""

Write-Host "Step 8: Launching PolyCare..." -ForegroundColor Yellow
Run-ADB "shell am start -n org.polycare.app/.MainActivity"
Start-Sleep -Seconds 2
Write-Host "✅ App launched" -ForegroundColor Green
Write-Host ""

Write-Host "Step 9: Performance benchmarks..." -ForegroundColor Yellow
Write-Host "Run these tests manually in the app:" -ForegroundColor Cyan
Write-Host "  1. Go to Ask screen → Enter 'What is pregnancy danger sign?'"
Write-Host "  2. Go to Sync screen → Tap 'Sync Now'"
Write-Host "  3. Go to Households → Create a test household"
Write-Host "  4. Go to Settings → Change language to Hindi"
Write-Host ""

Write-Host "Step 10: Collecting app logs..." -ForegroundColor Yellow
$logFile = "polycare_device_logs_$(Get-Date -Format 'yyyyMMdd_HHmmss').txt"
Run-ADB "logcat -d org.polycare.app:V *:S" | Out-File -FilePath $logFile -Encoding UTF8
Write-Host "✅ Logs saved to $logFile" -ForegroundColor Green
Write-Host ""

Write-Host "=== Test Suite Complete ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "Next Steps:" -ForegroundColor Yellow
Write-Host "1. Manually test all features in the app"
Write-Host "2. Check sync functionality with gateway"
Write-Host "3. Verify Hindi UI strings display correctly"
Write-Host "4. Test network quality detection"
Write-Host "5. Monitor metrics submission to dashboard"
Write-Host ""
Write-Host "Dashboard URL: http://localhost:8000/dashboard (when gateway running)" -ForegroundColor Cyan
