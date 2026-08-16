package io.deepseekharness.mobile.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.system.Os
import java.io.BufferedOutputStream
import java.io.File
import java.net.InetAddress
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.UUID

object RuntimeDns {
    private val safeAddress = Regex("^[0-9A-Fa-f:.%A-Za-z_-]{1,96}$")
    private val fallbackAddresses = listOf("1.1.1.1", "8.8.8.8")

    fun refresh(context: Context, destination: File) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val addresses = manager?.activeNetwork
            ?.let(manager::getLinkProperties)
            ?.dnsServers
            .orEmpty()
        val content = formatConfig(addresses).toByteArray(Charsets.US_ASCII)
        val parent = destination.parentFile
            ?: throw RuntimeFailure("FILESYSTEM_ERROR", "DNS 配置路径无效")
        val temporary = File(parent, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(
                temporary.toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                BufferedOutputStream(Channels.newOutputStream(channel), 4096).use { output ->
                    output.write(content)
                    output.flush()
                    channel.force(true)
                }
            }
            Os.chmod(temporary.absolutePath, 0x180)
            Os.rename(temporary.absolutePath, destination.absolutePath)
        } catch (error: Throwable) {
            temporary.delete()
            if (error is RuntimeFailure) throw error
            throw RuntimeFailure("FILESYSTEM_ERROR", "无法生成 Ubuntu DNS 配置", error)
        }
    }

    internal fun formatConfig(addresses: List<InetAddress>): String {
        val normalized = addresses.asSequence()
            .filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isMulticastAddress }
            .mapNotNull { it.hostAddress }
            .filter { safeAddress.matches(it) }
            .distinct()
            .take(MAX_DNS_SERVERS)
            .toList()
            .ifEmpty { fallbackAddresses }
        return buildString {
            normalized.forEach { append("nameserver ").append(it).append('\n') }
            append("options timeout:2 attempts:3\n")
        }
    }

    private const val MAX_DNS_SERVERS = 4
}
