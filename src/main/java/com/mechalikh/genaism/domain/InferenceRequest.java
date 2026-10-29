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

import com.mechalikh.pureedgesim.simulationengine.QueueElement;

/**
 * One generative-AI inference request, from arrival to its terminal outcome.
 * <p>
 * The request carries the workload description (token counts, priority, service level),
 * the lifecycle state, and every timestamp the metrics need. Time is the simulation clock
 * in seconds, so a value of {@code 0} means "not recorded yet" and is reported as such by
 * the getters that return a duration.
 * <p>
 * State changes go through {@link #transitionTo(RequestState)}, which refuses moves that
 * would put the request in two places at once, such as queued and decoding.
 * <p>
 * This class is a {@link QueueElement} so that requests can be held by the PureEdgeSim
 * {@code FutureQueue} in arrival order.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public class InferenceRequest implements QueueElement, Comparable<InferenceRequest> {

	/** Unique identifier of the request inside one experiment. */
	protected final long requestId;
	/** Identifier of the model the request asks for. */
	protected final String modelId;
	/** Tenant that sent the request, {@code "default"} for single-tenant experiments. */
	protected String tenantId = "default";
	/** Service class of the request, used to select the SLO to evaluate against. */
	protected String serviceClass = "default";
	/** Priority, higher wins when the scheduling policy is priority based. */
	protected int priority = 0;
	/** Number of prompt tokens the request must read. */
	protected final int inputTokens;
	/** Number of tokens the request asks the model to generate. */
	protected int outputTokens = 1;
	 /** Upper bound on the generated tokens, whatever the workload drew. */
	protected int maxOutputTokens = Integer.MAX_VALUE;
	/**
	 * Output length the controller can use at admission time. It equals
	 * {@link #outputTokens} in trace replay, where the length was observed, and a sampled
	 * or configured estimate for online decisions.
	 */
	protected int predictedOutputTokens = 1;
	/** Absolute simulation time before which the first token is expected, {@code 0} for none. */
	protected double ttftDeadline = 0;
	/** Absolute simulation time before which the whole request must finish, {@code 0} for none. */
	protected double endToEndDeadline = 0;
	/** Per-token target, in seconds, {@code 0} for none. */
	protected double tpotTarget = 0;
	/** Time at which the client abandons the request, {@code 0} for never. */
	protected double cancellationTime = 0;
	/** Session identifier, used by prefix-aware extensions. Empty when the request has none. */
	protected String sessionId = "";

	/** Current lifecycle state. */
	protected RequestState state = RequestState.CREATED;
	/** Reason code recorded when the request is rejected, times out or is cancelled. */
	protected String outcomeReason = "";
	/** Identifier of the replica that served or is serving the request, {@code -1} when none. */
	protected int replicaId = -1;

	/** Insertion serial used by the {@code FutureQueue} to break timestamp ties. */
	protected long serial;

	/** Simulation time at which the request reached the service. */
	protected double arrivalTime;
	/** Simulation time at which the admission policy accepted the request. */
	protected double admittedTime;
	/** Simulation time at which the request entered the queue of its replica. */
	protected double queueEntryTime;
	/** Simulation time at which the last queueing step started, after a preemption. */
	protected double lastQueueEntryTime;
	/** Simulation time at which the first prefill chunk reached the GPU. */
	protected double prefillStartTime;
	/** Simulation time at which the prefill finished and the first token was emitted. */
	protected double firstTokenTime;
	/** Simulation time at which the last token was emitted. */
	protected double lastTokenTime;
	/** Simulation time at which the request reached a terminal state. */
	protected double terminalTime;

	/** Tokens generated so far, including the one produced by the prefill. */
	protected int generatedTokens;
	/** Number of cached tokens currently held in the KV cache for this request. */
	protected int cachedTokens;
	/** KV-cache blocks reserved for this request. */
	protected int allocatedBlocks;
	/** Sum of the decode-iteration durations observed by this request, in seconds. */
	protected double interTokenLatencySum;
	/** Number of inter-token latencies accumulated in {@link #interTokenLatencySum}. */
	protected int interTokenLatencyCount;
	/** Largest inter-token latency observed by this request, in seconds. */
	protected double maxInterTokenLatency;
	/** Total time spent waiting in replica queues, in seconds. */
	protected double queueingSeconds;
	/** Sum of the prefill durations, in seconds, including prefills replayed after preemption. */
	protected double prefillSeconds;
	/** Sum of the time spent decoding, in seconds. */
	protected double decodeSeconds;
	/** Energy attributed to this request, in joules. */
	protected double energyJoule;
	/** Number of times the request lost its cached state and had to be queued again. */
	protected int preemptionCount;

	/**
	 * Creates a request for the given model and prompt length.
	 *
	 * @param requestId    unique identifier
	 * @param modelId      model the request targets
	 * @param inputTokens  number of prompt tokens, at least one
	 */
	public InferenceRequest(long requestId, String modelId, int inputTokens) {
		if (inputTokens < 1)
			throw new IllegalArgumentException("Request " + requestId + " has " + inputTokens
					+ " input tokens, at least one is required");
		if (modelId == null || modelId.isEmpty())
			throw new IllegalArgumentException("Request " + requestId + " has no model identifier");
		this.requestId = requestId;
		this.modelId = modelId;
		this.inputTokens = inputTokens;
	}

	/**
	 * Moves the request to a new lifecycle state and refuses impossible moves.
	 *
	 * @param next the state to enter
	 * @throws IllegalStateException when the transition is not representable
	 */
	public void transitionTo(RequestState next) {
		if (!state.canTransitionTo(next))
			throw new IllegalStateException("Request " + requestId + " cannot go from " + state + " to " + next
					+ "; allowed targets are " + state.allowedNextStates());
		state = next;
	}

	/**
	 * Ends the lifecycle of the request with the given outcome and reason.
	 *
	 * @param outcome a terminal state
	 * @param reason  machine-readable reason code, or an empty string for a success
	 * @param clock   current simulation time, in seconds
	 * @throws IllegalStateException when {@code outcome} is not terminal or the request
	 *                               already reached one
	 */
	public void finish(RequestState outcome, String reason, double clock) {
		if (!outcome.isTerminal())
			throw new IllegalStateException(outcome + " is not a terminal state");
		if (state.isTerminal())
			throw new IllegalStateException("Request " + requestId + " already finished as " + state);
		transitionTo(outcome);
		outcomeReason = reason == null ? "" : reason;
		terminalTime = clock;
	}

	/**
	 * Records that one token was emitted at the given time and updates the latencies.
	 *
	 * @param clock             simulation time of the emission, in seconds
	 * @param iterationSeconds  duration of the work that produced the token, in seconds
	 */
	public void emitToken(double clock, double iterationSeconds) {
		if (generatedTokens == 0)
			firstTokenTime = clock;
		else {
			interTokenLatencySum += iterationSeconds;
			interTokenLatencyCount++;
			maxInterTokenLatency = Math.max(maxInterTokenLatency, iterationSeconds);
		}
		generatedTokens++;
		lastTokenTime = clock;
	}

	/**
	 * Total context length the request reaches once it generated everything it asks for.
	 *
	 * @param usePrediction {@code true} to use {@link #predictedOutputTokens}, which is what
	 *                      a production controller can know, {@code false} to use the drawn
	 *                      length, which is an oracle value
	 * @return the final context length, in tokens
	 */
	public int finalContextTokens(boolean usePrediction) {
		int output = Math.min(maxOutputTokens, usePrediction ? predictedOutputTokens : outputTokens);
		return inputTokens + Math.max(1, output);
	}

	/**
	 * Expected work used by scheduling policies: prompt tokens plus the output length the
	 * policy is allowed to see.
	 *
	 * @param usePrediction {@code true} for a deployable estimate, {@code false} for the
	 *                      oracle length
	 * @return the expected number of processed tokens
	 */
	public int expectedTotalTokens(boolean usePrediction) {
		return inputTokens + Math.min(maxOutputTokens, usePrediction ? predictedOutputTokens : outputTokens);
	}

	/**
	 * Time to the first token, measured from the arrival at the service.
	 *
	 * @return the TTFT in seconds, or {@code Double.NaN} when the first token was never emitted
	 */
	public double ttftSeconds() {
		if (Double.compare(firstTokenTime, 0) == 0)
			return Double.NaN;
		return firstTokenTime - arrivalTime;
	}

	/**
	 * End-to-end latency, measured from the arrival at the service to the last token.
	 *
	 * @return the latency in seconds, or {@code Double.NaN} when the request is still running
	 */
	public double endToEndSeconds() {
		if (!isCompleted())
			return Double.NaN;
		return lastTokenTime - arrivalTime;
	}

	/**
	 * Mean time per output token after the first one, the definition used by serving engines.
	 *
	 * @return the TPOT in seconds, {@code 0} when the request generated a single token
	 */
	public double tpotSeconds() {
		int tokens = generatedTokens - 1;
		if (tokens <= 0)
			return 0;
		return (lastTokenTime - firstTokenTime) / tokens;
	}

	/**
	 * Mean of the inter-token latencies observed by this request.
	 *
	 * @return the mean ITL in seconds, {@code 0} when no decode iteration was observed
	 */
	public double meanInterTokenLatency() {
		return interTokenLatencyCount == 0 ? 0 : interTokenLatencySum / interTokenLatencyCount;
	}

	/**
	 * Whether the request generated every token it asked for.
	 *
	 * @return {@code true} when the state is {@link RequestState#COMPLETED}
	 */
	public boolean isCompleted() {
		return state == RequestState.COMPLETED;
	}

	/**
	 * Whether the request is waiting or running inside the system.
	 *
	 * @return {@code true} while the lifecycle has not ended
	 */
	public boolean isInsideSystem() {
		return state.isInSystem();
	}

	/**
	 * Adds time spent waiting in a replica queue.
	 *
	 * @param seconds the waiting time to add; non-positive values are ignored
	 */
	public void addQueueingTime(double seconds) {
		if (seconds > 0)
			queueingSeconds += seconds;
	}

	/**
	 * Returns the arrival time used as the sort key when the request is held by a
	 * {@code FutureQueue}.
	 *
	 * @return the arrival time, in seconds
	 */
	public double getTime() {
		return arrivalTime;
	}

	/**
	 * Sets the arrival time of the request.
	 *
	 * @param time simulation time in seconds
	 */
	public void setArrivalTime(double time) {
		this.arrivalTime = time;
	}

	@Override
	public long getSerial() {
		return serial;
	}

	@Override
	public void setSerial(long serial) {
		this.serial = serial;
	}

	/**
	 * Orders requests by arrival time, then by the insertion serial so that two requests
	 * arriving at the same instant keep a deterministic order.
	 *
	 * @param other the request to compare with
	 * @return a negative, zero or positive value
	 */
	@Override
	public int compareTo(InferenceRequest other) {
		int byTime = Double.compare(arrivalTime, other.arrivalTime);
		return byTime != 0 ? byTime : Long.compare(serial, other.serial);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null || getClass() != obj.getClass())
			return false;
		return requestId == ((InferenceRequest) obj).requestId;
	}

	@Override
	public int hashCode() {
		return Long.hashCode(requestId);
	}

	/**
	 * A short human-readable identifier, used in the logs and in the result rows.
	 *
	 * @return {@code req-<requestId>}
	 */
	public String label() {
		return "req-" + requestId;
	}

	public long getRequestId() {
		return requestId;
	}

	public String getModelId() {
		return modelId;
	}

	public String getTenantId() {
		return tenantId;
	}

	public InferenceRequest setTenantId(String tenantId) {
		this.tenantId = tenantId == null ? "default" : tenantId;
		return this;
	}

	public String getServiceClass() {
		return serviceClass;
	}

	public InferenceRequest setServiceClass(String serviceClass) {
		this.serviceClass = serviceClass == null || serviceClass.isEmpty() ? "default" : serviceClass;
		return this;
	}

	public int getPriority() {
		return priority;
	}

	public InferenceRequest setPriority(int priority) {
		this.priority = priority;
		return this;
	}

	public int getInputTokens() {
		return inputTokens;
	}

	public int getOutputTokens() {
		return outputTokens;
	}

	public InferenceRequest setOutputTokens(int outputTokens) {
		if (outputTokens < 1)
			throw new IllegalArgumentException("Request " + requestId + " asks for " + outputTokens
					+ " generated tokens, at least one is required");
		this.outputTokens = outputTokens;
		this.predictedOutputTokens = outputTokens;
		return this;
	}

	public int getMaxOutputTokens() {
		return maxOutputTokens;
	}

	public InferenceRequest setMaxOutputTokens(int maxOutputTokens) {
		if (maxOutputTokens < 1)
			throw new IllegalArgumentException("Request " + requestId + " has a maximum output of "
					+ maxOutputTokens);
		this.maxOutputTokens = maxOutputTokens;
		return this;
	}

	public int getPredictedOutputTokens() {
		return predictedOutputTokens;
	}

	public InferenceRequest setPredictedOutputTokens(int predictedOutputTokens) {
		if (predictedOutputTokens < 1)
			throw new IllegalArgumentException("Request " + requestId + " has a predicted output of "
					+ predictedOutputTokens);
		this.predictedOutputTokens = predictedOutputTokens;
		return this;
	}

	public double getTtftDeadline() {
		return ttftDeadline;
	}

	public InferenceRequest setTtftDeadline(double ttftDeadline) {
		this.ttftDeadline = ttftDeadline;
		return this;
	}

	public double getEndToEndDeadline() {
		return endToEndDeadline;
	}

	public InferenceRequest setEndToEndDeadline(double endToEndDeadline) {
		this.endToEndDeadline = endToEndDeadline;
		return this;
	}

	public double getTpotTarget() {
		return tpotTarget;
	}

	public InferenceRequest setTpotTarget(double tpotTarget) {
		this.tpotTarget = tpotTarget;
		return this;
	}

	public double getCancellationTime() {
		return cancellationTime;
	}

	public InferenceRequest setCancellationTime(double cancellationTime) {
		this.cancellationTime = cancellationTime;
		return this;
	}

	public String getSessionId() {
		return sessionId;
	}

	public InferenceRequest setSessionId(String sessionId) {
		this.sessionId = sessionId == null ? "" : sessionId;
		return this;
	}

	public RequestState getState() {
		return state;
	}

	public String getOutcomeReason() {
		return outcomeReason;
	}

	public int getReplicaId() {
		return replicaId;
	}

	public void setReplicaId(int replicaId) {
		this.replicaId = replicaId;
	}

	public double getArrivalTime() {
		return arrivalTime;
	}

	public double getAdmittedTime() {
		return admittedTime;
	}

	public void setAdmittedTime(double admittedTime) {
		this.admittedTime = admittedTime;
	}

	public double getQueueEntryTime() {
		return queueEntryTime;
	}

	public void setQueueEntryTime(double queueEntryTime) {
		this.queueEntryTime = queueEntryTime;
		this.lastQueueEntryTime = queueEntryTime;
	}

	public double getLastQueueEntryTime() {
		return lastQueueEntryTime;
	}

	public double getPrefillStartTime() {
		return prefillStartTime;
	}

	public void setPrefillStartTime(double prefillStartTime) {
		this.prefillStartTime = prefillStartTime;
	}

	public double getFirstTokenTime() {
		return firstTokenTime;
	}

	public double getLastTokenTime() {
		return lastTokenTime;
	}

	public double getTerminalTime() {
		return terminalTime;
	}

	public int getGeneratedTokens() {
		return generatedTokens;
	}

	public int getCachedTokens() {
		return cachedTokens;
	}

	public void setCachedTokens(int cachedTokens) {
		this.cachedTokens = Math.max(0, cachedTokens);
	}

	public int getAllocatedBlocks() {
		return allocatedBlocks;
	}

	public void setAllocatedBlocks(int allocatedBlocks) {
		this.allocatedBlocks = Math.max(0, allocatedBlocks);
	}

	public double getInterTokenLatencySum() {
		return interTokenLatencySum;
	}

	public int getInterTokenLatencyCount() {
		return interTokenLatencyCount;
	}

	public double getMaxInterTokenLatency() {
		return maxInterTokenLatency;
	}

	public double getQueueingSeconds() {
		return queueingSeconds;
	}

	public double getPrefillSeconds() {
		return prefillSeconds;
	}

	public void addPrefillSeconds(double seconds) {
		prefillSeconds += seconds;
	}

	public double getDecodeSeconds() {
		return decodeSeconds;
	}

	public void addDecodeSeconds(double seconds) {
		decodeSeconds += seconds;
	}

	public double getEnergyJoule() {
		return energyJoule;
	}

	public void addEnergyJoule(double joule) {
		if (joule > 0)
			energyJoule += joule;
	}

	public int getPreemptionCount() {
		return preemptionCount;
	}

	public void incrementPreemptions() {
		preemptionCount++;
	}
}
