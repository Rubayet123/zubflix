package com.example.netflix.network

import com.example.netflix.model.Post
import com.example.netflix.model.CircleFtpResponse
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface CircleFtpApi {
    @GET("posts")
    suspend fun fetchPosts(
        @Query("category") categoryId: String? = null,
        @Query("search") searchTerm: String? = null,
        @Query("page") page: Int? = null,
        @Query("limit") limit: Int? = null
    ): Response<CircleFtpResponse>

    @GET("posts/{id}")
    suspend fun fetchPostDetails(
        @Path("id") id: Int
    ): Response<Post>
}

object RetrofitClient {
    const val IMAGE_BASE_URL = "http://172.16.50.4/"
    private const val BASE_URL = "http://172.16.50.4/api/"

    val instance: CircleFtpApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CircleFtpApi::class.java)
    }
}
