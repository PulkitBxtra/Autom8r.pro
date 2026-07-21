package com.bxtralabs.pod.workflow.model;

import com.bxtralabs.pod.workflow.common.IDs;
import jakarta.persistence.*;

@Entity
public class Trigger {

    @PrePersist
    public void prePersist() {
        if(id==null){
            id = IDs.generateID("trg");
        }
    }

    @Id
    private String id;
    private  String name;
    @ManyToOne
    @JoinColumn(name = "app_trigger_id")
    private AppTrigger type;
    private String AppName;

    public Trigger() {
    }



    public Trigger(String id, String name, AppTrigger type, String appName) {
        this.id = id;
        this.name = name;
        this.type = type;
        AppName = appName;
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

    public AppTrigger getType() {
        return type;
    }

    public void setType(AppTrigger type) {
        this.type = type;
    }

    public String getAppName() {
        return AppName;
    }

    public void setAppName(String appName) {
        AppName = appName;
    }

    @Override
    public String toString() {
        return "Trigger{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", type='" + type + '\'' +
                ", AppName='" + AppName + '\'' +
                '}';
    }
}
