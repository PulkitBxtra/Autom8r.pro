package com.bxtralabs.pod.webhooks.model;

import com.bxtralabs.pod.webhooks.common.IDs;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;

@Entity
public class AppAction {

    @PrePersist
    public void prePersist() {
        if(id == null) {
            id = IDs.generateID("aac");
        }
    }

    @Id
    private String id;
    private  String name;
    private  String type;
    private  String appName;

    public AppAction() {
    }

    public AppAction(String id, String name, String type, String appName) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.appName = appName;
    }

    @Override
    public String toString() {
        return "AppAction{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", type='" + type + '\'' +
                ", appName='" + appName + '\'' +
                '}';
    }
}
