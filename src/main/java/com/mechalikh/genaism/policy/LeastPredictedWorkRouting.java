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
 * Sends a request to the feasible replica that promised the least work, measured in predicted
 * seconds rather than in request counts.
 * <p>
 * The pending work of a replica comes from
 * {@link com.mechalikh.genaism.cost.ExecutionCostModel} estimates of its queue, so this policy
 * inherits the provenance of the cost model: with an analytical model its decisions are as
 * provisional as the durations they predict. The label written in the artifacts says which cost
 * model was used.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public class LeastPredictedWorkRouting implements RoutingPolicy {

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int choose(List<ReplicaLoad> loads, InferenceRequest request) {
		List<ReplicaLoad> candidates = feasibleCandidates(loads, request);
		int best = -1;
		double bestWork = Double.MAX_VALUE;
		for (ReplicaLoad candidate : candidates) {
			if (candidate.pendingWorkSeconds() < bestWork) {
				bestWork = candidate.pendingWorkSeconds();
				best = candidate.replicaId();
			}
		}
		return best;
	}

	/**
	 * Returns the policy name written in the artifacts.
	 *
	 * @return {@code least-predicted-work}
	 */
	@Override
	public String name() {
		return "least-predicted-work";
	}
}
