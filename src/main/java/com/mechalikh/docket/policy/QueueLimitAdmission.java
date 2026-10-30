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
package com.mechalikh.docket.policy;

import java.util.List;

import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.domain.OutcomeReason;

/**
 * Refuses a request once the queues of the service hold the configured number of waiting
 * requests, which is the behaviour of a serving gateway with a bounded backlog.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 0.1
 */
public class QueueLimitAdmission implements AdmissionPolicy {

	/** Largest number of waiting requests accepted across the replicas of the service. */
	protected final int maxWaitingRequests;

	/**
	 * Sets the backlog limit.
	 *
	 * @param maxWaitingRequests waiting requests the service accepts, at least one
	 */
	public QueueLimitAdmission(int maxWaitingRequests) {
		if (maxWaitingRequests < 1)
			throw new IllegalArgumentException("A queue limit of " + maxWaitingRequests
					+ " requests would refuse every arrival; use at least one");
		this.maxWaitingRequests = maxWaitingRequests;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public AdmissionDecision decide(InferenceRequest request, List<ReplicaLoad> loads, double clock) {
		int waiting = loads.stream().mapToInt(ReplicaLoad::waitingRequests).sum();
		return waiting >= maxWaitingRequests ? AdmissionDecision.reject(OutcomeReason.ADMISSION_QUEUE_LIMIT)
				: AdmissionDecision.admit();
	}

	/**
	 * Returns the policy name written in the artifacts.
	 *
	 * @return {@code queue-limit}
	 */
	@Override
	public String name() {
		return "queue-limit";
	}

	/**
	 * Returns the description, naming the largest accepted backlog.
	 *
	 * @return a one-line description
	 */
	@Override
	public String describe() {
		return name() + "(max waiting " + maxWaitingRequests + ")";
	}

	/**
	 * Returns the configured limit.
	 *
	 * @return waiting requests accepted across the service
	 */
	public int getMaxWaitingRequests() {
		return maxWaitingRequests;
	}
}
