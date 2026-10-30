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
package com.mechalikh.docket.examples.edf;

import java.util.Comparator;

import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.policy.SchedulingPolicy;

/**
 * Orders the waiting queue by the deadline of each request, earliest first.
 * <p>
 * A request has two deadlines and both are absolute instants: the one for its first token and
 * the one for its last. The policy sorts on the earlier of the two, which is the moment by which
 * the request must at least have started, and falls back to the end-to-end deadline when only
 * that one was stated. Requests that state no deadline at all sort last, after every request that
 * does, because a request with no objective cannot be urgent.
 * <p>
 * The order never reads the drawn output length, so it is a policy a production controller could
 * run: the deadlines are part of the request contract, not knowledge the simulator invented.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 1.0.1
 */
public final class EarliestDeadlineFirst implements SchedulingPolicy {

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String name() {
		return "edf";
	}

	/**
	 * Returns {@code false}: the order uses the deadlines the client stated, not the generated
	 * length the simulator drew.
	 *
	 * @return {@code false}
	 */
	@Override
	public boolean usesOracleInformation() {
		return false;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The identifier is the final key, so two requests with the same deadline stay in a
	 * reproducible order.
	 */
	@Override
	public Comparator<InferenceRequest> waitingOrder() {
		return Comparator.comparingDouble(EarliestDeadlineFirst::earliestDeadline)
				.thenComparing(Comparator.comparingInt(InferenceRequest::getPriority).reversed())
				.thenComparingLong(InferenceRequest::getRequestId);
	}

	/**
	 * Returns the instant by which the request must have started, or the largest value when it
	 * states no deadline.
	 *
	 * @param request the request
	 * @return the earliest of its two deadlines, in simulation seconds
	 */
	static double earliestDeadline(InferenceRequest request) {
		double ttft = request.getTtftDeadline();
		double endToEnd = request.getEndToEndDeadline();
		if (ttft > 0 && endToEnd > 0)
			return Math.min(ttft, endToEnd);
		if (ttft > 0)
			return ttft;
		if (endToEnd > 0)
			return endToEnd;
		return Double.MAX_VALUE;
	}
}
