package com.syncwatch.network

import android.content.Context
import com.syncwatch.BuildConfig
import com.syncwatch.util.DataMeter
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    lateinit var api: ApiService
        private set

    fun init(context: Context) {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG)
                HttpLoggingInterceptor.Level.BODY
            else
                HttpLoggingInterceptor.Level.NONE
        }

        val okHttp = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)         // longer for uploads
            .addInterceptor(logging)
            .addInterceptor(DataMeter.httpInterceptor)  // tracks bytes in/out
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BuildConfig.SERVER_URL.trimEnd('/') + "/")
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        api = retrofit.create(ApiService::class.java)
    }
}
