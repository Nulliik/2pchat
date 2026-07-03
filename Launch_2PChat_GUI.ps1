$ErrorActionPreference = "Stop"

$projectRoot = $PSScriptRoot
$pidFile = Join-Path $projectRoot ".2pchat_gui.pid"
$logFile = Join-Path $projectRoot "Launch_2PChat_GUI.log"

function Resolve-PythonLauncher {
    $pythonCmd = Get-Command python -ErrorAction SilentlyContinue
    if ($pythonCmd) {
        $pythonExe = $pythonCmd.Source
        $pythonwExe = Join-Path (Split-Path $pythonExe -Parent) "pythonw.exe"
        if (Test-Path $pythonwExe) {
            return $pythonwExe
        }
        return $pythonExe
    }

    $pywCmd = Get-Command pyw -ErrorAction SilentlyContinue
    if ($pywCmd) {
        return $pywCmd.Source
    }

    $pyCmd = Get-Command py -ErrorAction SilentlyContinue
    if ($pyCmd) {
        return $pyCmd.Source
    }

    throw "Python launcher not found. Install Python and ensure it is on PATH."
}

$launcher = Resolve-PythonLauncher
$arguments = @("-m", "messenger.app.kivy_gui")

Set-Content -Path $logFile -Value ("[{0}] Launcher: {1}" -f (Get-Date), $launcher)

$startParams = @{
    FilePath         = $launcher
    ArgumentList     = $arguments
    WorkingDirectory = $projectRoot
    PassThru         = $true
}

$launcherName = [System.IO.Path]::GetFileName($launcher).ToLowerInvariant()
if ($launcherName -notin @("pythonw.exe", "pyw.exe")) {
    $startParams["WindowStyle"] = "Hidden"
}

$process = Start-Process @startParams

Set-Content -Path $pidFile -Value $process.Id -Encoding ascii
Add-Content -Path $logFile -Value ("[{0}] Started PID {1}" -f (Get-Date), $process.Id)
