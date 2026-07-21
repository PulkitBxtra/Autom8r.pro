package com.bxtralabs.pod.workflow.model;

import com.bxtralabs.pod.workflow.common.IDs;
import jakarta.persistence.*;

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
    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "app_id")
    public List<AppAction> actions;
    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "app_id")
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
