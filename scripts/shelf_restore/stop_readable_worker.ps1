param([int]$KeepPid = 0)
Get-CimInstance Win32_Process -Filter "Name='python.exe'" |
  Where-Object { $_.CommandLine -match 'run_readable' } |
  ForEach-Object {
    if ($KeepPid -ne 0 -and $_.ProcessId -eq $KeepPid) {
      Write-Output "keep $($_.ProcessId)"
      return
    }
    Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    Write-Output "killed $($_.ProcessId)"
  }
