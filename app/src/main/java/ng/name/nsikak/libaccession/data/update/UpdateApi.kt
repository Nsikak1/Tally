package ng.name.nsikak.libaccession.data.update

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Streaming
import retrofit2.http.Url

interface UpdateApi {
    @GET("api/v1/app/check-update")
    suspend fun checkUpdate(): UpdateInfoResponse

    @Streaming
    @GET
    suspend fun downloadFile(@Url fileUrl: String): ResponseBody
}
