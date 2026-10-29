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
 * Prefills the request expected to hold the device for the shortest time, using the output
 * length the request declares at admission.
 * <p>
 * The declared length is what a controller can know: a trace records the observed length of a
 * past request, and an online estimator predicts one for a live prompt. This policy does not see
 * the length the workload drew, which {@link ShortestOracleFirst} uses.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public class ShortestExpectedFirst implements SchedulingPolicy {

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Comparator<InferenceRequest> waitingOrder() {
		return Comparator.comparingInt((InferenceRequest r) -> r.expectedTotalTokens(true))
				.thenComparingDouble(InferenceRequest::getArrivalTime)
				.thenComparingLong(InferenceRequest::getRequestId);
	}

	@Override
	public String name() {
		return "shortest-expected-first";
	}

	@Override
	public boolean usesOracleInformation() {
		return false;
	}
}
