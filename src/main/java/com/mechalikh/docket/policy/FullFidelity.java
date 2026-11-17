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
 * Grants every request the reasoning budget the workload asked for, which is the absence of a
 * fidelity decision.
 * <p>
 * This is the default. A scenario that names no fidelity policy is served by a deployment that never
 * sheds work: the generated length, the cache the request reaches, the work the queue promises and
 * the energy the sequence draws are all exactly what they were before the mechanism existed.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 1.1
 */
public class FullFidelity implements FidelityPolicy {

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String name() {
		return "full";
	}

	/**
	 * Returns zero, the answer that leaves the request alone.
	 *
	 * @return {@code 0}
	 */
	@Override
	public int chooseBudget(InferenceRequest request, List<ReplicaLoad> loads, double clock) {
		return 0;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String describe() {
		return name() + " (every request generates the length the workload asked for)";
	}
}
