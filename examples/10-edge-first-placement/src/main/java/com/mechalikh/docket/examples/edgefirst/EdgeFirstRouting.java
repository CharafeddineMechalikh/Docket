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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.policy.ReplicaLoad;
import com.mechalikh.docket.policy.RoutingPolicy;

/**
 * Sends every request to the cheapest tier that can hold it, and only then upward.
 * <p>
 * A deployment that mixes a mobile accelerator, an on-premise server and a cloud card has a
 * preference no load counter can see: the edge is the machine the operator wants used, for the
 * latency of its own clients, for the data that may not leave the site, or for the watt price. The
 * built-in policies balance replicas as if they were interchangeable, which is right when they are
 * clones and wrong when they are not. This policy makes the preference explicit and measurable: the
 * tier order is written here, the load decides which replica of the chosen tier receives the
 * request, and the result says what that preference cost.
 * <p>
 * The order names the {@code device.<profile>.type} labels of the scenario, cheapest first. A
 * replica whose type is not listed is treated as the most expensive machine there is, and is placed
 * after every listed tier, ordered by its own label so that two runs of the same scenario agree.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 1.1
 */
public class EdgeFirstRouting implements RoutingPolicy {

	/** Device families in the order the operator prefers them, cheapest first. */
	public static final List<String> TIER_ORDER = Arrays.asList("edge-device", "edge-datacenter",
			"cloud-accelerator");

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String name() {
		return "edge-first";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String describe() {
		return name() + " (tier order " + String.join(" > ", TIER_ORDER) + ")";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int choose(List<ReplicaLoad> loads, InferenceRequest request) {
		List<ReplicaLoad> candidates = ReplicaLoad.sortedById(feasibleCandidates(loads, request));
		if (candidates.isEmpty())
			return -1;
		for (String tier : ranks(candidates)) {
			ReplicaLoad best = leastLoaded(candidates, tier);
			if (best != null)
				return best.replicaId();
		}
		return candidates.get(0).replicaId();
	}

	/**
	 * Returns the tiers present among the candidates, in preference order.
	 * <p>
	 * Only tiers that have a candidate are listed: a policy that walked a tier nobody declares would
	 * report a placement decision it never had the chance to make.
	 *
	 * @param candidates the replicas that can hold the request
	 * @return the distinct tiers, preferred first
	 */
	protected static List<String> ranks(List<ReplicaLoad> candidates) {
		List<String> present = new ArrayList<>();
		for (String tier : TIER_ORDER)
			for (ReplicaLoad candidate : candidates)
				if (candidate.tier().equals(tier) && !present.contains(tier))
					present.add(tier);
		List<String> unknown = new ArrayList<>();
		for (ReplicaLoad candidate : candidates)
			if (!TIER_ORDER.contains(candidate.tier()) && !unknown.contains(candidate.tier()))
				unknown.add(candidate.tier());
		unknown.sort(String::compareTo);
		present.addAll(unknown);
		return present;
	}

	/**
	 * Returns the least busy replica of one tier, or {@code null} when the tier has none.
	 * <p>
	 * Busy is the number of requests already inside the replica, waiting and running together,
	 * because that is what the request will queue behind. Equal loads are broken by identifier, so
	 * the choice does not depend on the order the engine happened to build its replicas in.
	 *
	 * @param candidates the replicas that can hold the request, in identifier order
	 * @param tier       the tier to pick from
	 * @return the chosen load, or {@code null}
	 */
	protected static ReplicaLoad leastLoaded(List<ReplicaLoad> candidates, String tier) {
		ReplicaLoad best = null;
		for (ReplicaLoad candidate : candidates) {
			if (!candidate.tier().equals(tier))
				continue;
			if (best == null || candidate.insideReplica() < best.insideReplica())
				best = candidate;
		}
		return best;
	}
}
