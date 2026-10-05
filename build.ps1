param([string]$ProxyPath='C:\Users\artyo\Documents\Codex\nordcommands-paper-test-20261004\proxy')
$ErrorActionPreference='Stop'
$projectPath=Split-Path -Parent $MyInvocation.MyCommand.Path
$resolved=(Resolve-Path -LiteralPath $ProxyPath).Path
if(-not $resolved.StartsWith('C:\Users\artyo\Documents\Codex\',[StringComparison]::OrdinalIgnoreCase)){throw 'LOCAL Velocity library required'}
$velocityJar=Join-Path $resolved 'velocity.jar'
$queueJar=Join-Path (Split-Path -Parent $projectPath) 'NordQueue\releases\1.1.2\NordQueue-1.1.2.jar'
if(-not (Test-Path -LiteralPath $velocityJar) -or -not (Test-Path -LiteralPath $queueJar)){throw 'Current runtime libraries missing'}
if((Get-FileHash -LiteralPath $queueJar -Algorithm SHA256).Hash -ne '8C60BBADAB9B7F1AD02B2D2B59539F518D9BE13B9D4A06E6317F1E4D025D3CB2'){throw 'Unexpected NordQueue API JAR'}
$buildPath=Join-Path $projectPath 'build'
$work=Join-Path $buildPath ('work-'+[Guid]::NewGuid().ToString('N'))
$classes=Join-Path $work 'classes'
$testClasses=Join-Path $work 'test-classes'
New-Item -ItemType Directory -Path $classes,$testClasses -Force | Out-Null
$java='C:\Program Files\Java\jdk-25\bin'
$classpath=$velocityJar+';'+$queueJar
$sources=Get-ChildItem -LiteralPath (Join-Path $projectPath 'src\main\java') -Recurse -Filter '*.java' -File | Select-Object -ExpandProperty FullName
& (Join-Path $java 'javac.exe') --release 25 -encoding UTF-8 -proc:none -classpath $classpath -d $classes $sources
if($LASTEXITCODE-ne0){throw 'Compilation failed'}
Copy-Item -Path (Join-Path $projectPath 'src\main\resources\*') -Destination $classes -Recurse -Force
$tests=@(Get-ChildItem -LiteralPath (Join-Path $projectPath 'src\test\java') -Recurse -Filter '*.java' -File -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
if($tests.Count){
 & (Join-Path $java 'javac.exe') --release 25 -encoding UTF-8 -proc:none -classpath ($classes+';'+$classpath) -d $testClasses $tests
 if($LASTEXITCODE-ne0){throw 'Test compilation failed'}
 & (Join-Path $java 'java.exe') -ea -classpath ($testClasses+';'+$classes+';'+$classpath) com.nordfjell.nordqueuenotice.NoticeRegressionTest (Join-Path $buildPath 'unit-results.json')
 if($LASTEXITCODE-ne0){throw 'Regression checks failed'}
}
$jar=Join-Path $buildPath 'NordQueueNotice-1.1.0.jar'
& (Join-Path $java 'jar.exe') --create --file $jar -C $classes .
if($LASTEXITCODE-ne0){throw 'Packaging failed'}
$entries=& (Join-Path $java 'jar.exe') tf $jar
if($entries -match 'Test|Probe'){throw 'Test code in release'}
'VELOCITY_SHA256='+(Get-FileHash -LiteralPath $velocityJar -Algorithm SHA256).Hash
'SHA256='+(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
$jar
