$ErrorActionPreference = 'Stop'

# Official PaddlePaddle Hugging Face ONNX exports. Revisions and SHA-256 digests are pinned.
# The downloaded assets ship inside the APK, so both scripts work without a first-use download.
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$models = @(
    @{ name = 'det'; repo = 'PaddlePaddle/PP-OCRv5_mobile_det_onnx'; rev = 'e6f4fa85f00e168c862bc462aebca69eef9b3d3d'; files = @(
        @{ name = 'inference.onnx'; sha = 'a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d' },
        @{ name = 'inference.yml'; sha = '98069072e1b6b37d727fd9d9f11725faa46d6ea0de012f2ed26caea011c37699' }
    ) },
    @{ name = 'latin'; repo = 'PaddlePaddle/PP-OCRv5_mobile_rec_onnx'; rev = 'ed152b8b495f84de93cda5709d768548a9127622'; files = @(
        @{ name = 'inference.onnx'; sha = 'da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092' },
        @{ name = 'inference.yml'; sha = '5dfeb2777f6d0db8177d8128a8acfcf6e6276dc4ac73ea3bf0dc06d6a5e85d8e' }
    ) },
    @{ name = 'devanagari'; repo = 'PaddlePaddle/devanagari_PP-OCRv5_mobile_rec_onnx'; rev = '251aec19e36739540d35e2cc943f6aa7503b98e5'; files = @(
        @{ name = 'inference.onnx'; sha = 'cb789212ce96c69d3e74728ae4309d179281d68cb3945d0616b67cafab41c986' },
        @{ name = 'inference.yml'; sha = '9bd172dd26440c8ce94d1cde5d5baea6aefdc7cf3c5c8492e0beedef656d4e54' }
    ) }
)

foreach ($model in $models) {
    $destination = Join-Path $root "android\app\src\main\assets\models\ocr\$($model.name)"
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    foreach ($file in $model.files) {
        $path = Join-Path $destination $file.name
        $actual = if (Test-Path -LiteralPath $path) { (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLower() } else { '' }
        if ($actual -ne $file.sha) {
            $partial = "$path.part"
            $uri = "https://huggingface.co/$($model.repo)/resolve/$($model.rev)/$($file.name)"
            Invoke-WebRequest -Uri $uri -OutFile $partial
            $downloaded = (Get-FileHash -Algorithm SHA256 -LiteralPath $partial).Hash.ToLower()
            if ($downloaded -ne $file.sha) {
                Remove-Item -LiteralPath $partial -Force
                throw "SHA-256 mismatch for $($model.repo)/$($file.name): $downloaded"
            }
            Move-Item -LiteralPath $partial -Destination $path -Force
        }
        Write-Host "verified $($model.name)/$($file.name)"
    }
}
