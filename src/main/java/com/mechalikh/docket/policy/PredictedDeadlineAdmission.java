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
import com.mechalikh.docket.domain.OutcomeReason;

/**
 * Refuses a request whose time-to-first-token deadline cannot be met given the work the least
 * loaded replica already promised.
 * <p>
 * A request with no deadline is always admitted: refusing it would invent a deadline the workload
 * never stated. The prediction is the pending work of the queue plus the own prefill of the
 * request, which is a lower bound on the wait because the running sequences continue to decode
 * while the request waits.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 0.1
 */
public class PredictedDeadlineAdmission implements AdmissionPolicy {

	/**
	 * Fraction of the predicted wait the policy is allowed to be wrong by, so that a request is
	 * refused only when it misses the deadline by more than this margin.
	 */
	protected final double tolerance;

	/**
	 * Sets the tolerance of the prediction.
	 *
	 * @param tolerance a ratio, for instance {@code 0.1} to refuse only when the predicted wait
	 *                  exceeds the deadline by ten percent; zero or more
	 */
	public PredictedDeadlineAdmission(double tolerance) {
		if (tolerance < 0 || Double.isNaN(tolerance))
			throw new IllegalArgumentException("The deadline tolerance cannot be negative, found " + tolerance);
		this.tolerance = tolerance;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public AdmissionDecision decide(InferenceRequest request, List<ReplicaLoad> loads, double clock) {
		double deadline = request.getTtftDeadline();
		if (deadline <= 0)
			return AdmissionDecision.admit();
		double leastPendingWork = loads.stream().mapToDouble(ReplicaLoad::pendingWorkSeconds).min().orElse(0);
		double slack = deadline - clock;
		if (slack < 0)
			return AdmissionDecision.reject(OutcomeReason.ADMISSION_DEADLINE_INFEASIBLE);
		return leastPendingWork > slack * (1 + tolerance)
				? AdmissionDecision.reject(OutcomeReason.ADMISSION_DEADLINE_INFEASIBLE)
				: AdmissionDecision.admit();
	}

	/**
	 * Returns the policy name written in the artifacts.
	 *
	 * @return {@code predicted-deadline}
	 */
	@Override
	public String name() {
		return "predicted-deadline";
	}

	/**
	 * Returns the description, naming the tolerance applied to the slack.
	 *
	 * @return a one-line description
	 */
	@Override
	public String describe() {
		return name() + "(tolerance " + tolerance + ")";
	}

	/**
	 * Returns the configured tolerance.
	 *
	 * @return the ratio applied to the slack
	 */
	public double getTolerance() {
		return tolerance;
	}
}
