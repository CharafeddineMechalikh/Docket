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
 * Chooses which waiting request a replica prefills next.
 * <p>
 * A policy returns a total order over requests. Every order ends with the request identifier,
 * so two requests that the policy considers equal still have one determined winner: without that
 * final tie-break, the order of equal keys would depend on the internal layout of the queue and
 * the same configuration would not replay the same way.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public interface SchedulingPolicy {

	/**
	 * Returns the short name written to the artifacts.
	 *
	 * @return the policy name
	 */
	String name();

	/**
	 * Returns the order the waiting queue of a replica uses.
	 *
	 * @return a total order over requests
	 */
	Comparator<InferenceRequest> waitingOrder();

	/**
	 * Tells whether the policy reads information a production controller cannot have.
	 * <p>
	 * The drawn output length of a request is known to the simulator because the workload
	 * generated it, but no serving engine knows how many tokens a prompt will produce. A policy
	 * that uses it is an upper bound, and the artifacts label it as one.
	 *
	 * @return {@code true} when the order depends on oracle information
	 */
	boolean usesOracleInformation();

	/**
	 * Returns the description written next to the results.
	 *
	 * @return a one-line description
	 */
	default String describe() {
		return name() + (usesOracleInformation() ? " [oracle]" : "");
	}
}
