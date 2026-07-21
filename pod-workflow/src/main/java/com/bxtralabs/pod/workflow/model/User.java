package com.bxtralabs.pod.workflow.model;

import com.bxtralabs.pod.workflow.common.IDs;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;

@Entity
public class User {

    @PrePersist
    public void assignId() {
        if(id == null) {
            id = IDs.generateID("usr");
        }
    }

    @Id
    private String id;
    private String name;
    @Column(unique = true, nullable = false)
    private String email;
    private String password;

    public User() {
    }

    public User(String id, String name, String email, String password) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.password = password;
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

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    @Override
    public String toString() {
        return "User{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", email='" + email + '\'' +
                '}';
    }
}
