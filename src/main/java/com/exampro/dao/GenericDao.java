package com.exampro.dao;

import com.exampro.exception.DataAccessException;

import java.util.List;
import java.util.Optional;

/**
 * The five operations every entity needs, typed on the entity and its key.
 *
 * Every DAO in the project implements this, so a service can depend on
 * {@code GenericDao<Question, Long>} without knowing which implementation
 * it got. That is what makes the DAOs mockable in tests.
 */
public interface GenericDao<T, ID> {

    Optional<T> findById(ID id) throws DataAccessException;

    List<T> findAll() throws DataAccessException;

    ID insert(T entity) throws DataAccessException;

    void update(T entity) throws DataAccessException;

    void delete(ID id) throws DataAccessException;

    long count() throws DataAccessException;
}
