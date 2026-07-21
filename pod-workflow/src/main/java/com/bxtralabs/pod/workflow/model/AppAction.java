package com.bxtralabs.pod.workflow.model;

import com.bxtralabs.pod.workflow.common.IDs;
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

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
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
