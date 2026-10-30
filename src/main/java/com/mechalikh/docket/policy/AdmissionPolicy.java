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

/**
 * Decides whether a request may enter the queues of a service.
 * <p>
 * Implementations are pure functions of the request and the reported loads: they may not
 * mutate either, and they must not change their answer when called twice with the same inputs.
 * That keeps an experiment reproducible and makes a policy testable without a running server.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 0.1
 */
public interface AdmissionPolicy {

	/**
	 * Returns the short name written to the artifacts.
	 *
	 * @return the policy name
	 */
	String name();

	/**
	 * Decides whether the request enters the service.
	 *
	 * @param request the arriving request, not null
	 * @param loads   the load of every replica of the service the request targets, not empty
	 * @param clock   current simulation time, in seconds
	 * @return the decision and its reason
	 */
	AdmissionDecision decide(InferenceRequest request, List<ReplicaLoad> loads, double clock);

	/**
	 * Returns the description written next to the results, naming the thresholds in force.
	 *
	 * @return a one-line description
	 */
	default String describe() {
		return name();
	}
}
