package com.exampro.dao.impl;

import com.exampro.config.ConnectionPool;
import com.exampro.dao.UserDao;
import com.exampro.exception.EntityNotFoundException;
import com.exampro.model.entity.User;
import com.exampro.model.enums.Role;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class UserDaoImpl extends AbstractJdbcDao<User, Long> implements UserDao {

    private static final String INSERT = """
        INSERT INTO users (username, password, full_name, email, is_active)
        VALUES (?, ?, ?, ?, ?)
        """;

    private static final String UPDATE = """
        UPDATE users
           SET username = ?, password = ?, full_name = ?, email = ?,
               is_active = ?, updated_at = CURRENT_TIMESTAMP
         WHERE id = ?
        """;

    /**
     * One query, one round trip, roles folded in. A per-user role lookup
     * would be an N+1 on every user list screen.
     */
    private static final String SELECT_WITH_ROLES = """
        SELECT u.*, r.name AS role_name
          FROM users u
          LEFT JOIN user_roles ur ON ur.user_id = u.id
          LEFT JOIN roles r       ON r.id = ur.role_id
        """;

    private static final String INSERT_ROLE = "INSERT INTO roles (name, description) VALUES (?, ?)";
    private static final String FIND_ROLE_ID = "SELECT id FROM roles WHERE name = ?";
    private static final String GRANT_ROLE =
        "MERGE INTO user_roles (user_id, role_id) KEY (user_id, role_id) VALUES (?, ?)";

    @Override
    protected String tableName() {
        return "users";
    }

    @Override
    protected User mapRow(ResultSet rs) throws SQLException {
        User u = new User();
        u.setId(rs.getLong("id"));
        u.setUsername(rs.getString("username"));
        u.setPassword(rs.getString("password"));
        u.setFullName(rs.getString("full_name"));
        u.setEmail(rs.getString("email"));
        u.setActive(rs.getBoolean("is_active"));
        if (rs.getTimestamp("created_at") != null) {
            u.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        }
        return u;
    }

    @Override
    public Optional<User> findById(Long id) {
        List<User> found = collect(SELECT_WITH_ROLES + " WHERE u.id = ?",
                                   ps -> ps.setLong(1, id));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public Optional<User> findByUsername(String username) {
        List<User> found = collect(SELECT_WITH_ROLES + " WHERE u.username = ?",
                                   ps -> ps.setString(1, username));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public List<User> findAll() {
        return collect(SELECT_WITH_ROLES + " ORDER BY u.id", ps -> { });
    }

    @Override
    public List<User> findByRole(Role role) {
        return collect(SELECT_WITH_ROLES + """
             WHERE u.id IN (SELECT ur2.user_id
                              FROM user_roles ur2
                              JOIN roles r2 ON r2.id = ur2.role_id
                             WHERE r2.name = ?)
             ORDER BY u.id
            """, ps -> ps.setString(1, role.name()));
    }

    @Override
    public Long insert(User user) {
        long id = insertAndReturnKey(INSERT, ps -> {
            ps.setString(1, user.getUsername());
            ps.setString(2, user.getPassword());
            ps.setString(3, user.getFullName());
            ps.setString(4, user.getEmail());
            ps.setBoolean(5, user.isActive());
        });
        user.setId(id);
        for (Role role : user.getRoles()) {
            grantRole(id, role);
        }
        return id;
    }

    @Override
    public void update(User user) {
        int rows = executeUpdate(UPDATE, ps -> {
            ps.setString(1, user.getUsername());
            ps.setString(2, user.getPassword());
            ps.setString(3, user.getFullName());
            ps.setString(4, user.getEmail());
            ps.setBoolean(5, user.isActive());
            ps.setLong(6, user.getId());
        });
        if (rows == 0) {
            throw new EntityNotFoundException("No user with id " + user.getId());
        }
    }

    @Override
    public void ensureRolesSeeded() {
        for (Role role : Role.values()) {
            if (roleId(role).isEmpty()) {
                executeUpdate(INSERT_ROLE, ps -> {
                    ps.setString(1, role.name());
                    ps.setString(2, role.name().charAt(0)
                                    + role.name().substring(1).toLowerCase() + " account");
                });
            }
        }
    }

    @Override
    public void grantRole(long userId, Role role) {
        long roleId = roleId(role).orElseThrow(() -> new EntityNotFoundException(
            "Role " + role + " missing from the roles table, call ensureRolesSeeded first"));
        executeUpdate(GRANT_ROLE, ps -> {
            ps.setLong(1, userId);
            ps.setLong(2, roleId);
        });
    }

    private Optional<Long> roleId(Role role) {
        try (Connection c = ConnectionPool.getConnection();
             PreparedStatement ps = c.prepareStatement(FIND_ROLE_ID)) {
            ps.setString(1, role.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate(e, FIND_ROLE_ID);
        }
    }

    /**
     * Folds a joined result set into distinct users. LinkedHashMap keeps the
     * SQL ordering, which the UI relies on.
     */
    private List<User> collect(String sql, Binder binder) {
        try (Connection c = ConnectionPool.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                Map<Long, User> byId = new LinkedHashMap<>();
                while (rs.next()) {
                    long id = rs.getLong("id");
                    User u = byId.get(id);
                    if (u == null) {
                        u = mapRow(rs);
                        byId.put(id, u);
                    }
                    String roleName = rs.getString("role_name");
                    if (roleName != null) {
                        u.getRoles().add(Role.valueOf(roleName));
                    }
                }
                return new ArrayList<>(byId.values());
            }
        } catch (SQLException e) {
            throw translate(e, sql);
        }
    }
}
