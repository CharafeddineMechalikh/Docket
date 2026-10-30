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

import java.util.Comparator;

import com.mechalikh.genaism.domain.InferenceRequest;

/**
 * Prefills the highest priority request first, breaking ties by arrival time.
 * <p>
 * Priorities come from the workload: a trace column, a service class default, or an explicit
 * value per request. The policy never invents one.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public class PriorityFirst implements SchedulingPolicy {

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Comparator<InferenceRequest> waitingOrder() {
		return Comparator.comparingInt(InferenceRequest::getPriority).reversed()
				.thenComparingDouble(InferenceRequest::getArrivalTime)
				.thenComparingLong(InferenceRequest::getRequestId);
	}

	/**
	 * Returns the policy name written in the artifacts.
	 *
	 * @return {@code priority}
	 */
	@Override
	public String name() {
		return "priority";
	}

	/**
	 * Priorities are stated by the workload, so the order uses no oracle information.
	 *
	 * @return always {@code false}
	 */
	@Override
	public boolean usesOracleInformation() {
		return false;
	}
}
