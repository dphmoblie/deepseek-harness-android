package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTerminalPluginBackupTest {
    @Test
    fun acceptsOnlyThirdPartyPackageNames() {
        assertTrue(RuntimeTerminalPluginBackup.isAllowedUserPackageName("dshmarket"))
        assertTrue(RuntimeTerminalPluginBackup.isAllowedUserPackageName("@scope/plugin-name"))
        assertFalse(RuntimeTerminalPluginBackup.isAllowedUserPackageName("../escape"))
        assertFalse(RuntimeTerminalPluginBackup.isAllowedUserPackageName("/absolute"))
        assertFalse(RuntimeTerminalPluginBackup.isAllowedUserPackageName("@scope/../escape"))
        assertFalse(RuntimeTerminalPluginBackup.isAllowedUserPackageName("@deepseek-ai/dsh"))
        assertFalse(RuntimeTerminalPluginBackup.isAllowedUserPackageName("@deepseek-harness/dsh-mobile-shizuku"))
        assertFalse(RuntimeTerminalPluginBackup.isAllowedUserPackageName("react"))
    }

    @Test
    fun backupDirectoryNameIsStrictlyScoped() {
        assertTrue(RuntimeTerminalPluginBackup.isBackupDirectoryName("terminal-plugin-backup-123e4567-e89b-12d3-a456-426614174000"))
        assertFalse(RuntimeTerminalPluginBackup.isBackupDirectoryName("terminal-plugin-backup-"))
        assertFalse(RuntimeTerminalPluginBackup.isBackupDirectoryName("terminal-plugin-backup-../current"))
        assertFalse(RuntimeTerminalPluginBackup.isBackupDirectoryName("preserve-123e4567-e89b-12d3-a456-426614174000"))
    }

    @Test
    fun recoveryJournalOnlyAcceptsPluginModulePaths() {
        assertTrue(RuntimeTerminalPluginBackup.isModulePath("root/.dsh/profiles/web/node_modules/pkg"))
        assertTrue(RuntimeTerminalPluginBackup.isModulePath("root/.dsh/profiles/node_modules/@scope/pkg"))
        assertFalse(RuntimeTerminalPluginBackup.isModulePath("root/.dsh/profiles/web/package.json"))
        assertFalse(RuntimeTerminalPluginBackup.isModulePath("root/.dsh/profiles/other/file"))
        assertTrue(RuntimeTerminalPluginBackup.isModulePath("root/.dsh/profiles/web/node_modules/.pnpm/pkg"))
        assertFalse(RuntimeTerminalPluginBackup.isModulePath("root/.dsh/profiles/web/package.json"))
    }
}
