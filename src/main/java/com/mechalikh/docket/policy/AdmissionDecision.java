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

import com.mechalikh.docket.domain.OutcomeReason;

/**
 * The verdict of an admission policy, with the reason behind it.
 * <p>
 * Admission is where a simulator can hide a result: accepting a request that later times out,
 * or refusing one without recording why, both change the reported latency. A decision therefore
 * always carries a reason, and the reason is written in the row of the request.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 0.1
 */
public final class AdmissionDecision {

	/** Whether the request may enter a replica queue. */
	private final boolean admitted;
	/** {@link OutcomeReason#SUCCESS} when admitted, otherwise why the request was refused. */
	private final OutcomeReason reason;

	/**
	 * Creates a decision.
	 *
	 * @param admitted the verdict
	 * @param reason   the reason, never null
	 */
	private AdmissionDecision(boolean admitted, OutcomeReason reason) {
		this.admitted = admitted;
		this.reason = reason;
	}

	/**
	 * Creates an accepting decision.
	 *
	 * @return the decision
	 */
	public static AdmissionDecision admit() {
		return new AdmissionDecision(true, OutcomeReason.SUCCESS);
	}

	/**
	 * Creates a refusing decision.
	 *
	 * @param reason why the request is refused, not {@link OutcomeReason#SUCCESS}
	 * @return the decision
	 * @throws IllegalArgumentException when the reason is null or describes a success
	 */
	public static AdmissionDecision reject(OutcomeReason reason) {
		if (reason == null || reason.isSuccess())
			throw new IllegalArgumentException("A refusal needs the reason that caused it, found " + reason);
		return new AdmissionDecision(false, reason);
	}

	/**
	 * Tells whether the request may enter a replica queue.
	 *
	 * @return {@code true} when admitted, {@code false} when refused
	 */
	public boolean isAdmitted() {
		return admitted;
	}

	/**
	 * Returns the reason recorded for the decision.
	 *
	 * @return the reason
	 */
	public OutcomeReason reason() {
		return reason;
	}

	/**
	 * Returns a short label used in logs.
	 *
	 * @return {@code admit}, or {@code reject} followed by the reason
	 */
	@Override
	public String toString() {
		return admitted ? "admit" : "reject(" + reason.label() + ")";
	}
}
