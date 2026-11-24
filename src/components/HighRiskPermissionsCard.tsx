import { useCallback, useEffect, useRef, useState } from 'react'
import { ShieldAlert } from 'lucide-react'
import { t } from '../i18n'
import { runtimeBridge } from '../platform/native'
import {
  HIGH_RISK_PERMISSION_CATEGORIES,
  type HighRiskPermissionCategory,
  type HighRiskPermissions,
  type HighRiskPermissionsUpdate,
  type RuntimeBridge,
} from '../platform/types'

interface HighRiskCategoryMeta {
  id: HighRiskPermissionCategory
  tier: 'elevated' | 'high' | 'critical'
  title: string
  description: string
}

interface HighRiskCategoryGroup {
  id: string
  title: string
  items: readonly HighRiskCategoryMeta[]
}

const HIGH_RISK_CATEGORY_GROUPS: readonly HighRiskCategoryGroup[] = [
  {
    id: 'files_storage',
    title: '一、高危文件与存储修改（6 项）',
    items: [
      {
        id: 'core_partition_write',
        tier: 'high',
        title: '核心系统分区写入与重挂载',
        description: '拦截对 /system、/vendor、/product、/system_ext、/odm、/apex、/system_root 的 rw 重挂载及文件写入、覆盖、替换操作',
      },
      {
        id: 'system_data_destruction',
        tier: 'critical',
        title: '系统核心数据目录删除与覆写',
        description: '拦截对 /data 根目录及 /data/system、misc、vendor、apex、property、app、dalvik-cache、adb 的 rm、wipe、find -delete、shred、truncate、dd 覆写',
      },
      {
        id: 'shared_storage_delete',
        tier: 'critical',
        title: '内置共享存储（SD 卡）批量删除',
        description: '拦截对 /sdcard、/storage/emulated/0、/data/media/0 根目录及 DCIM、Pictures、Download、Documents 等关键目录的整目录或通配符删除',
      },
      {
        id: 'app_private_data_modify',
        tier: 'elevated',
        title: '应用私有数据目录修改与删除',
        description: '拦截对 /data/data、/data/user、/data/user_de 及 /sdcard/Android/data、obb 下应用私有文件的删除、截断与重定向写入（保留只读查看与备份）',
      },
      {
        id: 'system_permissions_owner',
        tier: 'elevated',
        title: '系统目录权限、属主与安全上下文修改',
        description: '拦截对核心系统分区、/dev、/proc、/sys 及 /data 系统子目录执行 chmod、chown、chgrp、setfacl、chcon、restorecon',
      },
      {
        id: 'dev_node_write',
        tier: 'elevated',
        title: '/dev 设备节点直接写入',
        description: '拦截向 /dev/ 下的非空设备节点（排除 /dev/null、/dev/zero、标准流）直接重定向或 tee 写入数据',
      },
    ],
  },
  {
    id: 'system_ops',
    title: '二、高危系统与运维命令（7 项）',
    items: [
      {
        id: 'dynamic_shell_eval',
        tier: 'elevated',
        title: '动态 Shell 展开与二次解释执行',
        description: '拦截 $(...)、反引号、$IFS 变量拼接、eval、exec、source 及 sh/bash/mksh -c 等动态命令变形（防止拼接绕过静态规则）',
      },
      {
        id: 'filesystem_mount',
        tier: 'elevated',
        title: '文件系统挂载与卸载',
        description: '拦截 mount 与 umount 挂载点变更命令（只读查看挂载表不受影响）',
      },
      {
        id: 'raw_device_image_tool',
        tier: 'elevated',
        title: '镜像直写与回环/映射设备工具',
        description: '拦截 dd、losetup、dmsetup 及带 of= 参数的原始镜像/设备写入命令',
      },
      {
        id: 'kernel_module_control',
        tier: 'elevated',
        title: '内核模块加载与卸载',
        description: '拦截 insmod、rmmod、modprobe 等内核模块操作',
      },
      {
        id: 'kernel_sysfs_proc_write',
        tier: 'elevated',
        title: '内核运行参数与 sysfs 写入',
        description: '拦截向 /proc/sys/ 与 /sys/(kernel|fs|module|class|devices|firmware)/ 写入内核运行参数',
      },
      {
        id: 'system_properties_write',
        tier: 'high',
        title: 'Android 系统属性与属性库修改',
        description: '拦截 setprop、resetprop（含 ctl.* 与 sys.powerctl）及直接写 /data/property、/dev/__properties__（只读 getprop 不受影响）',
      },
      {
        id: 'package_install_remove',
        tier: 'elevated',
        title: '应用安装、卸载、清空与组件禁用',
        description: '拦截 pm / cmd package 的 install、uninstall、clear、disable、disable-user、hide、suspend 操作（只读 pm list packages 不受影响）',
      },
    ],
  },
  {
    id: 'critical_hardware',
    title: '三、极危整机、安全与底层硬件（4 项）',
    items: [
      {
        id: 'system_service_privilege',
        tier: 'high',
        title: '关键系统服务控制与嵌套特权 Shell',
        description: '拦截 kill init (PID 1)、start/stop zygote/system_server/surfaceflinger/servicemanager，以及嵌套 su -c 与 adb shell',
      },
      {
        id: 'selinux_security_policy',
        tier: 'high',
        title: 'SELinux 强制模式与安全策略修改',
        description: '拦截 setenforce 0/permissive、写入 /sys/fs/selinux/enforce 及内核参数关闭 SELinux',
      },
      {
        id: 'device_power_control',
        tier: 'high',
        title: '设备重启、关机与电源触发',
        description: '拦截 reboot、poweroff、shutdown、halt、svc/cmd power、重启广播、写 /proc/sysrq-trigger 与 /sys/power/',
      },
      {
        id: 'block_flash_memory_raw',
        tier: 'critical',
        title: '物理块设备、分区格式化、刷机与物理内存访问',
        description: '拦截 /dev/block 读写、mkfs/parted/fdisk/sgdisk 分区格式化、fastboot/odin/edl 刷机、reboot bootloader/recovery 及 /dev/mem、/proc/kcore 访问',
      },
    ],
  },
] as const

const TIER_LABELS: Record<HighRiskCategoryMeta['tier'], string> = {
  elevated: '中高危',
  high: '高危',
  critical: '极危',
}

type PermissionsLoad =
  | { status: 'loading' }
  | { status: 'ready'; value: HighRiskPermissions }
  | { status: 'failed' }

function samePermissions(a: HighRiskPermissions, b: HighRiskPermissions): boolean {
  if (a.enabled !== b.enabled) return false
  return HIGH_RISK_PERMISSION_CATEGORIES.every(cat => a.categories[cat] === b.categories[cat])
}

/**
 * 启动器「AI 高危文件与命令权限」设置面板（1 个总开关 + 17 个细分开关）。
 *
 * 核心原则：
 *  - 所有规则均在宿主 `RootCommandGuard.verifyPermitted` 中硬编码主动拦截，绝不交由 AI 自行决定；
 *  - 默认总开关关闭、17 项细分开关全部关闭，补回放开权限带来的风险提升；
 *  - 即使开启对应细分开关，Root 通道下的审批与 Android 应用警告确认页仍继续生效（双重防线）。
 */
export function HighRiskPermissionsCard({ bridge = runtimeBridge }: { bridge?: RuntimeBridge }) {
  const [load, setLoad] = useState<PermissionsLoad>({ status: 'loading' })
  const [busy, setBusy] = useState(false)
  const [writeError, setWriteError] = useState<string | null>(null)
  const requestId = useRef(0)

  const refresh = useCallback(async () => {
    const currentRequest = ++requestId.current
    try {
      const next = await bridge.getHighRiskPermissions()
      if (currentRequest !== requestId.current) return
      setLoad(previous => (previous.status === 'ready' && samePermissions(previous.value, next)
        ? previous
        : { status: 'ready', value: next }))
    } catch {
      if (currentRequest !== requestId.current) return
      setLoad(previous => (previous.status === 'failed' ? previous : { status: 'failed' }))
    }
  }, [bridge])

  useEffect(() => {
    void refresh()
    return () => {
      requestId.current += 1
    }
  }, [refresh])

  const applyUpdate = useCallback(async (update: HighRiskPermissionsUpdate) => {
    setBusy(true)
    setWriteError(null)
    try {
      const next = await bridge.setHighRiskPermissions(update)
      setLoad({ status: 'ready', value: next })
    } catch (error) {
      setWriteError(error instanceof Error ? error.message : String(error))
      await refresh()
    } finally {
      setBusy(false)
    }
  }, [bridge, refresh])

  const value = load.status === 'ready' ? load.value : null
  const totalCount = HIGH_RISK_PERMISSION_CATEGORIES.length
  const enabledCount = value === null
    ? 0
    : HIGH_RISK_PERMISSION_CATEGORIES.filter(cat => value.categories[cat]).length

  const setAllCategories = useCallback((allowed: boolean) => {
    const all = Object.fromEntries(
      HIGH_RISK_PERMISSION_CATEGORIES.map(cat => [cat, allowed]),
    ) as Record<HighRiskPermissionCategory, boolean>
    void applyUpdate({ categories: all })
  }, [applyUpdate])

  const statusChipLabel = load.status === 'loading'
    ? t('读取中')
    : load.status === 'failed'
      ? t('读取失败')
      : !value?.enabled
        ? t('总开关已关 · 全部拦截')
        : t('已放行 {0} / {1} 项', enabledCount, totalCount)

  return (
    <section className="settings-section" aria-labelledby="high-risk-permissions">
      <div className="section-title section-title-action">
        <span className="section-icon"><ShieldAlert size={19} /></span>
        <div>
          <h2 id="high-risk-permissions">{t('AI 高危文件与命令权限')}</h2>
          <p>{t('由宿主硬编码规则在命令执行前主动拦截，绝不交由 AI 自行判断')}</p>
        </div>
        <span className={`status-chip ${value?.enabled && enabledCount > 0 ? 'warning' : 'success'}`}>
          {statusChipLabel}
        </span>
      </div>

      {load.status === 'loading' && (
        <p className="settings-note">{t('正在读取高危文件与命令权限设置…')}</p>
      )}

      {value !== null && (
        <>
          <label className="toggle-row">
            <span>
              <strong>{t('允许 AI 申请高危文件与命令权限（总开关）')}</strong>
              <small>
                {t('关闭时，下方全部 17 类高危文件修改与系统命令均由宿主硬编码规则直接拦截；开启后，仅对下方显式勾选的细分项放行。')}
              </small>
            </span>
            <input
              type="checkbox"
              role="switch"
              aria-label={t('允许 AI 申请高危文件与命令权限（总开关）')}
              checked={value.enabled}
              disabled={busy}
              onChange={event => {
                void applyUpdate({ enabled: event.target.checked })
              }}
            />
          </label>

          <p className="settings-note">
            {t('纵深防御说明：此处的硬编码规则对 Root 通道与 Shizuku 通道同时生效。即使开启了某项细分权限，Root 通道下的中高危操作仍需走 dsh 审批，高危与极危操作仍会强制弹出 Android 应用警告确认页。')}
          </p>

          <div className="settings-inline-actions">
            <button
              className="button button-secondary compact-button"
              type="button"
              disabled={busy || !value.enabled || enabledCount === totalCount}
              onClick={() => setAllCategories(true)}
            >
              {t('全部开启')}
            </button>
            <button
              className="button button-secondary compact-button"
              type="button"
              disabled={busy || !value.enabled || enabledCount === 0}
              onClick={() => setAllCategories(false)}
            >
              {t('全部关闭')}
            </button>
          </div>

          {HIGH_RISK_CATEGORY_GROUPS.map(group => (
            <div key={group.id} className="high-risk-group" role="group" aria-label={t(group.title)}>
              <h3 className="high-risk-group-title">{t(group.title)}</h3>
              <div className="high-risk-item-list">
                {group.items.map(item => {
                  const categoryChecked = value.categories[item.id]
                  return (
                    <label
                      key={item.id}
                      className={`toggle-row high-risk-toggle-row ${!value.enabled ? 'is-master-disabled' : ''}`}
                    >
                      <span>
                        <strong>
                          {t(item.title)}{' '}
                          <span className={`high-risk-tier-badge tier-${item.tier}`}>
                            {t(TIER_LABELS[item.tier])}
                          </span>
                        </strong>
                        <small>{t(item.description)}</small>
                      </span>
                      <input
                        type="checkbox"
                        role="switch"
                        aria-label={t(item.title)}
                        checked={categoryChecked}
                        disabled={busy || !value.enabled}
                        onChange={event => {
                          void applyUpdate({
                            categories: { [item.id]: event.target.checked },
                          })
                        }}
                      />
                    </label>
                  )
                })}
              </div>
            </div>
          ))}
        </>
      )}

      {writeError !== null && (
        <p role="alert">{t('保存高危文件与命令权限失败：{0}', writeError)}</p>
      )}

      {load.status === 'failed' && (
        <p role="alert">
          {t('无法读取高危文件与命令权限设置')}{' '}
          <button
            className="button button-quiet"
            type="button"
            onClick={() => { void refresh() }}
          >
            {t('重试')}
          </button>
        </p>
      )}
    </section>
  )
}
