package com.bxtralabs.pod.workflow.model;

import com.bxtralabs.pod.workflow.common.IDs;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
public class Action {

    @PrePersist
    public void prePersist() {
        if(id == null) {
            id = IDs.generateID("act");
        }
    }


    @Id
    private String id;
    private  String name;
    private  String type;
    private  String appName;
    // Position of this action within its trigger's execution plan; consumers sort by this at runtime.
    private Integer sortingOrder;
    // Per-instance config for this action, e.g. connection id, channel, message template.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> parameters;

    public Action() {
    }

    public Action(String id, String name, String type, String appName) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.appName = appName;
    }

    public Action(String id, String name, String type, String appName, Integer sortingOrder) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.appName = appName;
        this.sortingOrder = sortingOrder;
    }

    public Action(String id, String name, String type, String appName, Integer sortingOrder, Map<String, Object> parameters) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.appName = appName;
        this.sortingOrder = sortingOrder;
        this.parameters = parameters;
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

    public Integer getSortingOrder() {
        return sortingOrder;
    }

    public void setSortingOrder(Integer sortingOrder) {
        this.sortingOrder = sortingOrder;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
    }

    @Override
    public String toString() {
        return "Action{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", type='" + type + '\'' +
                ", appName='" + appName + '\'' +
                ", sortingOrder=" + sortingOrder +
                ", parameters=" + parameters +
                '}';
    }
}
