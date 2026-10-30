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
package com.mechalikh.genaism.domain;

/**
 * The machine-readable reason a request left the system in a non-success state.
 * <p>
 * Outcome reasons are part of the result, not a detail: a run that meets its latency target by
 * rejecting half of its traffic is not a faster deployment, so every artifact records which
 * reason produced which outcome.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public enum OutcomeReason {

	/** Every token the request asked for was generated. */
	SUCCESS("success"),
	/** The admission policy refused the request before it reached a replica queue. */
	ADMISSION_QUEUE_LIMIT("admission_queue_limit"),
	/** No replica could reserve the cache the request needs, even before being queued. */
	ADMISSION_NO_CACHE_CAPACITY("admission_no_cache_capacity"),
	/** The admission policy predicted that the deadline could not be met. */
	ADMISSION_DEADLINE_INFEASIBLE("admission_deadline_infeasible"),
	/** The deployment has no replica serving the requested model. */
	NO_REPLICA_FOR_MODEL("no_replica_for_model"),
	/** The prompt is longer than the context the model accepts. */
	CONTEXT_TOO_LONG("prompt_exceeds_model_context"),
	/** The prompt alone cannot fit in the cache pool of any replica. */
	PROMPT_DOES_NOT_FIT("prompt_exceeds_cache_capacity"),
	/** The client gave up before the request finished. */
	CLIENT_CANCELLED("client_cancelled"),
	/** The request passed its end-to-end deadline and the deployment aborted it. */
	DEADLINE_MISSED("deadline_missed"),
	/** The request was still running when the experiment reached its duration. */
	SIMULATION_ENDED("simulation_ended"),
	/** Generation stopped because the context window of the model, or the cache pool of the device, was full before every requested token. */
	CONTEXT_LIMIT_REACHED("context_limit_reached"),
	/** The request was preempted to free cache and could not be re-admitted before the end. */
	NOT_RESUMBED("preempted_not_resumed");

	/** Label written to the artifacts. */
	private final String label;

	OutcomeReason(String label) {
		this.label = label;
	}

	/**
	 * Returns the label used in the artifacts and in the logs.
	 *
	 * @return the reason code
	 */
	public String label() {
		return label;
	}

	/**
	 * Tells whether the reason describes a request that finished normally.
	 *
	 * @return {@code true} only for {@link #SUCCESS}
	 */
	public boolean isSuccess() {
		return this == SUCCESS;
	}

	/**
	 * Returns the label recorded in the artifacts for this outcome, so that a reader can classify a
	 * rejection without mapping the enumeration constant by hand.
	 *
	 * @return the reason code
	 */
	@Override
	public String toString() {
		return label;
	}
}
