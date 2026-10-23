package io.github.hafeee44lab.solargrid.data

import io.github.hafeee44lab.solargrid.data.api.DeviceDataRequest
import io.github.hafeee44lab.solargrid.data.api.DeviceState
import io.github.hafeee44lab.solargrid.data.api.PageRequest
import io.github.hafeee44lab.solargrid.data.api.SolarMetric
import io.github.hafeee44lab.solargrid.data.api.SolarmanApi
import io.github.hafeee44lab.solargrid.data.api.SolarmanService
import io.github.hafeee44lab.solargrid.data.api.StationDevicesRequest
import io.github.hafeee44lab.solargrid.data.api.TokenRequest
import io.github.hafeee44lab.solargrid.data.demo.DemoInverter

sealed interface FetchResult {
    data class Ok(
        val metrics: List<SolarMetric>,
        val collectedAt: Long,
        val inverterAlarm: Boolean
    ) : FetchResult

    data object LoggerOffline : FetchResult

    data class Failed(val message: String, val authProblem: Boolean = false) : FetchResult
}

interface ReadingSource {
    suspend fun fetch(): FetchResult
}

/** One inverter discovered on the account during onboarding. */
data class DiscoveredDevice(val sn: String, val station: String, val online: Boolean?)

class SolarmanSource(
    private val account: Account,
    private val state: StateStore,
    private val api: SolarmanApi = SolarmanService.api
) : ReadingSource {

    override suspend fun fetch(): FetchResult {
        var token = when (val t = token(forceRefresh = false)) {
            is TokenResult.Ok -> t.token
            is TokenResult.Failed -> return FetchResult.Failed(t.message, authProblem = true)
        }

        var response = api.currentData(account.appId, "Bearer $token", DeviceDataRequest(account.deviceSn))

        if (response.code() == 401 || response.code() == 403) {
            token = when (val t = token(forceRefresh = true)) {
                is TokenResult.Ok -> t.token
                is TokenResult.Failed -> return FetchResult.Failed(t.message, authProblem = true)
            }
            response = api.currentData(account.appId, "Bearer $token", DeviceDataRequest(account.deviceSn))
        }

        if (!response.isSuccessful) return FetchResult.Failed("Solarman returned HTTP ${response.code()}")

        val body = response.body() ?: return FetchResult.Failed("Solarman sent an empty reply")

        if (body.success == false) {
            return FetchResult.Failed("Solarman rejected the request: ${body.msg ?: body.code ?: "no reason given"}")
        }

        if (body.deviceState == DeviceState.OFFLINE) return FetchResult.LoggerOffline

        val metrics = body.dataList
        if (metrics.isNullOrEmpty()) return FetchResult.Failed("Inverter ${account.deviceSn} returned no metrics")

        return FetchResult.Ok(
            metrics = metrics,
            collectedAt = (body.collectionTime ?: 0L) * 1000L,
            inverterAlarm = body.deviceState == DeviceState.ALARM
        )
    }

    private sealed interface TokenResult {
        data class Ok(val token: String) : TokenResult
        data class Failed(val message: String) : TokenResult
    }

    private suspend fun token(forceRefresh: Boolean): TokenResult {
        val cached = state.token
        if (!forceRefresh && cached != null && System.currentTimeMillis() < cached.second - TOKEN_MARGIN_MS) {
            return TokenResult.Ok(cached.first)
        }

        return when (val result = login(api, account)) {
            is LoginResult.Ok -> {
                state.token = result.token to (System.currentTimeMillis() + result.lifetimeMs)
                TokenResult.Ok(result.token)
            }
            is LoginResult.Failed -> {
                state.token = null
                TokenResult.Failed(result.message)
            }
        }
    }

    companion object {
        private const val TOKEN_MARGIN_MS = 5 * 60_000L

        sealed interface LoginResult {
            data class Ok(val token: String, val lifetimeMs: Long) : LoginResult
            data class Failed(val message: String) : LoginResult
        }

        suspend fun login(api: SolarmanApi, account: Account): LoginResult {
            val response = api.token(account.appId, TokenRequest(account.appSecret, account.email, account.passwordSha256))
            if (!response.isSuccessful) return LoginResult.Failed("Sign-in failed (HTTP ${response.code()})")

            val body = response.body()
            val token = body?.accessToken
            if (token.isNullOrBlank()) {
                return LoginResult.Failed(body?.msg?.let { "Sign-in rejected: $it" } ?: "Sign-in rejected — check the App ID, secret, email and password")
            }
            return LoginResult.Ok(token, (body.expiresIn ?: 3600L) * 1000L)
        }

        /** Signs in and lists every inverter on the account, for the onboarding picker. */
        suspend fun discover(account: Account, api: SolarmanApi = SolarmanService.api): Result<List<DiscoveredDevice>> =
            try {
                when (val login = login(api, account)) {
                    is LoginResult.Failed -> Result.failure(IllegalStateException(login.message))
                    is LoginResult.Ok -> {
                        val bearer = "Bearer ${login.token}"
                        val stations = api.stations(account.appId, bearer, PageRequest()).body()?.stationList.orEmpty()

                        val devices = stations.flatMap { station ->
                            val id = station.id ?: return@flatMap emptyList()
                            api.stationDevices(account.appId, bearer, StationDevicesRequest(id))
                                .body()?.deviceListItems.orEmpty()
                                .mapNotNull { device ->
                                    device.deviceSn?.let {
                                        DiscoveredDevice(it, station.name ?: "Station $id", device.connectStatus?.let { s -> s == 1 })
                                    }
                                }
                        }
                        Result.success(devices)
                    }
                }
            } catch (e: Exception) {
                Result.failure(IllegalStateException("Couldn't reach Solarman: ${e.localizedMessage ?: e.javaClass.simpleName}"))
            }
    }
}

class DemoSource(private val inverter: DemoInverter) : ReadingSource {
    override suspend fun fetch(): FetchResult {
        val now = System.currentTimeMillis()
        return FetchResult.Ok(
            metrics = inverter.metricsAt(now),
            collectedAt = inverter.collectionTime(now),
            inverterAlarm = false
        )
    }
}
