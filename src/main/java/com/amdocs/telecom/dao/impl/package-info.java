/**
 * JDBC implementations of the DAO contracts.
 *
 * <p>All statements are parameterised, which satisfies the security
 * requirement for {@code PreparedStatement} use and rules out SQL injection.
 * Batch operations and savepoint aware transaction support also live here.</p>
 */
package com.amdocs.telecom.dao.impl;
