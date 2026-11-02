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

import com.mechalikh.docket.policy.ReplicaLoad;

/**
 * Packs replicas the way {@link ConsolidateRouting} does, but stops before a batch grows too deep.
 * <p>
 * Unbounded consolidation is the cheap end of the trade-off and the slow end at the same time: one
 * machine absorbs the whole load, so it amortises the weights well and keeps its neighbours idle,
 * but every request now queues behind a batch that has to step through all its sequences before it
 * reaches the next token, and a request that finds the packed machine full waits for a slot. This
 * policy keeps the batching that pays, {@code PACK_LIMIT} sequences' worth, and refuses to push a
 * replica past it: when the fullest admissible machine is already at the limit, the request opens
 * the emptiest one instead. The result is a placement that uses more replicas than pure packing but
 * far fewer than a balancer spreads across, and a batch size bounded so the step time stays close to
 * the spread case.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 1.1
 */
public final class ConsolidateWithHeadroomRouting extends ConsolidateRouting {

	/** Sequences a replica may already hold before the policy prefers to open another one. */
	static final int PACK_LIMIT = 16;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String name() {
		return "consolidate-with-headroom";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String describe() {
		return name() + " (pack onto the fullest replica below " + PACK_LIMIT
				+ " sequences, otherwise open the emptiest one)";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected ReplicaLoad pick(List<ReplicaLoad> candidates) {
		ReplicaLoad full = fullest(candidates, PACK_LIMIT);
		return full != null ? full : emptiest(candidates);
	}
}
