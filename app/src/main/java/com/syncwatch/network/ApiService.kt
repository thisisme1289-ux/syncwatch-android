package com.syncwatch.network

import com.syncwatch.model.CreateRoomRequest
import com.syncwatch.model.CreateRoomResponse
import com.syncwatch.model.RoomLookupResponse
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

interface ApiService {

    /**
     * Creates a new room. Returns roomId, join code, and hostToken.
     * The hostToken must be kept in memory only — never persisted.
     */
    @POST("api/rooms")
    suspend fun createRoom(@Body request: CreateRoomRequest): Response<CreateRoomResponse>

    /**
     * Looks up a room by its short join code or full UUID.
     * Used on the Home screen to validate a code before connecting the socket.
     */
    @GET("api/rooms/{identifier}")
    suspend fun getRoom(@Path("identifier") identifier: String): Response<RoomLookupResponse>

    /**
     * Host uploads a video file. Server stores it and later emits `media_ready`
     * with a streaming URL. Response body is ignored — we wait for the socket event.
     */
    @Multipart
    @POST("api/upload")
    suspend fun uploadVideo(
        @Part roomId: MultipartBody.Part,
        @Part file: MultipartBody.Part
    ): Response<ResponseBody>
}
