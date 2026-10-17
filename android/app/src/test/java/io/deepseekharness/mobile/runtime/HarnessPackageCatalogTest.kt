package io.deepseekharness.mobile.runtime

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HarnessPackageCatalogTest {
    private fun packageJson(version: String): JSONObject = JSONObject().put("name", "@deepseek-ai/dsh").put("version", version)
        .put("dist", JSONObject().put("tarball", "https://registry.npmjs.org/@deepseek-ai/dsh/-/dsh-$version.tgz")
            .put("integrity", "sha512-" + "a".repeat(86) + "=="))
    private fun catalog(versions: JSONObject): ByteArray = JSONObject().put("name", "@deepseek-ai/dsh").put("versions", versions)
        .put("dist-tags", JSONObject().put("next", "0.2.1-rc.2")).toString().toByteArray()

    @Test fun allVersionsAreListedInSemanticOrder() {
        val versions = JSONObject()
        for (index in 0..50) versions.put("0.1.$index", packageJson("0.1.$index"))
        for (version in listOf("0.2.1-alpha.10", "0.2.1-alpha.2", "0.2.1-rc.2", "0.2.1")) versions.put(version, packageJson(version))
        val result = HarnessPackageCatalog.parse(catalog(versions))
        assertEquals(55, result.size)
        assertEquals(listOf("0.2.1", "0.2.1-rc.2", "0.2.1-alpha.10", "0.2.1-alpha.2"), result.take(4).map { it.version })
        assertEquals(listOf("next"), result[1].tags)
    }
    @Test fun invalidSourceOrIntegrityIsNotInstallable() {
        val wrongSource = packageJson("0.2.1")
        wrongSource.getJSONObject("dist").put("tarball", "https://example.com/dsh.tgz")
        val wrongIntegrity = packageJson("0.2.0")
        wrongIntegrity.getJSONObject("dist").put("integrity", "sha512-invalid")
        assertTrue(HarnessPackageCatalog.parse(catalog(JSONObject().put("0.2.1", wrongSource).put("0.2.0", wrongIntegrity))).isEmpty())
    }
    @Test fun malformedCatalogAndInjectedVersionsAreRejected() {
        assertThrows(RuntimeFailure::class.java) { HarnessPackageCatalog.parse("[]".toByteArray()) }
        for (value in listOf("../x", "latest", "0.2.1;id", "01.2.3", "0.2.1\n")) {
            assertThrows(RuntimeFailure::class.java) { HarnessPackageCatalog.requireVersion(value) }
        }
    }
}
