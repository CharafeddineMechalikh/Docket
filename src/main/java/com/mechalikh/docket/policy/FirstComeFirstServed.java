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

import java.util.Comparator;

import com.mechalikh.docket.domain.InferenceRequest;

/**
 * Prefills the requests that arrived first, which is the behaviour of a serving engine without
 * a batch scheduler.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 0.1
 */
public class FirstComeFirstServed implements SchedulingPolicy {

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Comparator<InferenceRequest> waitingOrder() {
		return Comparator.comparingDouble(InferenceRequest::getArrivalTime)
				.thenComparingLong(InferenceRequest::getRequestId);
	}

	/**
	 * Returns the policy name written in the artifacts.
	 *
	 * @return {@code fcfs}
	 */
	@Override
	public String name() {
		return "fcfs";
	}

	/**
	 * Arrival times and identifiers are known to any gateway, so the order needs no information
	 * from the workload.
	 *
	 * @return always {@code false}
	 */
	@Override
	public boolean usesOracleInformation() {
		return false;
	}
}
