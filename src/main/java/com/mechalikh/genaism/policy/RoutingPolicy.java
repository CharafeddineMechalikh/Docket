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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.mechalikh.genaism.domain.InferenceRequest;

/**
 * Chooses which replica of a service receives a request.
 * <p>
 * Every policy sees the same candidates: the replicas that can hold the context the request is
 * expected to reach. A replica with too little free cache is never returned, because queueing a
 * request that cannot be served would produce a preemption loop rather than a decision. When no
 * replica can take the request, the policy reports it with {@code -1} and the service records
 * the refusal.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public interface RoutingPolicy {

	/**
	 * Returns the short name written to the artifacts.
	 *
	 * @return the policy name
	 */
	String name();

	/**
	 * Selects the replica that receives the request.
	 *
	 * @param loads   the loads of the replicas serving the model of the request, not empty
	 * @param request the request being routed
	 * @return the identifier of the chosen replica, or {@code -1} when none can take it
	 */
	int choose(List<ReplicaLoad> loads, InferenceRequest request);

	/**
	 * Clears the state a policy keeps between decisions.
	 * <p>
	 * Called before each experiment repetition, so that a counter such as the round-robin cursor
	 * cannot leak from one run into the next.
	 */
	default void reset() {
	}

	/**
	 * Returns the description written next to the results.
	 *
	 * @return a one-line description
	 */
	default String describe() {
		return name();
	}

	/**
	 * Returns the replicas that can hold the context the request is expected to reach, in
	 * identifier order.
	 *
	 * @param loads   the loads of the service
	 * @param request the request being routed
	 * @return the feasible candidates, possibly empty
	 */
	default List<ReplicaLoad> feasibleCandidates(List<ReplicaLoad> loads, InferenceRequest request) {
		int needed = request.finalContextTokens(true);
		List<ReplicaLoad> candidates = new ArrayList<>();
		for (ReplicaLoad load : loads)
			if (load.canHoldContext(needed))
				candidates.add(load);
		candidates.sort(Comparator.comparingInt(ReplicaLoad::replicaId));
		return candidates;
	}
}
