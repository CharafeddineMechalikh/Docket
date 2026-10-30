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
package com.mechalikh.docket.metrics;

import com.mechalikh.docket.domain.InferenceRequest;

/**
 * Evaluates the service-level objectives a request states against what the request experienced.
 * <p>
 * Three answers are possible, not two: met, missed, and not applicable. A request that states no
 * deadline is not a success and not a failure, it is simply outside the objective, so the checks
 * return {@code null} for it. Counting those as met would inflate attainment, and counting them
 * as missed would deflate it; both are wrong, and the artifact leaves the field empty.
 * <p>
 * A request that never reached the measurement is a miss when it stated a target: a run that
 * meets its latency by refusing the traffic is not a run that met its latency. The summary
 * reports attainment over offered requests and over completed requests, so both readings are
 * visible.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 0.1
 */
public final class SloChecks {

	/** Not instantiable: a collection of checks over a request. */
	private SloChecks() {
	}

	/**
	 * Checks the time to the first token against the deadline of the request.
	 *
	 * @param request a recorded request
	 * @return {@code true} when the first token came before the deadline, {@code false} when it
	 *         came later or never, {@code null} when the request states no deadline
	 */
	public static Boolean ttftMet(InferenceRequest request) {
		if (request.getTtftDeadline() <= 0)
			return null;
		double ttft = request.ttftSeconds();
		return !Double.isNaN(ttft) && ttft <= request.getTtftDeadline() - request.getArrivalTime();
	}

	/**
	 * Checks the end-to-end latency against the deadline of the request.
	 *
	 * @param request a recorded request
	 * @return {@code true} when every token came before the deadline, {@code false} when the
	 *         request missed it or never completed, {@code null} when it states no deadline
	 */
	public static Boolean endToEndMet(InferenceRequest request) {
		if (request.getEndToEndDeadline() <= 0)
			return null;
		double e2e = request.endToEndSeconds();
		return !Double.isNaN(e2e) && e2e <= request.getEndToEndDeadline() - request.getArrivalTime();
	}

	/**
	 * Checks the time per output token against the target of the request.
	 * <p>
	 * A request that generated a single token has no per-token interval to measure, so the check
	 * is not applicable to it rather than satisfied.
	 *
	 * @param request a recorded request
	 * @return {@code true} when the measured pace held, {@code false} when it did not,
	 *         {@code null} when the request states no target or produced one token
	 */
	public static Boolean tpotMet(InferenceRequest request) {
		if (request.getTpotTarget() <= 0 || request.getGeneratedTokens() < 2)
			return null;
		return request.tpotSeconds() <= request.getTpotTarget();
	}

	/**
	 * Combines the three checks into the single answer a goodput count needs.
	 *
	 * @param request a recorded request
	 * @return {@code true} when every applicable check held, {@code false} when any of them
	 *         missed, {@code null} when the request states no objective at all
	 */
	public static Boolean allMet(InferenceRequest request) {
		Boolean ttft = ttftMet(request);
		Boolean endToEnd = endToEndMet(request);
		Boolean tpot = tpotMet(request);
		if (ttft == null && endToEnd == null && tpot == null)
			return null;
		return Boolean.TRUE.equals(ttft) && Boolean.TRUE.equals(endToEnd) && Boolean.TRUE.equals(tpot);
	}

	/**
	 * Formats a check for an artifact column.
	 *
	 * @param check the answer, possibly {@code null}
	 * @return {@code "true"}, {@code "false"}, or an empty field when not applicable
	 */
	public static String field(Boolean check) {
		return check == null ? "" : check.toString();
	}

	/**
	 * Counts how many of the answers are applicable, true or false.
	 *
	 * @param answers the answers of a group
	 * @return the number of applicable answers
	 */
	public static int applicable(Iterable<Boolean> answers) {
		int count = 0;
		for (Boolean answer : answers)
			if (answer != null)
				count++;
		return count;
	}

	/**
	 * Counts how many of the applicable answers are true.
	 *
	 * @param answers the answers of a group
	 * @return the number of met objectives
	 */
	public static int met(Iterable<Boolean> answers) {
		int count = 0;
		for (Boolean answer : answers)
			if (Boolean.TRUE.equals(answer))
				count++;
		return count;
	}
}
