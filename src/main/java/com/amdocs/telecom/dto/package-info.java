/**
 * Read only projections assembled for dashboards and reports.
 *
 * <p>These carry joined or aggregated shapes that do not correspond to a
 * single table, such as engineer workload summaries and SLA compliance rows,
 * keeping reporting concerns out of the entity model.</p>
 *
 * <p>Each class mirrors one of the views or stored procedure result sets
 * created in the database scripts, so the columns a query returns and the
 * fields a screen reads stay in step. They implement {@code Displayable} but
 * not {@code Identifiable}: a projection is something to print, not something
 * to save.</p>
 */
package com.amdocs.telecom.dto;
