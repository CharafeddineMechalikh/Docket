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
package com.mechalikh.docket.examples.affinity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.policy.ReplicaLoad;
import com.mechalikh.docket.policy.RoutingPolicy;

/**
 * Keeps the turns of one conversation on the replica that served the previous turn.
 * <p>
 * This is the example of a policy that is allowed to remember something between decisions, and
 * of the two rules that come with it. The memory is cleared by {@link #reset()} before every
 * repetition, so a run never inherits the routing of the run before it; and a remembered replica
 * is only honoured while it is still a feasible candidate, so affinity never overrides the cache
 * check that protects the request.
 * <p>
 * The simulator does not model shared-prefix reuse, so affinity here changes where the load lands
 * and how it queues. It is not claimed to save cache reads: that would need a prefix-aware cost
 * model, which this stage does not have.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 1.0.1
 */
public final class SessionAffinityRouting implements RoutingPolicy {

	/** Replica that served the last turn of each session. */
	private final Map<String, Integer> affinity = new HashMap<>();

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String name() {
		return "session-affinity";
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * A request without a session identifier, or whose session is new, is routed by replica load
	 * and its choice is remembered for the next turn.
	 */
	@Override
	public int choose(List<ReplicaLoad> loads, InferenceRequest request) {
		List<ReplicaLoad> candidates = feasibleCandidates(loads, request);
		if (candidates.isEmpty())
			return -1;
		String session = request.getSessionId();
		int chosen;
		Integer remembered = session == null || session.isEmpty() ? null : affinity.get(session);
		if (remembered != null && holds(candidates, remembered))
			chosen = remembered;
		else
			chosen = leastLoaded(candidates).replicaId();
		if (session != null && !session.isEmpty())
			affinity.put(session, chosen);
		return chosen;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void reset() {
		affinity.clear();
	}

	private static boolean holds(List<ReplicaLoad> candidates, int replicaId) {
		for (ReplicaLoad candidate : candidates)
			if (candidate.replicaId() == replicaId)
				return true;
		return false;
	}

	private static ReplicaLoad leastLoaded(List<ReplicaLoad> candidates) {
		ReplicaLoad best = candidates.get(0);
		for (ReplicaLoad candidate : candidates)
			if (candidate.insideReplica() < best.insideReplica()
					|| (candidate.insideReplica() == best.insideReplica()
							&& candidate.replicaId() < best.replicaId()))
				best = candidate;
		return best;
	}

	/**
	 * Returns how many sessions the policy currently remembers, for the tests and the artifacts.
	 *
	 * @return the number of remembered sessions
	 */
	public int rememberedSessions() {
		return affinity.size();
	}
}
