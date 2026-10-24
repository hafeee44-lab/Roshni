package io.github.hafeee44lab.solargrid.data.cloud

import io.github.hafeee44lab.solargrid.data.DiscoveredDevice
import io.github.hafeee44lab.solargrid.data.ReadingSource

/** One place that knows which source class reads which brand. Solarman is handled by SolarmanSource. */
object CloudSources {

    fun create(account: BrandAccount, session: SessionCache, http: CloudHttp = OkCloudHttp): ReadingSource? = when (account.brand) {
        CloudBrand.SOLARMAN -> null
        CloudBrand.GROWATT -> GrowattSource(account, session, http)
        CloudBrand.HUAWEI -> HuaweiSource(account, session, http)
        CloudBrand.SOLIS -> SolisSource(account, session, http)
        CloudBrand.DESS -> DessSource(account, session, http)
    }

    /** Signs in with [fields] and lists the inverters the source can read. */
    suspend fun discover(brand: CloudBrand, fields: Map<String, String>, http: CloudHttp = OkCloudHttp): Result<List<DiscoveredDevice>> = when (brand) {
        CloudBrand.SOLARMAN -> Result.failure(IllegalArgumentException("Solarman signs in through its own form"))
        CloudBrand.GROWATT -> GrowattSource.discover(fields, http)
        CloudBrand.HUAWEI -> HuaweiSource.discover(fields, http)
        CloudBrand.SOLIS -> SolisSource.discover(fields, http)
        CloudBrand.DESS -> DessSource.discover(fields, http)
    }
}
