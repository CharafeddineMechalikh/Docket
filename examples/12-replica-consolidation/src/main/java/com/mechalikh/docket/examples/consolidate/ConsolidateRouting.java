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
package com.mechalikh.docket.examples.consolidate;

import java.util.List;

import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.policy.ReplicaLoad;
import com.mechalikh.docket.policy.RoutingPolicy;

/**
 * Packs every request onto the replica that already holds the most, instead of spreading them.
 * <p>
 * The built-in balancers open a fresh machine as soon as one has any work, because the fastest way
 * to answer a single request is to give it an idle device. That is the right choice when the only
 * objective is latency, but it is not free: a decode batch is where a serving engine amortises the
 * cost of the weights, so four devices each running a batch of one read those weights four times to
 * do the work one device would do with a batch of four. Consolidation is the other end of the same
 * dial. This policy always chooses the most loaded replica that can still hold the context, so one
 * machine fills up before the next one opens, which makes the batching economics visible.
 * <p>
 * The fleet is a set of identical clones on purpose. On a heterogeneous fleet a placement policy
 * mixes two effects, the tier it prefers and the batch it forms; on clones the machines are
 * interchangeable, so every difference between this policy and a balancer comes from how full each
 * batch runs and from how many devices ever leave their idle power state.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 1.1
 */
public class ConsolidateRouting implements RoutingPolicy {

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String name() {
		return "consolidate";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String describe() {
		return name() + " (pack onto the most loaded replica that can still hold the context)";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int choose(List<ReplicaLoad> loads, InferenceRequest request) {
		List<ReplicaLoad> candidates = ReplicaLoad.sortedById(feasibleCandidates(loads, request));
		if (candidates.isEmpty())
			return -1;
		ReplicaLoad chosen = pick(candidates);
		return chosen == null ? candidates.get(0).replicaId() : chosen.replicaId();
	}

	/**
	 * Returns the replica the policy fills next, given the candidates that can hold the request.
	 * <p>
	 * The base policy has no ceiling, so it returns the most loaded candidate outright. A subclass
	 * that bounds the batch overrides this and decides where packing stops.
	 *
	 * @param candidates the feasible replicas, in identifier order
	 * @return the chosen load, or {@code null} when the policy imposes no rule the caller should use
	 */
	protected ReplicaLoad pick(List<ReplicaLoad> candidates) {
		return fullest(candidates, Integer.MAX_VALUE);
	}

	/**
	 * Returns the most loaded replica that still sits below a ceiling, or {@code null} when none does.
	 * <p>
	 * Load is the number of requests already inside, waiting and running together, because that is
	 * what the next request joins. Equal loads are broken by the lowest identifier, so the first
	 * replica fills before the second opens even when both look empty at the same instant.
	 *
	 * @param candidates the feasible replicas, in identifier order
	 * @param ceiling    the largest load a replica may already hold to still be chosen
	 * @return the fullest admissible load, or {@code null}
	 */
	protected static ReplicaLoad fullest(List<ReplicaLoad> candidates, int ceiling) {
		ReplicaLoad best = null;
		for (ReplicaLoad candidate : candidates) {
			if (candidate.insideReplica() >= ceiling)
				continue;
			if (best == null || candidate.insideReplica() > best.insideReplica())
				best = candidate;
		}
		return best;
	}

	/**
	 * Returns the least loaded replica, used by a bounded policy when every replica has reached its
	 * ceiling and a fresh one has to open.
	 *
	 * @param candidates the feasible replicas, in identifier order
	 * @return the emptiest load
	 */
	protected static ReplicaLoad emptiest(List<ReplicaLoad> candidates) {
		ReplicaLoad best = null;
		for (ReplicaLoad candidate : candidates)
			if (best == null || candidate.insideReplica() < best.insideReplica())
				best = candidate;
		return best;
	}
}
