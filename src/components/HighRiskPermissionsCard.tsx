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
    title: '一、文件与存储修改（6 项）',
    items: [
      {
        id: 'core_partition_write',
        tier: 'high',
        title: '核心系统分区写入与重挂载',
        description: '拦截对 /system、/vendor、/product、/system_ext、/odm、/apex、/system_root 的读写重挂载及文件写入、覆盖、替换操作',
      },
      {
        id: 'system_data_destruction',
        tier: 'critical',
        title: '系统核心数据目录删除与覆写',
        description: '拦截对 /data 根目录及 /data/system、misc、vendor、apex、property、app、dalvik-cache、adb 的删除与覆写操作',
      },
      {
        id: 'shared_storage_delete',
        tier: 'critical',
        title: '内置共享存储（SD 卡）批量删除',
        description: '拦截对 /sdcard、/storage/emulated/0、/data/media/0 根目录及 DCIM、Pictures、Download、Documents 等目录的整目录或通配符删除',
      },
      {
        id: 'app_private_data_modify',
        tier: 'elevated',
        title: '应用私有数据目录修改与删除',
        description: '拦截对 /data/data、/data/user、/data/user_de 及 /sdcard/Android/data、obb 下应用私有文件的删除、截断与写入（保留只读查看与备份）',
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
        description: '拦截向 /dev/ 下非空设备节点（排除 /dev/null、/dev/zero、标准流）直接重定向或 tee 写入数据',
      },
    ],
  },
  {
    id: 'system_ops',
    title: '二、系统与内核命令（7 项）',
    items: [
      {
        id: 'dynamic_shell_eval',
        tier: 'elevated',
        title: '动态 Shell 展开与二次解释执行',
        description: '拦截 $(...)、反引号、$IFS 变量拼接、eval、exec、source 及 sh/bash/mksh -c 等动态命令变形，防止拼接绕过拦截规则',
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
        description: '拦截 dd、losetup、dmsetup 及带 of= 参数的原始镜像或设备写入命令',
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
    title: '三、系统安全与底层硬件（4 项）',
    items: [
      {
        id: 'system_service_privilege',
        tier: 'high',
        title: '关键系统服务控制与嵌套特权 Shell',
        description: '拦截终止 init (PID 1)、启停 zygote/system_server/surfaceflinger/servicemanager，以及嵌套执行 su -c 与 adb shell',
      },
      {
        id: 'selinux_security_policy',
        tier: 'high',
        title: 'SELinux 强制模式与安全策略修改',
        description: '拦截 setenforce 0/permissive、写入 /sys/fs/selinux/enforce 及通过内核参数关闭 SELinux',
      },
      {
        id: 'device_power_control',
        tier: 'high',
        title: '设备重启、关机与电源触发',
        description: '拦截 reboot、poweroff、shutdown、halt、svc/cmd power、重启广播及写入 /proc/sysrq-trigger、/sys/power/',
      },
      {
        id: 'block_flash_memory_raw',
        tier: 'critical',
        title: '物理块设备、分区格式化、刷机与物理内存访问',
        description: '拦截 /dev/block 读写、mkfs/parted/fdisk/sgdisk 分区格式化、fastboot/odin/edl 刷机、reboot bootloader/recovery 及 /dev/mem、/proc/kcore 访问',
      },
    ],
  },
  {
    id: 'host_capability_unlock',
    title: '四、解除高级限制（沙箱、确认页与设备能力，8 项）',
    items: [
      {
        id: 'root_unconfined_sandbox',
        tier: 'critical',
        title: 'Root 解除命名空间与能力沙箱包装',
        description: '跳过 unshare 命名空间隔离、/system 只读重挂载、/dev/block 屏蔽与 setpriv 能力裁剪，直接以完整宿主 UID 0 环境执行 Root 命令（关闭即恢复沙箱包装）',
      },
      {
        id: 'root_skip_app_confirm',
        tier: 'critical',
        title: 'Root 高危与极危跳过应用警告页直通',
        description: '当命令命中的细分分类均已开启时，跳过 dsh 审批与 Android 应用高危/极危警告确认页实现自动执行（未开启对应分类时仍拦截）',
      },
      {
        id: 'accessibility_system_apps',
        tier: 'high',
        title: '无障碍操作受保护系统组件与权限弹窗',
        description: '放开 android、系统设置、系统界面（SystemUI）、应用安装器与权限控制器等系统包名限制，允许无障碍读取与点击系统弹窗',
      },
      {
        id: 'accessibility_sensitive_windows',
        tier: 'critical',
        title: '无障碍读取与操作敏感窗口及密码框',
        description: '解除密码框、验证码、支付与授权窗口的整棵拦截，并在主屏与副屏无障碍树中放开可编辑输入框文本与敏感词输入',
      },
      {
        id: 'accessibility_lockscreen_and_burst',
        tier: 'high',
        title: '无障碍解除锁屏拦截与高频动作节流',
        description: '允许熄屏/锁屏状态下继续调用无障碍读取与动作，并取消主屏与副屏连续动作之间的强制冷却间隔',
      },
      {
        id: 'device_file_unrestricted',
        tier: 'high',
        title: '设备文件通道解除投递区目录与大小限制',
        description: '放开 root=device 整机绝对路径读写与建目录，并将文件通道单文件传输上限从 128 KiB 提升至 4 MiB（关闭即恢复仅限 inbox/outbox 与 128 KiB）',
      },
      {
        id: 'virtual_screen_unrestricted',
        tier: 'high',
        title: '副屏解除主屏限制与任意 URI Scheme 启动',
        description: '允许副屏 Shell 指定 displayId=0（主屏，且不要求已启动副屏会话），并放开副屏 launch 调起自定义应用 URI Scheme（仍拒绝 file/javascript/data）',
      },
      {
        id: 'storage_full_sdcard_mount',
        tier: 'high',
        title: '访客自动挂载完整共享存储（/mnt/sdcard）与 Android 目录',
        description: '下次启动 Ubuntu 访客时自动将完整内置存储挂载到 /mnt/sdcard，并放开目录白名单选择共享存储根目录与 Android/data、obb 子目录（关闭后下次启动撤除）',
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
 * 启动器「AI 高危文件与命令权限」设置面板（1 个总开关 + 25 个细分开关）。
 *
 * 核心原则：
 *  - 所有规则均在宿主硬编码主动拦截，绝不交由 AI 自行决定；
 *  - 默认总开关关闭、25 项细分开关全部关闭，补回放开权限带来的风险提升；
 *  - 前 17 项开启后默认仍保留沙箱隔离与 Root 应用警告确认页，第四组 8 项按需单独放开宿主级限制，关闭即刻恢复硬编码拦截。
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
    const currentRequest = ++requestId.current
    setBusy(true)
    setWriteError(null)
    try {
      const next = await bridge.setHighRiskPermissions(update)
      if (currentRequest !== requestId.current) return
      setLoad({ status: 'ready', value: next })
    } catch (error) {
      if (currentRequest !== requestId.current) return
      setWriteError(error instanceof Error ? error.message : String(error))
      setBusy(false)
      void refresh()
    } finally {
      if (currentRequest === requestId.current) {
        setBusy(false)
      }
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
        ? t('已关闭 · 全部拦截')
        : t('已放行 {0} / {1} 项', enabledCount, totalCount)

  return (
    <section className="settings-section" aria-labelledby="high-risk-permissions">
      <div className="section-title section-title-action">
        <span className="section-icon"><ShieldAlert size={19} /></span>
        <div>
          <h2 id="high-risk-permissions">{t('AI 高危操作控制')}</h2>
          <p>{t('默认由本机安全规则拦截高危操作，仅放行你手动开启的类别')}</p>
        </div>
        <span className={`status-chip ${value?.enabled && enabledCount > 0 ? 'warning' : 'success'}`}>
          {statusChipLabel}
        </span>
      </div>

      {load.status === 'loading' && (
        <p className="settings-note">{t('正在读取高危操作控制设置…')}</p>
      )}

      {value !== null && (
        <>
          <label className="toggle-row">
            <span>
              <strong>{t('允许放行高危操作（总开关）')}</strong>
              <small>
                {t('关闭时，下方全部 25 项高危操作均由本机安全规则直接拦截；开启后，仅放行下方勾选的类别。')}
              </small>
            </span>
            <input
              type="checkbox"
              role="switch"
              aria-label={t('允许放行高危操作（总开关）')}
              checked={value.enabled}
              disabled={busy}
              onChange={event => {
                void applyUpdate({ enabled: event.target.checked })
              }}
            />
          </label>

          <p className="settings-note">
            {t('安全说明：规则覆盖 Root、Shizuku、无障碍、文件通道、副屏与存储挂载。前 17 项放行后仍默认保留沙箱隔离与 Root 警告确认页；仅在开启第四组对应开关后才会解除这些限制，关闭后立即恢复拦截。')}
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

          {HIGH_RISK_CATEGORY_GROUPS.map(group => {
            const groupEnabledCount = group.items.filter(item => value.categories[item.id]).length
            return (
            <div key={group.id} className="high-risk-group" role="group" aria-label={t(group.title)}>
              <div className="high-risk-group-header">
                <h3 className="high-risk-group-title">{t(group.title)}</h3>
                <span className="high-risk-group-count">{t('已开 {0} / {1}', groupEnabledCount, group.items.length)}</span>
              </div>
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
            )
          })}
        </>
      )}

      {writeError !== null && (
        <p role="alert">{t('保存高危操作设置失败：{0}', writeError)}</p>
      )}

      {load.status === 'failed' && (
        <p role="alert">
          {t('无法读取高危操作控制设置')}{' '}
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
