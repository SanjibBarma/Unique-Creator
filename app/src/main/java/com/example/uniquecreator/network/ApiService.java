package com.example.uniquecreator.network;

import com.example.uniquecreator.model.ApiRequest;
import com.example.uniquecreator.model.ApiResponse;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;

public interface ApiService {

    @POST("exec")
    Call<ApiResponse> sendData(@Body ApiRequest request);
}