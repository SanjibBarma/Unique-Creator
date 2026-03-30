package com.example.uniquecreator.viewModel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;

import com.example.uniquecreator.model.ApiRequest;
import com.example.uniquecreator.repository.UserCheckRepository;

public class UserCheckViewModel extends ViewModel {

    private UserCheckRepository repository = new UserCheckRepository();

    public LiveData<String> sendData(ApiRequest request) {
        return repository.sendData(request);
    }
}