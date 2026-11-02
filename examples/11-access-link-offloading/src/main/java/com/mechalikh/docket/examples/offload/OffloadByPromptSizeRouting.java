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
package com.mechalikh.docket.examples.offload;

import java.util.List;

import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.policy.ReplicaLoad;
import com.mechalikh.docket.policy.RoutingPolicy;

/**
 * Sends a short prompt across the bearer to the fast remote machine, and keeps a long one local.
 * <p>
 * A remote accelerator that answers in half the time still makes the client wait for the prompt to
 * cross the access link before it can start. For a short prompt that transfer is negligible and the
 * speed wins; for a long prompt the bytes on the wire cost more than the compute saved, and the
 * slower machine on the local site answers sooner. This policy turns that trade-off into one number:
 * a request whose prompt is at most {@link #PROMPT_LIMIT} tokens is offloaded to the remote tier,
 * any longer one is served on the local tier, and within whichever tier is preferred the least busy
 * replica takes it.
 * <p>
 * The threshold is a proxy for the bearer tax, not a model of it. A routing policy sees only the
 * load snapshot and the request; it does not hold the link's capacity or latency, so the example
 * fixes the crossover by hand and the reader changes the constant against the link they write in
 * the scenario. Setting it to {@link Integer#MAX_VALUE} offloads every request, which is what a
 * capacity-blind balancer does to a fast remote; setting it to {@code -1} offloads none, which pins
 * the whole service to the local site. Both extremes are measured in the example so the rule can be
 * read against the two edges it interpolates.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 1.1
 */
public final class OffloadByPromptSizeRouting implements RoutingPolicy {

	/** Prompts up to this many tokens are worth the transfer; a longer one is served locally. */
	static final int PROMPT_LIMIT = 400;
	/** The tier the client reaches through an access link, the faster but farther machine. */
	static final String REMOTE_TIER = "cloud-accelerator";
	/** The tier on the client's own site, which the request reaches without crossing a bearer. */
	static final String LOCAL_TIER = "edge-datacenter";

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String name() {
		return "offload-small-prompts";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String describe() {
		return name() + " (offload to " + REMOTE_TIER + " when the prompt is at most " + PROMPT_LIMIT
				+ " tokens, otherwise serve on " + LOCAL_TIER + ")";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int choose(List<ReplicaLoad> loads, InferenceRequest request) {
		List<ReplicaLoad> candidates = ReplicaLoad.sortedById(feasibleCandidates(loads, request));
		if (candidates.isEmpty())
			return -1;
		String preferred = request.getInputTokens() <= PROMPT_LIMIT ? REMOTE_TIER : LOCAL_TIER;
		ReplicaLoad chosen = leastLoaded(candidates, preferred);
		if (chosen == null)
			chosen = leastLoaded(candidates, preferred.equals(REMOTE_TIER) ? LOCAL_TIER : REMOTE_TIER);
		if (chosen == null)
			chosen = leastLoaded(candidates, null);
		return chosen.replicaId();
	}

	/**
	 * Returns the least busy replica of one tier, or {@code null} when the tier has none among the
	 * candidates. A {@code null} tier matches every candidate, which is the final fallback.
	 *
	 * @param candidates the feasible replicas, in identifier order
	 * @param tier       the tier to pick from, or {@code null} for any
	 * @return the chosen load, or {@code null}
	 */
	private static ReplicaLoad leastLoaded(List<ReplicaLoad> candidates, String tier) {
		ReplicaLoad best = null;
		for (ReplicaLoad candidate : candidates) {
			if (tier != null && !candidate.tier().equals(tier))
				continue;
			if (best == null || candidate.insideReplica() < best.insideReplica())
				best = candidate;
		}
		return best;
	}
}
