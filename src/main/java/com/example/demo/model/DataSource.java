package com.example.demo.model;

import java.util.HashSet;
import java.util.Set;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = "data_sources")
public class DataSource {

    @Id
    private String id;

    @Indexed(unique = true)
    private String name;

    private String description;

    @Field("supported_attributes")
    private Set<String> supportedAttributes = new HashSet<>();

    public DataSource() {
    }

    public DataSource(String id, String name, String description, Set<String> supportedAttributes) {
        this.id = id;
        this.name = name;
        this.description = description;
        setSupportedAttributes(supportedAttributes);
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Set<String> getSupportedAttributes() {
        return supportedAttributes;
    }

    public void setSupportedAttributes(Set<String> supportedAttributes) {
        this.supportedAttributes = supportedAttributes == null ? new HashSet<>() : new HashSet<>(supportedAttributes);
    }
}
