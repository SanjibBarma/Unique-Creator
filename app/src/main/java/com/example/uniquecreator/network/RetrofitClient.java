package com.example.uniquecreator.network;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class RetrofitClient {

    private static Retrofit retrofit;

    public static Retrofit getClient() {
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)   // connection timeout
                .readTimeout(10, TimeUnit.SECONDS)      // response timeout
                .writeTimeout(10, TimeUnit.SECONDS)     // request timeout
                .build();

        if (retrofit == null) {
            retrofit = new Retrofit.Builder()
                    .baseUrl("https://script.google.com/macros/s/AKfycbwK1Akp9KTG6QZPRaATfhplxTABgunfgHQSqoRrxnTAEJ5CkHupaEukZJGSytm9uREb/")
                    .client(okHttpClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();
        }
        return retrofit;
    }
}