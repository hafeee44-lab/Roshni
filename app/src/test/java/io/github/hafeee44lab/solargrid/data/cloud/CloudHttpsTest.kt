package io.github.hafeee44lab.solargrid.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

/** Sign-in details only ever leave the phone encrypted, whatever server address is typed. */
class CloudHttpsTest {

    @Test
    fun `typed http servers are upgraded to https`() {
        assertEquals("https://eu5.fusionsolar.huawei.com/", HuaweiSource.baseUrl("http://eu5.fusionsolar.huawei.com"))
        assertEquals("https://www.soliscloud.com:13333", SolisSource.baseUrl("http://www.soliscloud.com:13333/"))
        assertEquals("https://openapi.growatt.com/", GrowattSource.baseUrl("http://openapi.growatt.com"))
    }

    @Test
    fun `defaults are https`() {
        assertEquals("https://intl.fusionsolar.huawei.com/", HuaweiSource.baseUrl(""))
        assertEquals("https://www.soliscloud.com:13333", SolisSource.baseUrl(" "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `the client refuses plain http`() {
        kotlinx.coroutines.runBlocking { OkCloudHttp.get("http://example.com/") }
    }
}
