/*
 * Copyright 2011-2013 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.cassandra.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.support.PersistenceExceptionTranslator;
import org.springframework.data.cassandra.convert.CassandraConverter;
import org.springframework.data.cassandra.core.exceptions.CassandraConnectionFailureException;
import org.springframework.data.cassandra.exception.EntityWriterException;
import org.springframework.data.cassandra.mapping.CassandraPersistentEntity;
import org.springframework.data.cassandra.mapping.CassandraPersistentProperty;
import org.springframework.data.cassandra.util.CqlUtils;
import org.springframework.data.convert.EntityReader;
import org.springframework.data.mapping.context.MappingContext;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

import com.datastax.driver.core.Host;
import com.datastax.driver.core.Metadata;
import com.datastax.driver.core.Query;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.NoHostAvailableException;
import com.datastax.driver.core.querybuilder.Batch;

/**
 * The Cassandra Template is a convenience API for all Cassnadta DML Operations.
 * 
 * @author Alex Shvid
 * @author David Webb
 */
public class CassandraTemplate implements CassandraOperations {

	/**
	 * Simple {@link RowCallback} that will transform {@link Row} into the given target type using the given
	 * {@link EntityReader}.
	 * 
	 * @author Alex Shvid
	 */
	private static class ReadRowCallback<T> implements RowCallback<T> {

		private final EntityReader<? super T, Row> reader;
		private final Class<T> type;

		public ReadRowCallback(EntityReader<? super T, Row> reader, Class<T> type) {
			Assert.notNull(reader);
			Assert.notNull(type);
			this.reader = reader;
			this.type = type;
		}

		@Override
		public T doWith(Row object) {
			T source = reader.read(type, object);
			return source;
		}
	}

	private static Logger log = LoggerFactory.getLogger(CassandraTemplate.class);
	public static final Collection<String> ITERABLE_CLASSES;

	static {

		Set<String> iterableClasses = new HashSet<String>();
		iterableClasses.add(List.class.getName());
		iterableClasses.add(Collection.class.getName());
		iterableClasses.add(Iterator.class.getName());

		ITERABLE_CLASSES = Collections.unmodifiableCollection(iterableClasses);

	}
	private final Keyspace keyspace;
	private final Session session;
	private final CassandraConverter cassandraConverter;
	private final MappingContext<? extends CassandraPersistentEntity<?>, CassandraPersistentProperty> mappingContext;
	private final PersistenceExceptionTranslator exceptionTranslator = new CassandraExceptionTranslator();

	private ClassLoader beanClassLoader;

	/**
	 * Constructor used for a basic template configuration
	 * 
	 * @param keyspace must not be {@literal null}.
	 */
	public CassandraTemplate(Keyspace keyspace) {
		this.keyspace = keyspace;
		this.session = keyspace.getSession();
		this.cassandraConverter = keyspace.getCassandraConverter();
		this.mappingContext = this.cassandraConverter.getMappingContext();
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#delete(java.util.List)
	 */
	@Override
	public <T> void delete(List<T> entities) {
		String tableName = getTableName(entities.get(0).getClass());
		Assert.notNull(tableName);
		delete(entities, tableName);
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#delete(java.util.List, java.lang.String)
	 */
	@Override
	public <T> void delete(List<T> entities, String tableName) {
		delete(entities, tableName, new HashMap<String, Object>());
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#delete(java.util.List, java.lang.String, java.util.Map)
	 */
	@Override
	public <T> void delete(List<T> entities, String tableName, Map<String, Object> optionsByName) {
		Assert.notNull(entities);
		Assert.notEmpty(entities);
		Assert.notNull(tableName);
		Assert.notNull(optionsByName);
		doBatchDelete(tableName, entities, optionsByName, false);
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#delete(java.util.List, java.lang.String, org.springframework.data.cassandra.core.QueryOptions)
	 */
	@Override
	public <T> void delete(List<T> entities, String tableName, QueryOptions options) {
		delete(entities, tableName, options.toMap());
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#delete(java.lang.Object)
	 */
	@Override
	public <T> void delete(T entity) {
		String tableName = getTableName(entity.getClass());
		Assert.notNull(tableName);
		delete(entity, tableName);
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#delete(java.lang.Object, java.lang.String)
	 */
	@Override
	public <T> void delete(T entity, String tableName) {
		delete(entity, tableName, new HashMap<String, Object>());
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#delete(java.lang.Object, java.lang.String, java.util.Map)
	 */
	@Override
	public <T> void delete(T entity, String tableName, Map<String, Object> optionsByName) {
		Assert.notNull(entity);
		Assert.notNull(tableName);
		Assert.notNull(optionsByName);
		doDelete(tableName, entity, optionsByName, false);
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#delete(java.lang.Object, java.lang.String, org.springframework.data.cassandra.core.QueryOptions)
	 */
	@Override
	public <T> void delete(T entity, String tableName, QueryOptions options) {
		delete(entity, tableName, options.toMap());
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#deleteAsychronously(java.util.List)
	 */
	@Override
	public <T> void deleteAsychronously(List<T> entities) {
		String tableName = getTableName(entities.get(0).getClass());
		Assert.notNull(tableName);
		deleteAsychronously(entities, tableName);
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#deleteAsychronously(java.util.List, java.lang.String)
	 */
	@Override
	public <T> void deleteAsychronously(List<T> entities, String tableName) {
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#deleteAsychronously(java.util.List, java.lang.String, java.util.Map)
	 */
	@Override
	public <T> void deleteAsychronously(List<T> entities, String tableName, Map<String, Object> optionsByName) {
		Assert.notNull(entities);
		Assert.notEmpty(entities);
		Assert.notNull(tableName);
		Assert.notNull(optionsByName);
		doBatchDelete(tableName, entities, optionsByName, true);
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#deleteAsychronously(java.util.List, java.lang.String, org.springframework.data.cassandra.core.QueryOptions)
	 */
	@Override
	public <T> void deleteAsychronously(List<T> entities, String tableName, QueryOptions options) {
		// TODO Auto-generated method stub

	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#deleteAsychronously(java.lang.Object)
	 */
	@Override
	public <T> void deleteAsychronously(T entity) {
		String tableName = getTableName(entity.getClass());
		Assert.notNull(tableName);
		deleteAsychronously(entity, tableName);
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#deleteAsychronously(java.lang.Object, java.lang.String)
	 */
	@Override
	public <T> void deleteAsychronously(T entity, String tableName) {
		deleteAsychronously(entity, tableName, new HashMap<String, Object>());
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#deleteAsychronously(java.lang.Object, java.lang.String, java.util.Map)
	 */
	@Override
	public <T> void deleteAsychronously(T entity, String tableName, Map<String, Object> optionsByName) {
		Assert.notNull(entity);
		Assert.notNull(tableName);
		Assert.notNull(optionsByName);
		doDelete(tableName, entity, optionsByName, true);
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#deleteAsychronously(java.lang.Object, java.lang.String, org.springframework.data.cassandra.core.QueryOptions)
	 */
	@Override
	public <T> void deleteAsychronously(T entity, String tableName, QueryOptions options) {
		deleteAsychronously(entity, tableName, options.toMap());
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#describeRing()
	 */
	@Override
	public List<RingMember> describeRing() {

		/*
		 * Initialize the return variable
		 */
		List<RingMember> ring = new ArrayList<RingMember>();

		/*
		 * Get the cluster metadata for this session
		 */
		Metadata clusterMetadata = execute(new SessionCallback<Metadata>() {

			@Override
			public Metadata doInSession(Session s) throws DataAccessException {
				return s.getCluster().getMetadata();
			}

		});

		/*
		 * Get all hosts in the cluster
		 */
		Set<Host> hosts = clusterMetadata.getAllHosts();

		/*
		 * Loop variables
		 */
		RingMember member = null;

		/*
		 * Populate Ring with Host Metadata
		 */
		for (Host h : hosts) {

			member = new RingMember(h);
			ring.add(member);
		}

		/*
		 * Return
		 */
		return ring;

	}

	/**
	 * @param entityClass
	 * @return
	 */
	public String determineTableName(Class<?> entityClass) {

		if (entityClass == null) {
			throw new InvalidDataAccessApiUsageException(
					"No class parameter provided, entity table name can't be determined!");
		}

		CassandraPersistentEntity<?> entity = mappingContext.getPersistentEntity(entityClass);
		if (entity == null) {
			throw new InvalidDataAccessApiUsageException("No Persitent Entity information found for the class "
					+ entityClass.getName());
		}
		return entity.getTable();
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#executeQuery(java.lang.String)
	 */
	@Override
	public ResultSet executeQuery(final String query) {

		return execute(new SessionCallback<ResultSet>() {

			@Override
			public ResultSet doInSession(Session s) throws DataAccessException {

				return s.execute(query);

			}

		});

	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#executeQueryAsync(java.lang.String)
	 */
	@Override
	public ResultSetFuture executeQueryAsynchronously(final String query) {

		return execute(new SessionCallback<ResultSetFuture>() {

			@Override
			public ResultSetFuture doInSession(Session s) throws DataAccessException {

				return s.executeAsync(query);

			}

		});

	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#getConverter()
	 */
	@Override
	public CassandraConverter getConverter() {
		return cassandraConverter;
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#getTableName(java.lang.Class)
	 */
	@Override
	public String getTableName(Class<?> entityClass) {
		return determineTableName(entityClass);
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#insert(java.util.List)
	 */
	@Override
	public <T> List<T> insert(List<T> entities) {
		String tableName = getTableName(entities.get(0).getClass());
		Assert.notNull(tableName);
		return insert(entities, tableName);
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#insert(java.util.List, java.lang.String)
	 */
	@Override
	public <T> List<T> insert(List<T> entities, String tableName) {
		return insert(entities, tableName, new HashMap<String, Object>());
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#insert(java.util.List, java.lang.String, java.util.Map)
	 */
	@Override
	public <T> List<T> insert(List<T> entities, String tableName, Map<String, Object> optionsByName) {
		Assert.notNull(entities);
		Assert.notEmpty(entities);
		Assert.notNull(tableName);
		Assert.notNull(optionsByName);
		return doBatchInsert(tableName, entities, optionsByName, false);
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#insert(java.util.List, java.lang.String, org.springframework.data.cassandra.core.QueryOptions)
	 */
	@Override
	public <T> List<T> insert(List<T> entities, String tableName, QueryOptions options) {
		return insert(entities, tableName, options.toMap());
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#insert(java.lang.Object)
	 */
	@Override
	public <T> T insert(T entity) {
		String tableName = determineTableName(entity);
		Assert.notNull(tableName);
		return insert(entity, tableName);
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#insert(java.lang.Object, java.lang.String)
	 */
	@Override
	public <T> T insert(T entity, String tableName) {
		return insert(entity, tableName, new HashMap<String, Object>());
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#insert(java.lang.Object, java.lang.String, java.util.Map)
	 */
	@Override
	public <T> T insert(T entity, String tableName, Map<String, Object> optionsByName) {
		Assert.notNull(entity);
		Assert.notNull(tableName);
		ensureNotIterable(entity);
		return doInsert(tableName, entity, optionsByName, false);
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#insert(java.lang.Object, java.lang.String, org.springframework.data.cassandra.core.QueryOptions)
	 */
	@Override
	public <T> T insert(T entity, String tableName, QueryOptions options) {
		return insert(entity, tableName, options.toMap());
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#insertAsynchronously(java.util.List)
	 */
	@Override
	public <T> List<T> insertAsynchronously(List<T> entities) {
		String tableName = getTableName(entities.get(0).getClass());
		Assert.notNull(tableName);
		return insertAsynchronously(entities, tableName);
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#insertAsynchronously(java.util.List, java.lang.String)
	 */
	@Override
	public <T> List<T> insertAsynchronously(List<T> entities, String tableName) {
		return insertAsynchronously(entities, tableName, new HashMap<String, Object>());
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#insertAsynchronously(java.util.List, java.lang.String, java.util.Map)
	 */
	@Override
	public <T> List<T> insertAsynchronously(List<T> entities, String tableName, Map<String, Object> optionsByName) {
		Assert.notNull(entities);
		Assert.notEmpty(entities);
		Assert.notNull(tableName);
		Assert.notNull(optionsByName);
		return doBatchInsert(tableName, entities, optionsByName, true);
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#insertAsynchronously(java.util.List, java.lang.String, org.springframework.data.cassandra.core.QueryOptions)
	 */
	@Override
	public <T> List<T> insertAsynchronously(List<T> entities, String tableName, QueryOptions options) {
		return insertAsynchronously(entities, tableName, options.toMap());
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#insertAsynchronously(java.lang.Object)
	 */
	@Override
	public <T> T insertAsynchronously(T entity) {
		String tableName = determineTableName(entity);
		Assert.notNull(tableName);
		return insertAsynchronously(entity, tableName);
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#insertAsynchronously(java.lang.Object, java.lang.String)
	 */
	@Override
	public <T> T insertAsynchronously(T entity, String tableName) {
		return insertAsynchronously(entity, tableName, new HashMap<String, Object>());
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#insertAsynchronously(java.lang.Object, java.lang.String, java.util.Map)
	 */
	@Override
	public <T> T insertAsynchronously(T entity, String tableName, Map<String, Object> optionsByName) {
		Assert.notNull(entity);
		Assert.notNull(tableName);
		Assert.notNull(optionsByName);

		ensureNotIterable(entity);

		return doInsert(tableName, entity, optionsByName, true);
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#insertAsynchronously(java.lang.Object, java.lang.String, org.springframework.data.cassandra.core.QueryOptions)
	 */
	@Override
	public <T> T insertAsynchronously(T entity, String tableName, QueryOptions options) {
		return insertAsynchronously(entity, tableName, options.toMap());
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#select(java.lang.String, java.lang.Class)
	 */
	@Override
	public <T> List<T> select(String query, Class<T> selectClass) {
		return selectInternal(query, new ReadRowCallback<T>(cassandraConverter, selectClass));
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#selectOne(java.lang.String, java.lang.Class)
	 */
	@Override
	public <T> T selectOne(String query, Class<T> selectClass) {
		return selectOneInternal(query, new ReadRowCallback<T>(cassandraConverter, selectClass));
	}

	/**
	 * @param classLoader
	 */
	public void setBeanClassLoader(ClassLoader classLoader) {
		this.beanClassLoader = classLoader;
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#update(java.util.List)
	 */
	@Override
	public <T> List<T> update(List<T> entities) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#update(java.util.List, java.lang.String)
	 */
	@Override
	public <T> List<T> update(List<T> entities, String tableName) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#update(java.util.List, java.lang.String, java.util.Map)
	 */
	@Override
	public <T> List<T> update(List<T> entities, String tableName, Map<String, Object> optionsByName) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#update(java.util.List, java.lang.String, org.springframework.data.cassandra.core.QueryOptions)
	 */
	@Override
	public <T> List<T> update(List<T> entities, String tableName, QueryOptions options) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#update(java.lang.Object)
	 */
	@Override
	public <T> T update(T entity) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#update(java.lang.Object, java.lang.String)
	 */
	@Override
	public <T> T update(T entity, String tableName) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#update(java.lang.Object, java.lang.String, java.util.Map)
	 */
	@Override
	public <T> T update(T entity, String tableName, Map<String, Object> optionsByName) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#update(java.lang.Object, java.lang.String, org.springframework.data.cassandra.core.QueryOptions)
	 */
	@Override
	public <T> T update(T entity, String tableName, QueryOptions options) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#updateAsynchronously(java.util.List)
	 */
	@Override
	public <T> List<T> updateAsynchronously(List<T> entities) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#updateAsynchronously(java.util.List, java.lang.String)
	 */
	@Override
	public <T> List<T> updateAsynchronously(List<T> entities, String tableName) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#updateAsynchronously(java.util.List, java.lang.String, java.util.Map)
	 */
	@Override
	public <T> List<T> updateAsynchronously(List<T> entities, String tableName, Map<String, Object> optionsByName) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#updateAsynchronously(java.util.List, java.lang.String, org.springframework.data.cassandra.core.QueryOptions)
	 */
	@Override
	public <T> List<T> updateAsynchronously(List<T> entities, String tableName, QueryOptions options) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#updateAsynchronously(java.lang.Object)
	 */
	@Override
	public <T> T updateAsynchronously(T entity) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#updateAsynchronously(java.lang.Object, java.lang.String)
	 */
	@Override
	public <T> T updateAsynchronously(T entity, String tableName) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#updateAsynchronously(java.lang.Object, java.lang.String, java.util.Map)
	 */
	@Override
	public <T> T updateAsynchronously(T entity, String tableName, Map<String, Object> optionsByName) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.CassandraOperations#updateAsynchronously(java.lang.Object, java.lang.String, org.springframework.data.cassandra.core.QueryOptions)
	 */
	@Override
	public <T> T updateAsynchronously(T entity, String tableName, QueryOptions options) {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * @param obj
	 * @return
	 */
	private <T> String determineTableName(T obj) {
		if (null != obj) {
			return determineTableName(obj.getClass());
		}

		return null;
	}

	private RuntimeException potentiallyConvertRuntimeException(RuntimeException ex) {
		RuntimeException resolved = this.exceptionTranslator.translateExceptionIfPossible(ex);
		return resolved == null ? ex : resolved;
	}

	/**
	 * Perform the deletion on a list of objects
	 * 
	 * @param tableName
	 * @param objectToRemove
	 */
	protected <T> void doBatchDelete(final String tableName, final List<T> entities, Map<String, Object> optionsByName,
			final boolean deleteAsynchronously) {

		Assert.notEmpty(entities);

		CassandraPersistentEntity<?> CPEntity = getEntity(entities.get(0));

		Assert.notNull(CPEntity);

		try {

			final Batch b = CqlUtils.toDeleteBatchQuery(keyspace.getKeyspace(), tableName, entities, CPEntity, optionsByName);
			log.info(b.toString());

			execute(new SessionCallback<Object>() {

				@Override
				public Object doInSession(Session s) throws DataAccessException {

					if (deleteAsynchronously) {
						s.executeAsync(b);
					} else {
						s.execute(b);
					}

					return null;

				}
			});

		} catch (EntityWriterException e) {
			throw exceptionTranslator.translateExceptionIfPossible(new RuntimeException(
					"Failed to translate Object to Query", e));
		}
	}

	/**
	 * Insert a row into a Cassandra CQL Table
	 * 
	 * @param tableName
	 * @param entity
	 */
	protected <T> List<T> doBatchInsert(final String tableName, final List<T> entities,
			Map<String, Object> optionsByName, final boolean insertAsychronously) {

		Assert.notEmpty(entities);

		CassandraPersistentEntity<?> CPEntity = getEntity(entities.get(0));

		Assert.notNull(CPEntity);

		try {

			final Batch b = CqlUtils.toInsertBatchQuery(keyspace.getKeyspace(), tableName, entities, CPEntity, optionsByName);
			log.info(b.toString());

			return execute(new SessionCallback<List<T>>() {

				@Override
				public List<T> doInSession(Session s) throws DataAccessException {

					if (insertAsychronously) {
						s.executeAsync(b);
					} else {
						s.execute(b);
					}

					return entities;

				}
			});

		} catch (EntityWriterException e) {
			throw exceptionTranslator.translateExceptionIfPossible(new RuntimeException(
					"Failed to translate Object to Query", e));
		}
	}

	/**
	 * Perform the removal of a Row.
	 * 
	 * @param tableName
	 * @param objectToRemove
	 */
	protected <T> void doDelete(final String tableName, final T objectToRemove, Map<String, Object> optionsByName,
			final boolean deleteAsynchronously) {

		CassandraPersistentEntity<?> entity = getEntity(objectToRemove);

		Assert.notNull(entity);

		try {

			final Query q = CqlUtils.toDeleteQuery(keyspace.getKeyspace(), tableName, objectToRemove, entity, optionsByName);
			log.info(q.toString());

			execute(new SessionCallback<Object>() {

				@Override
				public Object doInSession(Session s) throws DataAccessException {

					if (deleteAsynchronously) {
						s.executeAsync(q);
					} else {
						s.execute(q);
					}

					return null;

				}
			});

		} catch (EntityWriterException e) {
			throw exceptionTranslator.translateExceptionIfPossible(new RuntimeException(
					"Failed to translate Object to Query", e));
		}
	}

	/**
	 * Insert a row into a Cassandra CQL Table
	 * 
	 * @param tableName
	 * @param entity
	 */
	protected <T> T doInsert(final String tableName, final T entity, final Map<String, Object> optionsByName,
			final boolean insertAsychronously) {

		CassandraPersistentEntity<?> CPEntity = getEntity(entity);

		Assert.notNull(CPEntity);

		try {

			final Query q = CqlUtils.toInsertQuery(keyspace.getKeyspace(), tableName, entity, CPEntity, optionsByName);
			log.info(q.toString());

			return execute(new SessionCallback<T>() {

				@Override
				public T doInSession(Session s) throws DataAccessException {

					if (insertAsychronously) {
						s.executeAsync(q);
					} else {
						s.execute(q);
					}

					return entity;

				}
			});

		} catch (EntityWriterException e) {
			throw exceptionTranslator.translateExceptionIfPossible(new RuntimeException(
					"Failed to translate Object to Query", e));
		}

	}

	/**
	 * Verify the object is not an iterable type
	 * 
	 * @param o
	 */
	protected void ensureNotIterable(Object o) {
		if (null != o) {
			if (o.getClass().isArray() || ITERABLE_CLASSES.contains(o.getClass().getName())) {
				throw new IllegalArgumentException("Cannot use a collection here.");
			}
		}
	}

	/**
	 * Execute a command at the Session Level
	 * 
	 * @param callback
	 * @return
	 */
	protected <T> T execute(SessionCallback<T> callback) {

		Assert.notNull(callback);

		try {

			return callback.doInSession(session);

		} catch (DataAccessException e) {
			throw potentiallyConvertRuntimeException(e);
		}
	}

	/**
	 * Determines the PersistentEntityType for a given Object
	 * 
	 * @param o
	 * @return
	 */
	protected CassandraPersistentEntity<?> getEntity(Object o) {

		CassandraPersistentEntity<?> entity = null;
		try {
			String entityClassName = o.getClass().getName();
			Class<?> entityClass = ClassUtils.forName(entityClassName, beanClassLoader);
			entity = mappingContext.getPersistentEntity(entityClass);
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		} catch (LinkageError e) {
			e.printStackTrace();
		} finally {
		}

		return entity;

	}

	/**
	 * @param query
	 * @param readRowCallback
	 * @return
	 */
	<T> List<T> selectInternal(String query, ReadRowCallback<T> readRowCallback) {
		try {
			ResultSet resultSet = session.execute(query);
			List<T> result = new ArrayList<T>();
			Iterator<Row> iterator = resultSet.iterator();
			while (iterator.hasNext()) {
				Row row = iterator.next();
				result.add(readRowCallback.doWith(row));
			}
			return result;
		} catch (NoHostAvailableException e) {
			throw new CassandraConnectionFailureException(null, "no host available", e);
		} catch (RuntimeException e) {
			throw potentiallyConvertRuntimeException(e);
		}
	}

	/**
	 * @param query
	 * @param readRowCallback
	 * @return
	 */
	<T> T selectOneInternal(String query, ReadRowCallback<T> readRowCallback) {
		try {
			ResultSet resultSet = session.execute(query);
			Iterator<Row> iterator = resultSet.iterator();
			if (iterator.hasNext()) {
				Row row = iterator.next();
				T result = readRowCallback.doWith(row);
				if (iterator.hasNext()) {
					throw new DuplicateKeyException("found two or more results in query " + query);
				}
				return result;
			}
			return null;
		} catch (NoHostAvailableException e) {
			throw new CassandraConnectionFailureException(null, "no host available", e);
		} catch (RuntimeException e) {
			throw potentiallyConvertRuntimeException(e);
		}
	}

}
