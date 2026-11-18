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
 * The static tier rule: only the edge tier may answer, and nothing else is ever considered.
 * <p>
 * This is the placement an operator runs when the data may not leave the site, and it is the
 * strongest baseline a joint fidelity-and-placement policy has to beat, because it shares the
 * serving machinery, the workload and the reasoning budget with the policy under test and differs
 * from it in exactly one decision: the cloud card is never eligible, however idle it is.
 * <p>
 * The rule is deliberately unforgiving. When no edge replica can hold the context the request is
 * refused, which is what a static tier costs an operator at the moment the tier fills up, and the
 * refusal is recorded with the reason the engine gives rather than hidden behind a fallback to the
 * machine the policy was told not to use. Among the eligible replicas the least congested wins,
 * measured in the work its queue promised, with ties broken by the lowest replica identifier so the
 * choice replays.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 1.1
 */
public class EdgeTierOnlyRouting implements RoutingPolicy {

	/** Device families this rule considers, as the scenario labels them in {@code device.*.type}. */
	public static final List<String> EDGE_TIERS = Arrays.asList("edge-device", "edge-datacenter");

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String name() {
		return "edge-tier-only";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String describe() {
		return name() + " (eligible tiers " + String.join(", ", EDGE_TIERS) + ", least congested of them)";
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
		for (ReplicaLoad candidate : candidates) {
			if (!EDGE_TIERS.contains(candidate.tier()))
				continue;
			if (best == null || candidate.pendingWorkSeconds() < best.pendingWorkSeconds()
					|| (candidate.pendingWorkSeconds() == best.pendingWorkSeconds()
							&& candidate.replicaId() < best.replicaId()))
				best = candidate;
		}
		return best == null ? -1 : best.replicaId();
	}
}
