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
package com.mechalikh.docket.examples.fidelityedge;

import java.util.Arrays;
import java.util.List;

import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.policy.ReplicaLoad;
import com.mechalikh.docket.policy.RoutingPolicy;

/**
 * The tier an operator prefers, held only as long as it can still answer inside the deadline.
 * <p>
 * A reasoning budget and a placement are one decision, not two: what the edge tier can deliver in
 * the time left depends on how much of the answer was kept, and how much of the answer is worth
 * keeping depends on which machine will generate it. The fidelity policy has already granted the
 * budget by the time this rule runs, so the request arrives carrying the work it is now allowed to
 * do, and the rule walks the tiers from the one the operator likes best upward, asking each one the
 * question a load counter cannot answer: at this budget, with this queue, with this hop, does the
 * prediction still fit inside the deadline? The first tier that says yes receives the request, so
 * the cheap tier keeps the traffic whenever it honestly can.
 * <p>
 * When no tier says yes the request goes to the tier with the smallest predicted delay, which is
 * the cheapest way to miss: the deadline is already lost, and the only remaining choice is how much
 * of it to lose. Two limits follow from predicting instead of measuring. The estimate is the work
 * the queue has promised plus this request's own prefill and decode at the replica's current batch,
 * so the traffic that arrives after the decision is not in it, and the prices come from the
 * deployment's cost model, which is analytical and uncalibrated in every committed scenario here:
 * the seconds are the roofline's, not a card's.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 1.1
 */
public class JointTierBudgetRouting implements RoutingPolicy {

	/** Device families in the order the operator prefers them, cheapest first. */
	public static final List<String> TIER_ORDER = Arrays.asList("edge-device", "edge-datacenter",
			"cloud-accelerator");
	/** Share of the remaining deadline given back to prediction error, so a fit is a fit with room. */
	public static final double MARGIN = 0.15;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String name() {
		return "joint-tier-budget";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String describe() {
		return name() + " (tier order " + String.join(" > ", TIER_ORDER) + ", deadline guard at "
				+ (int) (MARGIN * 100) + "% margin, fallback to the smallest predicted delay)";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int choose(List<ReplicaLoad> loads, InferenceRequest request) {
		List<ReplicaLoad> candidates = feasibleCandidates(loads, request);
		if (candidates.isEmpty())
			return -1;
		double allowance = allowanceSeconds(request);
		int budget = request.getOutputTokenBudget();
		if (!Double.isNaN(allowance)) {
			for (String tier : ranks(candidates)) {
				ReplicaLoad best = quickestInTier(candidates, tier, budget);
				if (best != null && best.predictedDelaySeconds(budget) <= allowance)
					return best.replicaId();
			}
		}
		ReplicaLoad quickest = null;
		double quickestSeconds = Double.MAX_VALUE;
		for (ReplicaLoad candidate : candidates) {
			double seconds = candidate.predictedDelaySeconds(budget);
			if (Double.isNaN(seconds))
				continue;
			if (seconds < quickestSeconds || (seconds == quickestSeconds && quickest != null
					&& candidate.replicaId() < quickest.replicaId())) {
				quickestSeconds = seconds;
				quickest = candidate;
			}
		}
		if (quickest != null)
			return quickest.replicaId();
		// Nothing could be priced, which happens when a snapshot carries no cost model: fall back to
		// the preference order alone, so the rule still places the request rather than refusing it.
		for (String tier : ranks(candidates)) {
			ReplicaLoad best = leastLoadedInTier(candidates, tier);
			if (best != null)
				return best.replicaId();
		}
		return candidates.get(0).replicaId();
	}

	/**
	 * Returns the seconds this request has left before its end-to-end objective is due, reduced by
	 * the margin the rule keeps back.
	 *
	 * @param request the request being placed
	 * @return the allowance in seconds, or {@code NaN} when the request states no end-to-end target
	 */
	protected static double allowanceSeconds(InferenceRequest request) {
		double deadline = request.getEndToEndDeadline();
		if (deadline <= 0)
			return Double.NaN;
		return (deadline - request.getArrivalTime()) * (1 - MARGIN);
	}

	/**
	 * Returns the tiers present among the candidates, in preference order.
	 *
	 * @param candidates the replicas that can hold the request
	 * @return the distinct tiers, preferred first, and any unlisted tier after them by label
	 */
	protected static List<String> ranks(List<ReplicaLoad> candidates) {
		java.util.List<String> present = new java.util.ArrayList<>();
		for (String tier : TIER_ORDER)
			for (ReplicaLoad candidate : candidates)
				if (candidate.tier().equals(tier) && !present.contains(tier))
					present.add(tier);
		for (ReplicaLoad candidate : candidates)
			if (!TIER_ORDER.contains(candidate.tier()) && !present.contains(candidate.tier()))
				present.add(candidate.tier());
		return present;
	}

	/**
	 * Returns the replica of one tier that would finish the request soonest at its granted budget.
	 *
	 * @param candidates the replicas that can hold the request
	 * @param tier       the tier to look inside
	 * @param budget     the reasoning budget the estimate prices
	 * @return the quickest replica of that tier, or {@code null} when the tier has no candidate
	 */
	protected static ReplicaLoad quickestInTier(List<ReplicaLoad> candidates, String tier, int budget) {
		ReplicaLoad best = null;
		double bestSeconds = Double.MAX_VALUE;
		for (ReplicaLoad candidate : candidates) {
			if (!candidate.tier().equals(tier))
				continue;
			double seconds = candidate.predictedDelaySeconds(budget);
			if (Double.isNaN(seconds))
				continue;
			if (seconds < bestSeconds || (seconds == bestSeconds && best != null
					&& candidate.replicaId() < best.replicaId())) {
				bestSeconds = seconds;
				best = candidate;
			}
		}
		if (best == null)
			best = leastLoadedInTier(candidates, tier);
		return best;
	}

	/**
	 * Returns the replica of one tier with the least promised work, used when nothing can be priced.
	 *
	 * @param candidates the replicas that can hold the request
	 * @param tier       the tier to look inside
	 * @return the least loaded replica of that tier, or {@code null} when the tier has no candidate
	 */
	protected static ReplicaLoad leastLoadedInTier(List<ReplicaLoad> candidates, String tier) {
		ReplicaLoad best = null;
		for (ReplicaLoad candidate : candidates) {
			if (!candidate.tier().equals(tier))
				continue;
			if (best == null || candidate.pendingWorkSeconds() < best.pendingWorkSeconds()
					|| (candidate.pendingWorkSeconds() == best.pendingWorkSeconds()
							&& candidate.replicaId() < best.replicaId()))
				best = candidate;
		}
		return best;
	}
}
