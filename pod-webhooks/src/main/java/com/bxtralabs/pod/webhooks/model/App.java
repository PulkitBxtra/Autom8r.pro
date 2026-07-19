package com.bxtralabs.pod.webhooks.model;

import com.bxtralabs.pod.webhooks.common.IDs;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;

import java.util.List;

@Entity
public class App {

    @PrePersist
    public void prePersist() {
        if(id==null) {
            id = IDs.generateID("app");
        }
    }

    @Id
    public String id;
    public  String name;
    @OneToMany(targetEntity = AppAction.class)
    public List<AppAction> actions;
    @OneToMany(targetEntity = AppTrigger.class)
    public List<AppTrigger> triggers;

    public App() {
    }

    public App(String id, String name, List<AppAction> actions, List<AppTrigger> triggers) {
        this.id = id;
        this.name = name;
        this.actions = actions;
        this.triggers = triggers;
    }

    @Override
    public String toString() {
        return "App{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", actions=" + actions +
                ", triggers=" + triggers +
                '}';
    }
}
