package io.github.hafeee44lab.solargrid.data.api

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

data class TokenRequest(
    @SerializedName("appSecret") val appSecret: String,
    @SerializedName("email") val email: String,
    @SerializedName("password") val passwordHash: String
)

data class TokenResponse(
    @SerializedName("access_token") val accessToken: String?,
    @SerializedName("expires_in") val expiresIn: Long?,
    val success: Boolean?,
    val code: String?,
    val msg: String?
)

data class DeviceDataRequest(
    @SerializedName("deviceSn") val deviceSn: String
)

/**
 * Solarman's `deviceState`: 1 online, 2 alarm/failure, 3 offline. An inverter in alarm still
 * reports perfectly good numbers — a Deye raises one the moment it loses the grid — so only
 * state 3 means the data cannot be trusted.
 */
object DeviceState {
    const val ONLINE = 1
    const val ALARM = 2
    const val OFFLINE = 3
}

data class DeviceDataResponse(
    val success: Boolean?,
    val code: String?,
    val msg: String?,
    val deviceSn: String?,
    val deviceState: Int?,
    val collectionTime: Long?,
    val dataList: List<SolarMetric>?
)

data class SolarMetric(
    val key: String?,
    val name: String?,
    val value: String?,
    val unit: String?
)

data class PageRequest(val page: Int = 1, val size: Int = 50)

data class StationListResponse(
    val success: Boolean?,
    val msg: String?,
    val stationList: List<Station>?
)

data class Station(
    val id: Long?,
    val name: String?,
    val locationAddress: String?,
    val installedCapacity: Double?
)

data class StationDevicesRequest(
    val stationId: Long,
    val deviceType: String = "INVERTER",
    val page: Int = 1,
    val size: Int = 50
)

data class StationDevicesResponse(
    val success: Boolean?,
    val msg: String?,
    val deviceListItems: List<StationDevice>?
)

data class StationDevice(
    val deviceSn: String?,
    val deviceId: Long?,
    val deviceType: String?,
    val connectStatus: Int?
)

interface SolarmanApi {

    @Headers("Content-Type: application/json")
    @POST("account/v1.0/token?language=en")
    suspend fun token(
        @Query("appId") appId: String,
        @Body request: TokenRequest
    ): Response<TokenResponse>

    @Headers("Content-Type: application/json")
    @POST("device/v1.0/currentData?language=en")
    suspend fun currentData(
        @Query("appId") appId: String,
        @Header("Authorization") bearer: String,
        @Body request: DeviceDataRequest
    ): Response<DeviceDataResponse>

    @Headers("Content-Type: application/json")
    @POST("station/v1.0/list?language=en")
    suspend fun stations(
        @Query("appId") appId: String,
        @Header("Authorization") bearer: String,
        @Body request: PageRequest
    ): Response<StationListResponse>

    @Headers("Content-Type: application/json")
    @POST("station/v1.0/device?language=en")
    suspend fun stationDevices(
        @Query("appId") appId: String,
        @Header("Authorization") bearer: String,
        @Body request: StationDevicesRequest
    ): Response<StationDevicesResponse>
}

object SolarmanService {

    const val BASE_URL = "https://globalapi.solarmanpv.com/"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val api: SolarmanApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(http)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SolarmanApi::class.java)
    }
}
