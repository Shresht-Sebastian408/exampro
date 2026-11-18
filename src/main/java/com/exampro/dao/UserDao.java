package com.exampro.dao;

import com.exampro.model.entity.User;
import com.exampro.model.enums.Role;

import java.util.List;
import java.util.Optional;

public interface UserDao extends GenericDao<User, Long> {

    Optional<User> findByUsername(String username);

    List<User> findByRole(Role role);

    /** Ensures the roles lookup table holds a row per enum constant. */
    void ensureRolesSeeded();

    void grantRole(long userId, Role role);
}
