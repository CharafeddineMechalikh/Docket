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
package com.mechalikh.docket.examples.fairshare;

import java.util.List;

import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.policy.ReplicaLoad;
import com.mechalikh.docket.policy.RoutingPolicy;

/**
 * Routes each class towards the objective that class actually cares about.
 * <p>
 * A latency-sensitive request is sent to the replica that owes the least work, because its first
 * token waits for that work to finish. A throughput-tolerant request is sent to the replica with
 * the most free cache, because a long batch prompt only needs somewhere it fits; spreading it by
 * queue length would put it where the cache is tight and force preemptions.
 * <p>
 * The policy reads no state between decisions, so two replicas that score the same are broken by
 * identifier and the run replays identically.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 1.0.1
 */
public final class ClassObjectiveRouting implements RoutingPolicy {

	/** Class routed by pending work; every other class is routed by free cache. */
	static final String LATENCY_CLASS = "interactive";

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String name() {
		return "class-objective";
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Candidates are already limited to the replicas that can hold the context of the request, so
	 * a choice never sends work to a replica that would have to refuse it.
	 */
	@Override
	public int choose(List<ReplicaLoad> loads, InferenceRequest request) {
		List<ReplicaLoad> candidates = feasibleCandidates(loads, request);
		if (candidates.isEmpty())
			return -1;
		boolean latency = LATENCY_CLASS.equals(request.getServiceClass());
		ReplicaLoad best = null;
		for (ReplicaLoad candidate : candidates) {
			if (best == null || better(candidate, best, latency))
				best = candidate;
		}
		return best.replicaId();
	}

	/**
	 * Tells whether one replica is a better destination than another for the class at hand.
	 *
	 * @param candidate the replica under test
	 * @param best      the best replica found so far
	 * @param latency   {@code true} for the pending-work objective
	 * @return {@code true} when the candidate should replace the incumbent
	 */
	private static boolean better(ReplicaLoad candidate, ReplicaLoad best, boolean latency) {
		if (latency) {
			int byWork = Double.compare(candidate.pendingWorkSeconds(), best.pendingWorkSeconds());
			return byWork < 0 || (byWork == 0 && candidate.replicaId() < best.replicaId());
		}
		int byCache = Integer.compare(candidate.freeCacheTokens(), best.freeCacheTokens());
		return byCache > 0 || (byCache == 0 && candidate.replicaId() < best.replicaId());
	}
}
