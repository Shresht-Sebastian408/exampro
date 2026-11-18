package com.exampro.model.entity;

import com.exampro.model.enums.Role;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * An account. {@code password} always holds a BCrypt hash, never a plaintext
 * password: nothing in this codebase stores or logs the raw value.
 */
public class User {

    private Long id;
    private String username;
    private String password;
    private String fullName;
    private String email;
    private boolean active = true;
    private LocalDateTime createdAt;
    private final Set<Role> roles = EnumSet.noneOf(Role.class);

    public User() {}

    public User(String username, String password, String fullName, String email, Role role) {
        this.username = username;
        this.password = password;
        this.fullName = fullName;
        this.email = email;
        this.roles.add(role);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public Set<Role> getRoles() { return roles; }

    public boolean hasRole(Role role) { return roles.contains(role); }

    /** Convenience for the UI, which shows one primary role per user. */
    public Role primaryRole() {
        return roles.stream().findFirst().orElse(Role.STUDENT);
    }

    @Override
    public String toString() {
        return username + " (" + fullName + ")";
    }
}
