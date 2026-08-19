#!/bin/sh
# T3 apt/dpkg 诊断脚本（在 Android 容器内执行：粘贴整段）
# 用途：定位 dpkg "error creating new backup file ... Permission denied (errno 13)" 的精确 syscall
# 环境：Ubuntu 24.04 rootfs / proot / untrusted_app / seccomp 2 filters

echo "=== [1] Capabilities ==="
grep -E '^(Cap(Inh|Prm|Eff|Bnd))' /proc/self/status
perl -e 'open F,"<","/proc/self/status"; while(<F>){ if(/^CapEff:\s*([0-9a-f]+)/i){ $v=hex($1); printf "CapEff=%x DAC_OVERRIDE=%d FOWNER=%d SYS_ADMIN=%d CHOWN=%d\n",$v,($v>>1)&1,($v>>3)&1,($v>>21)&1,$v&1; } }'

echo "=== [2] immutable flags（无 lsattr 则跳过） ==="
lsattr -d /var/lib/dpkg /var/lib/dpkg/status 2>&1 || echo "(lsattr 不可用)"

echo "=== [3] 复现 rename：mv status -> status-old -> 还原 ==="
mv /var/lib/dpkg/status /var/lib/dpkg/status-old && echo RENAME_OK || echo "RENAME_FAIL errno=$?"
mv /var/lib/dpkg/status-old /var/lib/dpkg/status && echo RESTORE_OK || echo "RESTORE_FAIL errno=$?"

echo "=== [4] 复现 link：ln status -> status-old -> 清理 ==="
ln /var/lib/dpkg/status /var/lib/dpkg/status-old && echo LINK_OK || echo "LINK_FAIL errno=$?"
rm -f /var/lib/dpkg/status-old && echo LINK_CLEAN

echo "=== [5] renameat2(276) 探测（RENAME_NOREPLACE） ==="
touch /tmp/ra2_a
perl -e 'syscall(276, -100, "/tmp/ra2_a", -100, "/tmp/ra2_b", 1) == 0 ? print "RENAMEAT2_OK\n" : print "RENAMEAT2_FAIL errno=".($!+0)." ($!)\n"'
ls /tmp/ra2_a /tmp/ra2_b 2>&1
rm -f /tmp/ra2_a /tmp/ra2_b

echo "=== [6] dmesg avc 拒绝日志（可能无权限） ==="
dmesg 2>&1 | tail -30 | grep -iE 'avc|denied|dpkg|f2fs' || echo "(dmesg 不可读或无匹配)"

echo "=== [7] 决定性复现：dpkg --configure -a ==="
dpkg --configure -a 2>&1 | tail -15

echo "=== [8] apt-get install tree（全新包） ==="
apt-get update 2>&1 | tail -3
apt-get install -y tree 2>&1 | tail -15
tree --version 2>&1
