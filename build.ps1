<#
.SYNOPSIS
  Build, test, and sign the TecumSMS Notification Service & Driver (TecumSMS-rt) for Niagara 4.

.EXAMPLE
  .\build.ps1
  Compiles the module, runs tests, packages dist\TecumSMS-rt.jar, signs it, and verifies with nverify.

.EXAMPLE
  .\build.ps1 -Test
  Runs only the standalone core unit tests (no Niagara installation required).

.EXAMPLE
  .\build.ps1 -Install
  Builds, signs, and installs dist\TecumSMS-rt.jar into C:\Niagara\Niagara-4.14.0.162\modules.
#>
param(
  [switch]$Test,
  [switch]$SkipTests,
  [switch]$Install,
  [string]$NiagaraHome = "C:\Niagara\Niagara-4.14.0.162",
  [string]$Version = "1.0.0",
  [string]$StorePass = "Tecum2026!",
  [string]$KeyPass = "Tecum!Key26"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Root

# Add Niagara JRE bin (and JDK tools if present) to PATH
$jreBin = Join-Path $NiagaraHome "jre\bin"
if (Test-Path $jreBin) {
  $env:PATH = "$jreBin;$env:PATH"
}

function Run-Tests {
  Write-Host "==> Compiling and running TecumSMS core unit tests..." -ForegroundColor Cyan
  $testOut = Join-Path $Root "build\test"
  if (Test-Path $testOut) { Remove-Item $testOut -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $testOut | Out-Null

  $coreSrc = Get-ChildItem -Path "TecumSMS-rt\src\com\tecum\sms\core" -Filter "*.java" -Recurse | ForEach-Object { $_.FullName }
  $testSrc = Get-ChildItem -Path "TecumSMS-rt\srcTest" -Filter "*.java" -Recurse | ForEach-Object { $_.FullName }

  & javac -encoding UTF-8 -source 1.8 -target 1.8 -d $testOut $coreSrc $testSrc
  if ($LASTEXITCODE -ne 0) { throw "Test compilation failed." }

  & java -cp $testOut com.tecum.sms.core.CoreTests
  if ($LASTEXITCODE -ne 0) { throw "Unit tests failed." }
}

function Ensure-Certificates {
  $certDir = Join-Path $Root "certs"
  New-Item -ItemType Directory -Force -Path $certDir | Out-Null

  $jks = Join-Path $certDir "tecum-signing.jks"
  $p12 = Join-Path $certDir "tecum-signing.p12"
  $rootPem = Join-Path $certDir "TecumRootCA.pem"
  $rootCer = Join-Path $certDir "TecumRootCA.cer"
  $signerPem = Join-Path $certDir "TecumCodeSigner.pem"
  $signerCer = Join-Path $certDir "TecumCodeSigner.cer"
  $chainPem = Join-Path $certDir "TecumChain.pem"

  if ((Test-Path $jks) -and (Test-Path $rootPem) -and (Test-Path $signerPem)) {
    Write-Host "==> Using existing Tecum signing certificates in certs\" -ForegroundColor Cyan
    return
  }

  Write-Host "==> Generating Tecum Root CA and Code Signing certificates..." -ForegroundColor Cyan
  Remove-Item "$certDir\*" -Force -ErrorAction SilentlyContinue
  $csr = Join-Path $certDir "signer.csr"

  # 1. Generate Root CA in JKS
  & keytool -genkeypair -alias tecum-rootca `
    -keystore $jks -storetype JKS -storepass $StorePass -keypass $KeyPass `
    -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -validity 3650 `
    -dname "CN=Tecum Root CA, OU=Building Automation, O=Tecum, L=Dubai, ST=Dubai, C=AE" `
    -ext "bc:c=ca:true,pathlen:1" `
    -ext "ku:c=keyCertSign,cRLSign"
  if ($LASTEXITCODE -ne 0) { throw "Failed to generate Root CA." }

  # 2. Generate Code Signing keypair and CSR in JKS
  & keytool -genkeypair -alias tecum-codesign `
    -keystore $jks -storetype JKS -storepass $StorePass -keypass $KeyPass `
    -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -validity 3650 `
    -dname "CN=Tecum Module Signer, OU=Building Automation, O=Tecum, L=Dubai, ST=Dubai, C=AE"
  if ($LASTEXITCODE -ne 0) { throw "Failed to generate Code Signer keypair." }

  & keytool -certreq -alias tecum-codesign `
    -keystore $jks -storetype JKS -storepass $StorePass -keypass $KeyPass `
    -file $csr
  if ($LASTEXITCODE -ne 0) { throw "Failed to generate CSR." }

  # 3. Sign Code Signing certificate with Root CA (includes critical codeSigning EKU)
  & keytool -gencert -rfc -alias tecum-rootca `
    -keystore $jks -storetype JKS -storepass $StorePass -keypass $KeyPass `
    -infile $csr -outfile $signerPem `
    -sigalg SHA256withRSA -validity 3650 `
    -ext "bc:c=ca:false" `
    -ext "ku:c=digitalSignature" `
    -ext "eku:c=codeSigning"
  if ($LASTEXITCODE -ne 0) { throw "Failed to sign Code Signer certificate." }

  # 4. Export Root CA (PEM & DER) and Code Signer (DER)
  & keytool -exportcert -rfc -alias tecum-rootca -keystore $jks -storetype JKS -storepass $StorePass -file $rootPem
  & keytool -exportcert -alias tecum-rootca -keystore $jks -storetype JKS -storepass $StorePass -file $rootCer

  # 5. Assemble certificate chain and import back into JKS
  Get-Content $signerPem, $rootPem | Set-Content -Encoding ASCII $chainPem
  & keytool -importcert -alias tecum-codesign `
    -keystore $jks -storetype JKS -storepass $StorePass -keypass $KeyPass `
    -file $chainPem -noprompt
  if ($LASTEXITCODE -ne 0) { throw "Failed to import certificate chain into JKS." }

  & keytool -exportcert -alias tecum-codesign -keystore $jks -storetype JKS -storepass $StorePass -file $signerCer

  # 6. Also export a PKCS#12 keystore for tools that prefer .p12
  & keytool -importkeystore `
    -srckeystore $jks -srcstoretype JKS -srcstorepass $StorePass -srckeypass $KeyPass -srcalias tecum-codesign `
    -destkeystore $p12 -deststoretype PKCS12 -deststorepass $StorePass -destkeypass $StorePass -destalias tecum-codesign -noprompt
  if ($LASTEXITCODE -ne 0) { throw "Failed to create PKCS#12 keystore." }

  Remove-Item $csr -Force -ErrorAction SilentlyContinue
}

function Build-Module {
  if (-not (Test-Path $NiagaraHome)) {
    throw "Niagara installation not found at $NiagaraHome. Pass -NiagaraHome <path>."
  }

  Write-Host "==> Compiling TecumSMS-rt against $NiagaraHome..." -ForegroundColor Cyan
  $classesDir = Join-Path $Root "build\classes"
  if (Test-Path $classesDir) { Remove-Item $classesDir -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $classesDir | Out-Null

  $jars = @(
    "$NiagaraHome\modules\baja.jar",
    "$NiagaraHome\modules\bql-rt.jar",
    "$NiagaraHome\modules\driver-rt.jar",
    "$NiagaraHome\modules\alarm-rt.jar",
    "$NiagaraHome\modules\control-rt.jar",
    "$NiagaraHome\bin\ext\nre.jar"
  )
  $cp = $jars -join ";"
  $sources = Get-ChildItem -Path "TecumSMS-rt\src" -Filter "*.java" -Recurse | ForEach-Object { $_.FullName }

  & javac -encoding UTF-8 -source 1.8 -target 1.8 -cp $cp -d $classesDir $sources
  if ($LASTEXITCODE -ne 0) { throw "Module compilation failed." }

  # Stage module resources
  $metaInf = Join-Path $classesDir "META-INF"
  New-Item -ItemType Directory -Force -Path $metaInf | Out-Null

  $buildMillis = [string][DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
  $buildHost = $env:COMPUTERNAME
  $xml = (Get-Content "TecumSMS-rt\module.xml.template" -Raw)
  $xml = $xml.Replace("@VERSION@", $Version).Replace("@BUILD_MILLIS@", $buildMillis).Replace("@BUILD_HOST@", $buildHost)
  [System.IO.File]::WriteAllText((Join-Path $metaInf "module.xml"), $xml, [System.Text.UTF8Encoding]::new($false))

  Copy-Item "TecumSMS-rt\module.palette" (Join-Path $classesDir "module.palette") -Force
  if (Test-Path "TecumSMS-rt\TecumSMS-rt.lexicon") {
    Copy-Item "TecumSMS-rt\TecumSMS-rt.lexicon" (Join-Path $classesDir "TecumSMS-rt.lexicon") -Force
  }

  $distDir = Join-Path $Root "dist"
  New-Item -ItemType Directory -Force -Path $distDir | Out-Null
  Remove-Item "$distDir\ignitechsSms-rt.jar" -Force -ErrorAction SilentlyContinue
  $jarPath = Join-Path $distDir "TecumSMS-rt.jar"
  if (Test-Path $jarPath) { Remove-Item $jarPath -Force }

  $manifestPath = Join-Path $Root "build\MANIFEST.MF"
  $manifest = "Manifest-Version: 1.0`r`nImplementation-Vendor: Tecum`r`nImplementation-Version: $Version`r`nSealed: true`r`n"
  [System.IO.File]::WriteAllText($manifestPath, $manifest, [System.Text.UTF8Encoding]::new($false))

  Write-Host "==> Packaging $jarPath..." -ForegroundColor Cyan
  & jar cfm $jarPath $manifestPath -C $classesDir .
  if ($LASTEXITCODE -ne 0) { throw "Failed to package JAR." }

  Ensure-Certificates

  $jks = Join-Path $Root "certs\tecum-signing.jks"
  $rootPem = Join-Path $Root "certs\TecumRootCA.pem"

  Write-Host "==> Signing $jarPath with Tecum Code Signing certificate..." -ForegroundColor Cyan
  & jarsigner -keystore $jks -storetype JKS -storepass $StorePass -keypass $KeyPass `
    -sigfile TECUM -digestalg SHA-256 -sigalg SHA256withRSA `
    $jarPath tecum-codesign
  if ($LASTEXITCODE -ne 0) { throw "Failed to sign JAR." }

  Write-Host "==> Verifying signature with jarsigner..." -ForegroundColor Cyan
  & jarsigner -verify -certs $jarPath
  if ($LASTEXITCODE -ne 0) { throw "jarsigner verification failed." }

  $nverify = Join-Path $NiagaraHome "bin\nverify.exe"
  if (Test-Path $nverify) {
    Write-Host "==> Verifying Niagara module signature with nverify.exe..." -ForegroundColor Cyan
    & $nverify -v --skip-tpk-check --trusted-certificates="$rootPem" $jarPath
    if ($LASTEXITCODE -ne 0) { throw "nverify module verification failed." }
  }

  $jarHash = (Get-FileHash -Algorithm SHA256 $jarPath).Hash
  Write-Host ""
  Write-Host "Build & Signing Complete!" -ForegroundColor Green
  Write-Host "  Module JAR : $jarPath"
  Write-Host "  SHA-256    : $jarHash"
  Write-Host "  Root CA    : $rootPem"

  if ($Install) {
    $oldDest = Join-Path $NiagaraHome "modules\ignitechsSms-rt.jar"
    Remove-Item $oldDest -Force -ErrorAction SilentlyContinue
    $dest = Join-Path $NiagaraHome "modules\TecumSMS-rt.jar"
    Copy-Item $jarPath $dest -Force
    Write-Host "  Installed  : $dest" -ForegroundColor Green
  }
}

if ($Test) {
  Run-Tests
} else {
  if (-not $SkipTests) {
    Run-Tests
  }
  Build-Module
}
