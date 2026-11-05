# 抢焦点诊断（60 秒）—— 双击运行，然后按几次 Win+D
# 结果写入 docs/诊断-抢焦点.txt
$ErrorActionPreference = 'SilentlyContinue'
$out = Join-Path $PSScriptRoot '..\docs\诊断-抢焦点.txt'
$out = [System.IO.Path]::GetFullPath($out)

Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public class FgMon {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
}
"@

$lines = @()
$lines += "抢焦点诊断  开始时间 $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
$lines += "请在运行期间按几次 Win+D（每次间隔 2 秒），60 秒后关闭本窗口"
$lines += ""

$end = (Get-Date).AddSeconds(60)
$last = ''
while ((Get-Date) -lt $end) {
  $h = [FgMon]::GetForegroundWindow()
  $sb = New-Object System.Text.StringBuilder 256
  [void][FgMon]::GetWindowText($h, $sb, 256)
  $procId = 0
  [void][FgMon]::GetWindowThreadProcessId($h, [ref]$procId)
  $pname = try { (Get-Process -Id $procId -ErrorAction Stop).ProcessName } catch { '?' }
  $title = $sb.ToString()
  $key = "$pname|$title"
  if ($key -ne $last) {
    $lines += ('{0}  前台 → {1,-22} [{2}]' -f (Get-Date -Format 'HH:mm:ss.fff'), $pname,
               $title.Substring(0, [Math]::Min(56, $title.Length)))
    $last = $key
  }
  Start-Sleep -Milliseconds 150
}

# 附带：当前所有可见窗口（含被最小化的）
$lines += ""
$lines += "=== 监控结束时，所有有窗口的进程 ==="
Get-Process | Where-Object { $_.MainWindowHandle -ne 0 } | Sort-Object ProcessName | ForEach-Object {
  $lines += ('  {0,-24} PID={1,-7} 标题={2}' -f $_.ProcessName, $_.Id,
             $_.MainWindowTitle.Substring(0, [Math]::Min(46, $_.MainWindowTitle.Length)))
}

$lines += ""
$lines += "=== 开机自启项（可能是元凶）==="
foreach ($k in @('HKCU:\Software\Microsoft\Windows\CurrentVersion\Run',
                 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Run')) {
  $item = Get-ItemProperty $k -ErrorAction SilentlyContinue
  if ($item) {
    $item.PSObject.Properties | Where-Object { $_.Name -notlike 'PS*' } | ForEach-Object {
      $lines += ('  [{0}] {1} = {2}' -f ($k -split '\\')[-2], $_.Name, $_.Value)
    }
  }
}

$lines | Out-File -FilePath $out -Encoding UTF8
Write-Host ""
Write-Host "已写入: $out"
Write-Host "把这个文件路径发给我，或者直接说一声“好了”。"
Start-Sleep -Seconds 3
