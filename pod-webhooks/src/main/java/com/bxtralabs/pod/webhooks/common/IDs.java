package com.bxtralabs.pod.webhooks.common;

import com.github.f4b6a3.ulid.UlidCreator;

public class IDs {

    public static String generateID(String prefix){
        return prefix + "_" + UlidCreator.getUlid().toLowerCase();
    }
}
