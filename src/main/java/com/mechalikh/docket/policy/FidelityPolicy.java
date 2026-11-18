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
 * Decides how much of the work a request may still do before the service places it.
 * <p>
 * A serving stack has more than one way to make a request cheaper: it can let the model run to the
 * length the workload asked for, or grant a shorter reasoning budget, or decline the request
 * altogether. This policy owns the first two, and it is the only place in the simulator allowed to
 * change the work a request is going to do. The decision is taken once per arrival, before the
 * admission policy and before routing, because the cache a request may reach, the work its queue
 * position promises and the deadline it can still make are all consequences of the grant.
 * <p>
 * A policy never prices its own decision. It reads the marginal step times the replicas publish in
 * {@link ReplicaLoad}, which come from the execution cost model of the deployment, so the seconds a
 * grant saves are the seconds the roofline says they are; a scenario that changes the cost model or
 * the device changes the answer here too. That is the difference between a fidelity decision and a
 * multiplier: the first lets the arithmetic decide, the second asserts it.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 1.1
 */
public interface FidelityPolicy {

	/**
	 * Returns the short name written to the artifacts.
	 *
	 * @return the policy name
	 */
	String name();

	/**
	 * Chooses the reasoning budget a request is granted.
	 *
	 * @param request the request being placed, still carrying the length the workload asked for
	 * @param loads   the loads of the replicas that could serve the request, not empty
	 * @param clock   the arrival instant, in seconds
	 * @return the tokens the request may generate, at least one, or {@code 0} to leave the workload's
	 *         own ask in place
	 */
	int chooseBudget(InferenceRequest request, List<ReplicaLoad> loads, double clock);

	/**
	 * Clears the state a policy keeps between decisions.
	 * <p>
	 * Called before each experiment repetition, so counters cannot leak from one run into the next.
	 */
	default void reset() {
	}

	/**
	 * Returns the description written next to the results.
	 *
	 * @return a one-line description
	 */
	default String describe() {
		return name();
	}

	/**
	 * Returns the replica, of the ones the load list carries, that would finish the priced request
	 * soonest at the given reasoning budget.
	 * <p>
	 * The estimate is the delay each replica publishes for the request it was taken with — the work
	 * its queue already promised, the prefill of its prompt and the decode of the budget, at the batch
	 * the machine is holding now — so the answer says which machine is nearest, not which machine is
	 * fastest in the abstract. A policy that wants to shed only as much as the nearest machine needs
	 * uses this instead of guessing.
	 *
	 * @param loads        the loads to compare, each priced for the request being decided
	 * @param outputTokens the reasoning budget the estimate prices
	 * @return the load with the smallest predicted delay, or {@code null} when none can be priced
	 */
	default ReplicaLoad soonestReplica(List<ReplicaLoad> loads, int outputTokens) {
		ReplicaLoad best = null;
		double bestSeconds = Double.MAX_VALUE;
		for (ReplicaLoad load : loads) {
			double seconds = load.predictedDelaySeconds(outputTokens);
			if (!Double.isNaN(seconds) && seconds < bestSeconds) {
				bestSeconds = seconds;
				best = load;
			}
		}
		return best;
	}

	/**
	 * Returns the delay of the soonest replica, or {@code Double.MAX_VALUE} when none can be priced.
	 * <p>
	 * The largest double is what an unpriced deployment reports, so a policy that compares it against
	 * a deadline finds nothing feasible and falls back to its own rule rather than inventing a
	 * meeting deadline.
	 *
	 * @param loads        the loads to compare, each priced for the request being decided
	 * @param outputTokens the reasoning budget the estimate prices
	 * @return the predicted delay in seconds
	 */
	default double soonestDelaySeconds(List<ReplicaLoad> loads, int outputTokens) {
		ReplicaLoad best = soonestReplica(loads, outputTokens);
		return best == null ? Double.MAX_VALUE : best.predictedDelaySeconds(outputTokens);
	}
}
