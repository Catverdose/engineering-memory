package com.engineeringmemory.common.repository;

import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

@NoRepositoryBean
public interface OwnerScopedRepository<T, ID> extends Repository<T, ID> {

	<S extends T> S save(S entity);

	<S extends T> S saveAndFlush(S entity);

	void delete(T entity);

	void flush();
}
