/**
 *     GenAIInferenceSim: a discrete-event simulator for generative AI inference serving.
 *
 *     This file is part of GenAIInferenceSim.
 *
 *     GenAIInferenceSim is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     GenAIInferenceSim is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with GenAIInferenceSim. If not, see <http://www.gnu.org/licenses/>.
 **/
package com.mechalikh.genaism.policy;

import java.util.List;

import com.mechalikh.genaism.domain.InferenceRequest;

/**
 * Sends each request to the next feasible replica in identifier order, cycling through the
 * replicas of the service.
 * <p>
 * The cursor counts the requests this policy routed, not the arrivals it saw: a request refused
 * by the admission policy never advances it, which matches a gateway that load balances only
 * among the requests it forwards.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public class RoundRobinRouting implements RoutingPolicy {

	/** Number of requests routed since the policy was created or reset. */
	protected long routedRequests;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int choose(List<ReplicaLoad> loads, InferenceRequest request) {
		List<ReplicaLoad> candidates = feasibleCandidates(loads, request);
		if (candidates.isEmpty())
			return -1;
		int index = (int) (routedRequests % candidates.size());
		routedRequests++;
		return candidates.get(index).replicaId();
	}

	/**
	 * Resets the cursor, so the next routed request starts again at the first candidate.
	 */
	@Override
	public void reset() {
		routedRequests = 0;
	}

	/**
	 * Returns how many requests the policy routed since the last reset.
	 *
	 * @return routed requests
	 */
	public long getRoutedRequests() {
		return routedRequests;
	}

	/**
	 * Returns the policy name written in the artifacts.
	 *
	 * @return {@code round-robin}
	 */
	@Override
	public String name() {
		return "round-robin";
	}
}
