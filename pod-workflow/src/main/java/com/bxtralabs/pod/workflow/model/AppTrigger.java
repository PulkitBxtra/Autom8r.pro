package com.bxtralabs.pod.workflow.model;

import com.bxtralabs.pod.workflow.common.IDs;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;

@Entity
public class AppTrigger {

    @PrePersist
    public void prePersist() {
        if(id==null){
            id = IDs.generateID("app");
        }
    }

    @Id
    private String id;
    private String name;
    private String appName;

    public AppTrigger() {
    }

    public AppTrigger(String id, String name, String appName) {
        this.id = id;
        this.name = name;
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

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    @Override
    public String toString() {
        return "AppTrigger{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", appName='" + appName + '\'' +
                '}';
    }
}
