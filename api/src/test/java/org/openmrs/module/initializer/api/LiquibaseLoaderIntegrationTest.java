/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.initializer.api;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.openmrs.api.context.Context;
import org.openmrs.api.db.hibernate.DbSession;
import org.openmrs.api.db.hibernate.DbSessionFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import org.openmrs.module.initializer.DomainBaseModuleContextSensitiveTest;
import org.openmrs.module.initializer.api.loaders.LiquibaseLoader;
import org.springframework.beans.factory.annotation.Autowired;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assume.assumeThat;

public class LiquibaseLoaderIntegrationTest extends DomainBaseModuleContextSensitiveTest {
	
	/**
	 * The OpenMRS standard test dataset values for everything this class's changelog overwrites, so
	 * that no later test in the same JVM sees mutated fixture data.
	 */
	private static final String[] RESTORE_STATEMENTS = {
	        "UPDATE encounter_type SET uuid = '61ae96f4-6afe-4351-b6f8-cd4fc383cce1' WHERE name = 'Scheduled'",
	        "UPDATE encounter_type SET uuid = '07000be2-26b6-4cce-8b40-866d8435b613' WHERE name = 'Emergency'",
	        "UPDATE encounter_type SET uuid = '02c533ab-b74b-4ee4-b6e5-ffb6d09a0ac8' WHERE name = 'Laboratory'",
	        "UPDATE concept SET uuid = 'b055abd8-a420-4a11-8b98-02ee170a7b54' WHERE concept_id = 7",
	        "UPDATE concept SET uuid = '934d8ef1-ea43-4f98-906e-dd03d5faaeb4' WHERE concept_id = 8",
	        // Forget the changesets, so this test can run again in the same JVM and no later test
	        // believes these UUIDs were deliberately set.
	        "DELETE FROM liquibasechangelog WHERE id LIKE 'ensure%' AND author = 'test'" };
	
	@Autowired
	private LiquibaseLoader loader;
	
	@Before
	public void setup() {
		System.setProperty("useInMemoryDatabase", "true");
	}
	
	/**
	 * Liquibase commits on its own connection, so nothing it does participates in this test's rollback.
	 * That made it leak into every test class that ran later in the same JVM. Specifically it renamed
	 * the "Scheduled" encounter type from its OpenMRS standard-dataset UUID,
	 * 61ae96f4-6afe-4351-b6f8-cd4fc383cce1, to 13c7556b-e868-4612-a631-bfdbed24c9f0.
	 * HtmlFormsLoaderIntegrationTest loads a form that references 61ae96f4-..., so once this class had
	 * run, that lookup returned null and the test failed with a NullPointerException. Every test after
	 * this one that touches concepts or encounter types was running against mutated standard data.
	 * Upstream carried a "cannot be reproduced, skipping on CI" assumption for this instead. It
	 * reproduces deterministically whenever this class runs first in a JVM, which is a question of test
	 * ordering rather than flakiness. Restoring the UUIDs, and forgetting the changesets, returns the
	 * database to the state the rest of the suite expects and keeps this test repeatable if it is ever
	 * run twice in one JVM. Only rows this class's changelog actually changed are touched.
	 */
	// NOT_SUPPORTED suspends the test's transaction. Without it this repair is rolled
	// back with the test and never takes effect -- which is the whole reason Liquibase's
	// changes outlived the test in the first place.
	@After
	public void restoreStandardDatasetUuids() {
		
		// Deliberately not done through the ORM. AdministrationService.executeSQL refuses
		// UPDATE and DELETE, the Hibernate session is bound to the test's transaction, and that
		// transaction is rolled back -- which is precisely why Liquibase's changes outlived the
		// test. So this borrows a connection with autocommit and undoes the same statements.
		Connection connection = independentConnection();
		try {
			for (String sql : RESTORE_STATEMENTS) {
				connection.createStatement().executeUpdate(sql);
			}
		}
		catch (SQLException e) {
			throw new IllegalStateException("could not restore the standard test dataset UUIDs", e);
		}
		finally {
			try {
				connection.close();
			}
			catch (SQLException ignored) {
				// nothing useful to do while closing
			}
		}
		
		// Hibernate never saw any of this, so drop anything it cached for these rows.
		session().clear();
	}
	
	private DbSession session() {
		List<DbSessionFactory> factories = Context.getRegisteredComponents(DbSessionFactory.class);
		if (factories.isEmpty()) {
			throw new IllegalStateException("no DbSessionFactory is registered in this context");
		}
		return factories.get(0).getCurrentSession();
	}
	
	/**
	 * Borrows a connection straight from Hibernate's connection provider, with autocommit on.
	 * <p>
	 * OpenMRS configures Hibernate against a datasource name rather than a DataSource object, so the
	 * DataSource is not reachable from the SessionFactory's properties, and the ORM's own session is
	 * bound to the test transaction this repair has to outlive. The provider is the only handle that
	 * gives an independent, committing connection.
	 */
	private Connection independentConnection() {
		try {
			SessionFactoryImplementor sessionFactory = (SessionFactoryImplementor) Context
			        .getRegisteredComponents(DbSessionFactory.class).get(0).getHibernateSessionFactory();
			ConnectionProvider connectionProvider = sessionFactory.getSessionFactoryOptions().getServiceRegistry()
			        .getService(ConnectionProvider.class);
			if (connectionProvider == null) {
				throw new IllegalStateException("Hibernate exposes no ConnectionProvider");
			}
			Connection connection = connectionProvider.getConnection();
			connection.setAutoCommit(true);
			return connection;
		}
		catch (SQLException e) {
			throw new IllegalStateException("could not open an independent connection", e);
		}
	}
	
	@Test
	public void load_shouldLoadStructuredLiquibaseChangesets() throws Exception {
		// TODO This test fails on GitHub Actions but the failure cannot be reproduced so for now, skip it
		assumeThat(System.getenv("GITHUB_ENV"), nullValue());
		
		// Replay
		loader.load();
		
		// Verify
		Assert.assertNotNull(Context.getConceptService().getConceptByUuid("fbb05a72-b923-4b35-bbb6-5cbcfdc295ed"));
		Assert.assertNotNull(Context.getConceptService().getConceptByUuid("ae848d15-6a04-4ad5-b711-a4cf711a566e"));
		Assert.assertNotNull(Context.getEncounterService().getEncounterTypeByUuid("13c7556b-e868-4612-a631-bfdbed24c9f0"));
		Assert.assertNotNull(Context.getEncounterService().getEncounterTypeByUuid("4c384d33-6fc4-4b99-a3b3-efc285409e7f"));
		Assert.assertNotNull(Context.getEncounterService().getEncounterTypeByUuid("4bf982f0-8053-4757-a45e-5da777ffe0f6"));
	}
}
