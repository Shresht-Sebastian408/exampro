package com.exampro.dao.impl;

import com.exampro.dao.TopicDao;
import com.exampro.exception.EntityNotFoundException;
import com.exampro.model.entity.Topic;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TopicDaoImpl extends AbstractJdbcDao<Topic, Long> implements TopicDao {

    private static final String INSERT = """
        INSERT INTO topics (subject_id, parent_id, name, display_order, is_active)
        VALUES (?, ?, ?, ?, ?)
        """;

    private static final String UPDATE = """
        UPDATE topics
           SET subject_id = ?, parent_id = ?, name = ?, display_order = ?, is_active = ?
         WHERE id = ?
        """;

    private static final String FIND_BY_SUBJECT = """
        SELECT * FROM topics
         WHERE subject_id = ?
         ORDER BY parent_id NULLS FIRST, display_order, name
        """;

    @Override
    protected String tableName() {
        return "topics";
    }

    @Override
    protected Topic mapRow(ResultSet rs) throws SQLException {
        Topic t = new Topic();
        t.setId(rs.getLong("id"));
        t.setSubjectId(rs.getLong("subject_id"));
        long parent = rs.getLong("parent_id");
        t.setParentId(rs.wasNull() ? null : parent);
        t.setName(rs.getString("name"));
        t.setDisplayOrder(rs.getInt("display_order"));
        t.setActive(rs.getBoolean("is_active"));
        return t;
    }

    @Override
    public List<Topic> findBySubject(long subjectId) {
        return queryList(FIND_BY_SUBJECT, ps -> ps.setLong(1, subjectId));
    }

    @Override
    public List<Topic> findTree(long subjectId) {
        Map<Long, Topic> byId = new LinkedHashMap<>();
        for (Topic t : findBySubject(subjectId)) {
            byId.put(t.getId(), t);
        }
        List<Topic> roots = new ArrayList<>();
        for (Topic t : byId.values()) {
            if (t.isRoot()) {
                roots.add(t);
            } else {
                Topic parent = byId.get(t.getParentId());
                // A child whose parent is inactive or in another subject is
                // promoted to a root rather than dropped, so nothing vanishes
                // from the tree view.
                (parent != null ? parent.getChildren() : roots).add(t);
            }
        }
        return roots;
    }

    @Override
    public Long insert(Topic topic) {
        long id = insertAndReturnKey(INSERT, ps -> {
            ps.setLong(1, topic.getSubjectId());
            if (topic.getParentId() == null) {
                ps.setNull(2, Types.BIGINT);
            } else {
                ps.setLong(2, topic.getParentId());
            }
            ps.setString(3, topic.getName());
            ps.setInt(4, topic.getDisplayOrder());
            ps.setBoolean(5, topic.isActive());
        });
        topic.setId(id);
        return id;
    }

    @Override
    public void update(Topic topic) {
        int rows = executeUpdate(UPDATE, ps -> {
            ps.setLong(1, topic.getSubjectId());
            if (topic.getParentId() == null) {
                ps.setNull(2, Types.BIGINT);
            } else {
                ps.setLong(2, topic.getParentId());
            }
            ps.setString(3, topic.getName());
            ps.setInt(4, topic.getDisplayOrder());
            ps.setBoolean(5, topic.isActive());
            ps.setLong(6, topic.getId());
        });
        if (rows == 0) {
            throw new EntityNotFoundException("No topic with id " + topic.getId());
        }
    }
}
