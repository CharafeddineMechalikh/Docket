/**
 *     Docket: a discrete-event simulator for generative AI inference serving.
 *
 *     This file is part of Docket.
 *
 *     Docket is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Docket is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with Docket. If not, see <http://www.gnu.org/licenses/>.
 **/
package com.mechalikh.docket.examples.reservation;

import java.util.List;

import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.domain.OutcomeReason;
import com.mechalikh.docket.policy.AdmissionDecision;
import com.mechalikh.docket.policy.AdmissionPolicy;
import com.mechalikh.docket.policy.ReplicaLoad;

/**
 * Refuses interactive traffic once the waiting queue holds a set number of requests of any class.
 * <p>
 * The built-in {@code queue-limit} rule caps the whole service, so a burst of batch work fills
 * the queue and the interactive requests are refused along with it. This rule caps the queue for
 * everyone except the protected class: batch work may only wait while the queue holds fewer than
 * {@code limit} requests, and an interactive request is always accepted. The queue therefore
 * belongs to the latency-sensitive class, and the throughput class uses whatever room is left.
 * <p>
 * The decision is a pure function of the request and the reported loads. It keeps no counter of
 * its own, which is what lets the same scenario replay identically and lets the rule be tested
 * without a running server.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 1.0.1
 */
public final class InteractiveReservationAdmission implements AdmissionPolicy {

	/** Service class that is never refused. */
	static final String PROTECTED_CLASS = "interactive";
	/** Waiting requests the queue holds before the other classes are refused. */
	static final int DEFAULT_LIMIT = 4;

	/** The queue occupancy reserved for the protected class. */
	private final int limit;

	/**
	 * Creates the policy with {@link #DEFAULT_LIMIT}, which is what a scenario that names this
	 * class by name gets.
	 */
	public InteractiveReservationAdmission() {
		this(DEFAULT_LIMIT);
	}

	/**
	 * Creates the policy with an explicit limit.
	 *
	 * @param limit waiting requests allowed before the other classes are refused, zero or more
	 */
	public InteractiveReservationAdmission(int limit) {
		if (limit < 0)
			throw new IllegalArgumentException("The reservation cannot be negative, found " + limit);
		this.limit = limit;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * A request of the protected class is always admitted; a request of another class is refused
	 * once the replicas already hold {@link #limit} waiting requests of any class.
	 */
	@Override
	public AdmissionDecision decide(InferenceRequest request, List<ReplicaLoad> loads, double clock) {
		if (PROTECTED_CLASS.equals(request.getServiceClass()))
			return AdmissionDecision.admit();
		int waiting = 0;
		for (ReplicaLoad load : loads)
			waiting += load.waitingRequests();
		return waiting >= limit ? AdmissionDecision.reject(OutcomeReason.ADMISSION_QUEUE_LIMIT)
				: AdmissionDecision.admit();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String name() {
		return "interactive-reservation";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String describe() {
		return name() + "(" + PROTECTED_CLASS + " always queued, others below " + limit + " waiting)";
	}

	/**
	 * Returns the queue occupancy that triggers a refusal of the other classes.
	 *
	 * @return the limit
	 */
	public int getLimit() {
		return limit;
	}
}
