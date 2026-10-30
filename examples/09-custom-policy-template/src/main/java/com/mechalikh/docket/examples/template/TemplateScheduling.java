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
package com.mechalikh.docket.examples.template;

import java.util.Comparator;

import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.policy.SchedulingPolicy;

/**
 * The smallest complete scheduling policy, meant to be copied.
 * <p>
 * It orders the waiting queue by arrival time and then by identifier, which is the first come,
 * first served order built into the simulator. It is written out here because the useful part of
 * a policy is the four contract points around the order:
 * <ol>
 * <li>the order is total: the identifier is the last key, so two requests that tie on every
 * other key still have one determined winner and the run replays identically;</li>
 * <li>the policy is a pure function of the requests it is given: no counters, no clock, no
 * randomness;</li>
 * <li>{@link #usesOracleInformation()} answers honestly, because the artifacts label a policy
 * that reads the drawn output length as an upper bound;</li>
 * <li>the class has a public no-argument constructor, because the scenario names it.</li>
 * </ol>
 * A user copies this file, changes {@link #waitingOrder()} and the name, and points a scenario at
 * it with {@code policy.scheduling.class}.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 1.0.1
 */
public final class TemplateScheduling implements SchedulingPolicy {

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String name() {
		return "template-fcfs";
	}

	/**
	 * Returns {@code false}: the order reads only the arrival instant.
	 *
	 * @return {@code false}
	 */
	@Override
	public boolean usesOracleInformation() {
		return false;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Comparator<InferenceRequest> waitingOrder() {
		return Comparator.comparingDouble(InferenceRequest::getTime)
				.thenComparingLong(InferenceRequest::getRequestId);
	}
}
