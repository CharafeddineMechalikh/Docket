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
 * One line of a recorded workload trace, as the replay needs it.
 * <p>
 * A trace row carries the arrival instant and the request shape. The output length of a recorded
 * request is known because it happened, so replaying a trace gives the simulator an oracle value;
 * the field that a controller can use at admission time is the prediction, which the workload
 * plan derives from the observed length with the configured predictor quality.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public final class TraceRecord {

	/** Arrival instant, in seconds from the start of the replay. */
	private final double arrivalTime;
	/** Model the recorded request targeted. */
	private final String modelId;
	/** Prompt length of the recorded request. */
	private final int inputTokens;
	/** Output length the recorded request produced, which is an oracle value on replay. */
	private final int outputTokens;
	/** Priority of the recorded request. */
	private final int priority;
	/** Service class the recorded request belonged to. */
	private final String serviceClass;
	/** Session the recorded request belonged to, empty when the trace has no session column. */
	private final String sessionId;
	/** Time-to-first-token target, in seconds from the arrival, zero when unstated. */
	private final double ttftTarget;
	/** End-to-end target, in seconds from the arrival, zero when unstated. */
	private final double endToEndTarget;
	/** Per-token target, in seconds, zero when unstated. */
	private final double tpotTarget;
	/** Client abandonment delay after the arrival, in seconds, zero when the client never gives up. */
	private final double cancellationDelay;

	/**
	 * Creates a trace row.
	 *
	 * @param arrivalTime        arrival instant, in seconds, zero or more
	 * @param modelId            the model the request targeted, not empty
	 * @param inputTokens        prompt length, at least one
	 * @param outputTokens       observed output length, at least one
	 * @param priority           the priority
	 * @param serviceClass       the service class, empty for the default one
	 * @param sessionId          the session, empty when unstated
	 * @param ttftTarget         time-to-first-token target in seconds, zero when unstated
	 * @param endToEndTarget     end-to-end target in seconds, zero when unstated
	 * @param tpotTarget         per-token target in seconds, zero when unstated
	 * @param cancellationDelay  abandonment delay after arrival, zero when never
	 */
	public TraceRecord(double arrivalTime, String modelId, int inputTokens, int outputTokens, int priority,
			String serviceClass, String sessionId, double ttftTarget, double endToEndTarget, double tpotTarget,
			double cancellationDelay) {
		if (arrivalTime < 0 || Double.isNaN(arrivalTime))
			throw new IllegalArgumentException("A trace row cannot arrive at time " + arrivalTime);
		if (inputTokens < 1)
			throw new IllegalArgumentException("A trace row at t=" + arrivalTime + " holds a prompt of " + inputTokens
					+ " tokens");
		if (outputTokens < 1)
			throw new IllegalArgumentException("A trace row at t=" + arrivalTime + " produced " + outputTokens
					+ " tokens");
		this.arrivalTime = arrivalTime;
		this.modelId = modelId;
		this.inputTokens = inputTokens;
		this.outputTokens = outputTokens;
		this.priority = priority;
		this.serviceClass = serviceClass == null || serviceClass.isEmpty() ? "default" : serviceClass;
		this.sessionId = sessionId == null ? "" : sessionId;
		this.ttftTarget = positiveOrZero(ttftTarget, "ttft target", arrivalTime);
		this.endToEndTarget = positiveOrZero(endToEndTarget, "end-to-end target", arrivalTime);
		this.tpotTarget = positiveOrZero(tpotTarget, "per-token target", arrivalTime);
		this.cancellationDelay = positiveOrZero(cancellationDelay, "cancellation delay", arrivalTime);
	}

	/**
	 * Rejects a negative target, which would silently invert a check.
	 *
	 * @param value       the declared value
	 * @param name        what the value is
	 * @param arrivalTime the row the value belongs to
	 * @return the value
	 */
	private static double positiveOrZero(double value, String name, double arrivalTime) {
		if (value < 0 || Double.isNaN(value))
			throw new IllegalArgumentException("A trace row at t=" + arrivalTime + " declares a " + name + " of "
					+ value);
		return value;
	}

	public double arrivalTime() {
		return arrivalTime;
	}

	public String modelId() {
		return modelId;
	}

	public int inputTokens() {
		return inputTokens;
	}

	public int outputTokens() {
		return outputTokens;
	}

	public int priority() {
		return priority;
	}

	public String serviceClass() {
		return serviceClass;
	}

	public String sessionId() {
		return sessionId;
	}

	public double ttftTarget() {
		return ttftTarget;
	}

	public double endToEndTarget() {
		return endToEndTarget;
	}

	public double tpotTarget() {
		return tpotTarget;
	}

	public double cancellationDelay() {
		return cancellationDelay;
	}

	@Override
	public String toString() {
		return "TraceRecord[t=" + arrivalTime + ", " + modelId + ", in=" + inputTokens + ", out=" + outputTokens + "]";
	}
}
