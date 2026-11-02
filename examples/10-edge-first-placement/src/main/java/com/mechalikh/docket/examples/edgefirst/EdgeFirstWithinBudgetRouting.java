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
package com.mechalikh.docket.examples.edgefirst;

import java.util.List;

import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.policy.ReplicaLoad;

/**
 * The edge-first preference, held only as long as it is affordable.
 * <p>
 * A tier the operator likes is not a tier the request can wait for. This policy walks the same
 * preference order as {@link EdgeFirstRouting} and asks one more question of each candidate: does
 * the work already promised to that replica fit inside the first-token budget the request states?
 * If it does, the request goes there and the cheaper tiers win as often as they can. If it does not,
 * the request moves up one tier, which is what an offloading decision looks like when a service
 * level agreement is part of the cost function.
 * <p>
 * Two honest limits come with that. A request that states no budget is served by the cheapest tier
 * that has room, exactly as the unguarded policy would place it, so the comparison between the two
 * is only about the traffic that carries a target. And the estimate is the queue's promised work,
 * not the request's own prefill: the policy has no cost model in hand, so a short queue on a slow
 * device can still look affordable. A request no candidate can serve inside its budget goes to the
 * least busy candidate, which is the cheapest way to miss.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 1.1
 */
public final class EdgeFirstWithinBudgetRouting extends EdgeFirstRouting {

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String name() {
		return "edge-first-within-budget";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String describe() {
		return name() + " (tier order " + String.join(" > ", TIER_ORDER) + ", first-token budget as the guard)";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int choose(List<ReplicaLoad> loads, InferenceRequest request) {
		List<ReplicaLoad> candidates = ReplicaLoad.sortedById(feasibleCandidates(loads, request));
		if (candidates.isEmpty())
			return -1;
		double budget = firstTokenBudget(request);
		for (String tier : ranks(candidates)) {
			ReplicaLoad best = leastLoaded(candidates, tier);
			if (best != null && (Double.isNaN(budget) || best.pendingWorkSeconds() <= budget))
				return best.replicaId();
		}
		ReplicaLoad leastBusy = null;
		for (ReplicaLoad candidate : candidates)
			if (leastBusy == null || candidate.insideReplica() < leastBusy.insideReplica())
				leastBusy = candidate;
		return leastBusy.replicaId();
	}

	/**
	 * Returns the first-token budget a request states, in seconds, measured from the instant it was
	 * released.
	 *
	 * @param request the request being routed
	 * @return the budget, or {@code NaN} when the request states none
	 */
	protected static double firstTokenBudget(InferenceRequest request) {
		double deadline = request.getTtftDeadline();
		if (deadline <= 0)
			return Double.NaN;
		return deadline - request.getArrivalTime();
	}
}
