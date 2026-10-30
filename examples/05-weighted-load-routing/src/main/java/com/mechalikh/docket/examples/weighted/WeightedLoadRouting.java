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
package com.mechalikh.docket.examples.weighted;

import java.util.List;

import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.policy.ReplicaLoad;
import com.mechalikh.docket.policy.RoutingPolicy;

/**
 * Scores every replica on four loads and routes to the lowest score.
 * <p>
 * The built-in {@code least-queued} counts requests, so one long prompt weighs the same as one
 * short chat turn. This policy lets the four signals be weighted against each other: waiting
 * requests, running sequences, the fraction of the cache already taken, and the work already
 * promised to the queue. The weights are the point of the example, and a reader changes the
 * constants to see the routing move: setting the last three to zero gives back
 * {@code least-queued} exactly.
 * <p>
 * The terms are counts, a fraction of the cache and a number of seconds; the weights turn them
 * into one unitless score, so they are pure trade-offs. The policy keeps no state, so equal scores are broken by identifier.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 1.0.1
 */
public final class WeightedLoadRouting implements RoutingPolicy {

	/** Weight of the waiting requests. */
	static final double WAITING_WEIGHT = 1.0;
	/** Weight of the running sequences. */
	static final double RUNNING_WEIGHT = 0.2;
	/** Weight of the fraction of the cache in use. */
	static final double CACHE_WEIGHT = 1.0;
	/** Weight of one second of work already promised to the queue. */
	static final double PENDING_WEIGHT = 2.0;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String name() {
		return "weighted-load";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int choose(List<ReplicaLoad> loads, InferenceRequest request) {
		List<ReplicaLoad> candidates = feasibleCandidates(loads, request);
		if (candidates.isEmpty())
			return -1;
		ReplicaLoad best = null;
		double bestScore = Double.POSITIVE_INFINITY;
		for (ReplicaLoad candidate : candidates) {
			double score = score(candidate);
			if (score < bestScore || (score == bestScore && candidate.replicaId() < best.replicaId())) {
				bestScore = score;
				best = candidate;
			}
		}
		return best.replicaId();
	}

	/**
	 * Returns the load score of one replica, where a lower score is a better destination.
	 *
	 * @param load the replica load
	 * @return the weighted score
	 */
	public static double score(ReplicaLoad load) {
		double cacheUsed = load.totalCacheTokens() == 0 ? 0
				: 1.0 - ((double) load.freeCacheTokens() / load.totalCacheTokens());
		return WAITING_WEIGHT * load.waitingRequests() + RUNNING_WEIGHT * load.runningSequences()
				+ CACHE_WEIGHT * cacheUsed + PENDING_WEIGHT * load.pendingWorkSeconds();
	}
}
