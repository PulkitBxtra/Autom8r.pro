package com.bxtralabs.pod.webhooks.service;

import com.bxtralabs.pod.webhooks.model.App;
import com.bxtralabs.pod.webhooks.repository.AppRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AppService {

    @Autowired
    private AppRepository appRepository;

    // Generic: persist whatever App (with its triggers/actions) is passed in.
    public App createApp(App app) {
        return appRepository.save(app);
    }
}
