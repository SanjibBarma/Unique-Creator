package com.example.uniquecreator.repository;

import androidx.lifecycle.MutableLiveData;

import com.example.uniquecreator.model.ApiRequest;
import com.example.uniquecreator.model.ApiResponse;
import com.example.uniquecreator.network.ApiService;
import com.example.uniquecreator.network.RetrofitClient;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class UserCheckRepository {

    public MutableLiveData<String> sendData(ApiRequest request) {
        MutableLiveData<String> liveData = new MutableLiveData<>();

        ApiService apiService = RetrofitClient.getClient().create(ApiService.class);

        apiService.sendData(request).enqueue(new Callback<ApiResponse>() {
            @Override
            public void onResponse(Call<ApiResponse> call, Response<ApiResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    liveData.setValue(response.body().getStatus());
                } else {
                    liveData.setValue("error");
                }
            }

            @Override
            public void onFailure(Call<ApiResponse> call, Throwable t) {
                liveData.setValue("error");
            }
        });

        return liveData;
    }
}