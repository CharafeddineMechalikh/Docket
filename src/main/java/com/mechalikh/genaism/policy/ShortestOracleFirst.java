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
 * Orders the waiting queue by the output length the workload actually drew.
 * <p>
 * This is an oracle: no serving engine knows the length of a generation before it happens. The
 * policy exists to bound the gains a perfect length predictor could bring, so a measured
 * improvement of a real predictor can be compared with the ideal. Every artifact of a run that
 * uses it is labelled {@code [oracle]}.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public class ShortestOracleFirst implements SchedulingPolicy {

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Comparator<InferenceRequest> waitingOrder() {
		return Comparator.comparingInt((InferenceRequest r) -> r.expectedTotalTokens(false))
				.thenComparingDouble(InferenceRequest::getArrivalTime)
				.thenComparingLong(InferenceRequest::getRequestId);
	}

	@Override
	public String name() {
		return "shortest-oracle-first";
	}

	@Override
	public boolean usesOracleInformation() {
		return true;
	}
}
