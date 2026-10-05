param([string]$ProxyPath='C:\Users\artyo\Documents\Codex\nordcommands-paper-test-20261004\proxy')
$ErrorActionPreference='Stop'
$resolved=(Resolve-Path -LiteralPath $ProxyPath).Path
if(-not $resolved.StartsWith('C:\Users\artyo\Documents\Codex\',[StringComparison]::OrdinalIgnoreCase)){throw 'LOCAL Velocity library required'}
$support=Split-Path -Parent $MyInvocation.MyCommand.Path
$classes=Join-Path $support ('build\probe-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$queue='Z:\Minecraft Plagins\NordQueue\releases\1.1.2\NordQueue-1.1.2.jar'
& 'C:\Program Files\Java\jdk-25\bin\javac.exe' --release 25 -encoding UTF-8 -proc:none -classpath ((Join-Path $resolved 'velocity.jar')+';'+$queue) -d $classes (Join-Path $support 'probe\NoticeTestProbe.java')
if($LASTEXITCODE-ne0){throw 'Probe compile failed'}
Copy-Item -LiteralPath (Join-Path $support 'probe\velocity-plugin.json') -Destination $classes
& 'C:\Program Files\Java\jdk-25\bin\jar.exe' --create --file (Join-Path $support 'build\QueueNoticeTestProbe.jar') -C $classes .
if($LASTEXITCODE-ne0){throw 'Probe package failed'}
