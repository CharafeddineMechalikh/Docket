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
 * Sends a request to the feasible replica holding the fewest requests, counting both the waiting
 * and the running ones.
 * <p>
 * Running sequences are counted because a decode batch is the part of the replica that decides
 * the iteration duration; a policy that only looked at the queue would keep adding requests to a
 * replica already generating a large batch.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 0.1
 */
public class LeastQueuedRouting implements RoutingPolicy {

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int choose(List<ReplicaLoad> loads, InferenceRequest request) {
		List<ReplicaLoad> candidates = feasibleCandidates(loads, request);
		int best = -1;
		int bestLoad = Integer.MAX_VALUE;
		for (ReplicaLoad candidate : candidates) {
			int load = candidate.insideReplica();
			if (load < bestLoad) {
				bestLoad = load;
				best = candidate.replicaId();
			}
		}
		return best;
	}

	/**
	 * Returns the policy name written in the artifacts.
	 *
	 * @return {@code least-queued}
	 */
	@Override
	public String name() {
		return "least-queued";
	}
}
