#!/system/bin/sh
# 采样某个进程及其各线程的 CPU 占用。
#
# 用法： sh perf.sh <包名> [秒数]
#
# 为什么不用 `dumpsys cpuinfo`：它的百分比是「自上次调用以来的增量」，
# 窗口多长完全取决于上一次谁调过它，对不上号就会读出很离谱的数。
# 这里直接读 /proc/<pid>/stat 的 utime+stime（单位 = 时钟滴答），
# 起止两次快照相减，除以 (HZ × 秒数) 就是「占单个核的百分比」。
#
# 注意：800% 表示 8 个核全满，所以「100%」不等于「跑满机器」，而是**跑满一个核**。

PKG="$1"
DUR="${2:-10}"
PID="$(pidof "$PKG" | awk '{print $1}')"

if [ -z "$PID" ]; then
  echo "ERR: 找不到进程 $PKG"
  exit 1
fi

HZ="$(getconf CLK_TCK 2>/dev/null)"
[ -z "$HZ" ] && HZ=100

SNAP=/data/local/tmp/perf_start.txt
ENDS=/data/local/tmp/perf_end.txt

snap() {
  : > "$1"
  for f in /proc/$PID/task/*/stat; do
    [ -r "$f" ] || continue
    tid="${f#/proc/$PID/task/}"
    tid="${tid%/stat}"
    ticks="$(awk '{print $14+$15}' "$f" 2>/dev/null)"
    [ -z "$ticks" ] && continue
    comm="$(cat /proc/$PID/task/$tid/comm 2>/dev/null)"
    echo "$tid $ticks $comm" >> "$1"
  done
}

echo "=== $PKG  pid=$PID  hz=$HZ  采样 ${DUR}s ==="
snap "$SNAP"
sleep "$DUR"
snap "$ENDS"

echo "--- 线程占用（% = 单个核的百分比） ---"
awk -v hz="$HZ" -v dur="$DUR" '
  NR==FNR { a[$1] = $2; next }
  ($1 in a) {
    d = $2 - a[$1]
    if (d > 0) printf "%7.1f%%  %-20s tid=%s\n", d / hz / dur * 100, $3, $1
  }
' "$SNAP" "$ENDS" | sort -rn | head -12

echo "--- 进程合计 ---"
awk -v hz="$HZ" -v dur="$DUR" '
  NR==FNR { a[$1] = $2; next }
  ($1 in a) { total += $2 - a[$1] }
  END { printf "%7.1f%%\n", total / hz / dur * 100 }
' "$SNAP" "$ENDS"

echo "--- 内存（该进程） ---"
awk '
  /^VmRSS/ { printf "VmRSS        %s kB\n", $2 }
  /^VmSize/ { printf "VmSize       %s kB\n", $2 }
  /^Threads/ { printf "Threads      %s\n", $2 }
' /proc/$PID/status
echo "--- 累计 CPU 时间 ---"
awk '{printf "utime+stime  %.1f s (用户 %.1f / 内核 %.1f)\n", ($14+$15)/100, $14/100, $15/100}' /proc/$PID/stat
