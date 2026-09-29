/**
 * The SLA engine of section 8.
 *
 * <p>{@link com.amdocs.telecom.service.sla.SlaClock} and its two
 * implementations are the Strategy pattern: each is one rule for deciding
 * which minutes of a window actually count, and a priority band is
 * configured to use one of them.
 * {@link com.amdocs.telecom.service.sla.SlaClockRegistry} resolves that
 * choice once at startup, and
 * {@link com.amdocs.telecom.service.sla.SlaEvaluation} is the immutable
 * answer for one ticket at one moment.</p>
 *
 * <p>The strategies live here rather than in {@code service.impl} because
 * they are part of the contract: a caller asking which clock a band uses is
 * asking a question about the SLA policy, not about how the service happens
 * to be implemented.</p>
 */
package com.amdocs.telecom.service.sla;
