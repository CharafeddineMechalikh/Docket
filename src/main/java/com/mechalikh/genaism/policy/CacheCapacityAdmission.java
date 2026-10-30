/**
 *     GenAIInferenceSim: a discrete-event simulator for generative AI inference serving.
 *
 *     This file is part of GenAIInferenceSim.
 *
 *     GenAIInferenceSim is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     GenAIInferenceSim is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with GenAIInferenceSim. If not, see <http://www.gnu.org/licenses/>.
 **/
package com.mechalikh.genaism.policy;

import java.util.List;

import com.mechalikh.genaism.domain.InferenceRequest;
import com.mechalikh.genaism.domain.OutcomeReason;

/**
 * Refuses a request when no replica holds enough free cache for the context it is expected to
 * reach.
 * <p>
 * The check reads the cache as it is now, not as it will be when the request starts: waiting
 * requests free their cache when they finish, so this policy is conservative. Serving engines
 * make the same trade-off, they reserve for the predicted footprint rather than promise a cache
 * that other sequences still occupy.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public class CacheCapacityAdmission implements AdmissionPolicy {

	/** Extra tokens kept free per admitted request, to avoid preempting on the next decode step. */
	protected final int reserveHeadroomTokens;

	/**
	 * Admits a request only when a replica can hold its predicted context plus the headroom.
	 *
	 * @param reserveHeadroomTokens tokens held back per sequence beyond its current context,
	 *                              zero to reserve nothing
	 */
	public CacheCapacityAdmission(int reserveHeadroomTokens) {
		if (reserveHeadroomTokens < 0)
			throw new IllegalArgumentException("The cache headroom cannot be negative, found " + reserveHeadroomTokens);
		this.reserveHeadroomTokens = reserveHeadroomTokens;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public AdmissionDecision decide(InferenceRequest request, List<ReplicaLoad> loads, double clock) {
		int needed = request.finalContextTokens(true) + reserveHeadroomTokens;
		boolean fits = loads.stream().anyMatch(load -> load.canHoldContext(needed));
		return fits ? AdmissionDecision.admit() : AdmissionDecision.reject(OutcomeReason.ADMISSION_NO_CACHE_CAPACITY);
	}

	/**
	 * Returns the policy name written in the artifacts.
	 *
	 * @return {@code cache-capacity}
	 */
	@Override
	public String name() {
		return "cache-capacity";
	}

	/**
	 * Returns the description, naming the headroom reserved per sequence.
	 *
	 * @return a one-line description
	 */
	@Override
	public String describe() {
		return name() + "(headroom " + reserveHeadroomTokens + " tokens)";
	}

	/**
	 * Returns the reserved headroom.
	 *
	 * @return tokens held back per sequence
	 */
	public int getReserveHeadroomTokens() {
		return reserveHeadroomTokens;
	}
}
