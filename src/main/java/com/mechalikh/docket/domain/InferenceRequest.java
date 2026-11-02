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
package com.mechalikh.docket.domain;

import com.mechalikh.pureedgesim.simulationengine.AbstractQueueElement;

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
 * This class extends the PureEdgeSim {@link AbstractQueueElement}, so requests
 * are held by the engine {@code FutureQueue} in arrival order: the base orders
 * any two queue elements by their time key, {@link #getTime()} here, and breaks
 * ties with the insertion serial the queue assigns.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 0.1
 */
public class InferenceRequest extends AbstractQueueElement<InferenceRequest> {

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
	/** Seconds the prompt spent crossing the access link, zero when the deployment models none. */
	protected double uplinkSeconds;
	/** Seconds the response spent crossing the access link, zero when none was sent. */
	protected double downlinkSeconds;
	/** Simulation time at which the client held the whole response, {@code 0} until it was sent. */
	protected double deliveryTime;

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
	 * <p>
	 * A deployment with an access link measures from the instant the client released the request,
	 * so the time the prompt spent crossing the link is part of the answer: a client counts its
	 * own wait, not the moment its prompt reached the device.
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
	@Override
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

	/**
	 * Two requests are equal when they carry the same identifier, which is unique inside one
	 * experiment.
	 *
	 * @param obj the object to compare with
	 * @return {@code true} when {@code obj} is a request with the same identifier
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null || getClass() != obj.getClass())
			return false;
		return requestId == ((InferenceRequest) obj).requestId;
	}

	/**
	 * Hash code derived from the request identifier, consistent with
	 * {@link #equals(Object)}.
	 *
	 * @return the hash of the identifier
	 */
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

	/**
	 * Identifier that distinguishes this request from every other one in the same experiment.
	 *
	 * @return the request identifier
	 */
	public long getRequestId() {
		return requestId;
	}

	/**
	 * Model this request is addressed to, which determines the replica set able to serve it.
	 *
	 * @return the model identifier
	 */
	public String getModelId() {
		return modelId;
	}

	/**
	 * Tenant that issued the request, used by policies that separate or cap tenants.
	 *
	 * @return the tenant identifier, {@code "default"} when the request was not assigned one
	 */
	public String getTenantId() {
		return tenantId;
	}

	/**
	 * Assigns the tenant that issued the request.
	 *
	 * @param tenantId tenant identifier, {@code null} falls back to {@code "default"}
	 * @return this request, so that setters can be chained
	 */
	public InferenceRequest setTenantId(String tenantId) {
		this.tenantId = tenantId == null ? "default" : tenantId;
		return this;
	}

	/**
	 * Service class of the request, which selects the service level objective it is
	 * evaluated against.
	 *
	 * @return the service class name, {@code "default"} when it was not set
	 */
	public String getServiceClass() {
		return serviceClass;
	}

	/**
	 * Assigns the service class used to pick the service level objective for this request.
	 *
	 * @param serviceClass service class name, {@code null} or empty falls back to
	 *                     {@code "default"}
	 * @return this request, so that setters can be chained
	 */
	public InferenceRequest setServiceClass(String serviceClass) {
		this.serviceClass = serviceClass == null || serviceClass.isEmpty() ? "default" : serviceClass;
		return this;
	}

	/**
	 * Scheduling priority of the request, meaningful only for priority based policies.
	 *
	 * @return the priority, {@code 0} when the workload did not specify one; higher wins
	 */
	public int getPriority() {
		return priority;
	}

	/**
	 * Assigns the scheduling priority, consulted by priority aware policies when they must
	 * choose between waiting requests.
	 *
	 * @param priority the priority, higher values are served first, {@code 0} for a neutral
	 *                 request
	 * @return this request, so that setters can be chained
	 */
	public InferenceRequest setPriority(int priority) {
		this.priority = priority;
		return this;
	}

	/**
	 * Length of the prompt, which drives the prefill cost of the request.
	 *
	 * @return the number of prompt tokens, always at least one
	 */
	public int getInputTokens() {
		return inputTokens;
	}

	/**
	 * Number of tokens the workload asks the model to generate, known exactly when a trace
	 * is replayed.
	 *
	 * @return the requested output length, in tokens
	 */
	public int getOutputTokens() {
		return outputTokens;
	}

	/**
	 * Sets the output length requested by the workload. The predicted length used by online
	 * controllers is reset to the same value.
	 *
	 * @param outputTokens number of tokens to generate, at least one
	 * @return this request, so that setters can be chained
	 * @throws IllegalArgumentException when {@code outputTokens} is below one
	 */
	public InferenceRequest setOutputTokens(int outputTokens) {
		if (outputTokens < 1)
			throw new IllegalArgumentException("Request " + requestId + " asks for " + outputTokens
					+ " generated tokens, at least one is required");
		this.outputTokens = outputTokens;
		this.predictedOutputTokens = outputTokens;
		return this;
	}

	/**
	 * Hard upper bound the generator may never exceed, whatever the workload drew.
	 *
	 * @return the maximum output length, in tokens, {@link Integer#MAX_VALUE} when unbounded
	 */
	public int getMaxOutputTokens() {
		return maxOutputTokens;
	}

	/**
	 * Sets the hard cap on the generated tokens, which truncates the request when the drawn
	 * length is larger.
	 *
	 * @param maxOutputTokens the cap, in tokens, at least one
	 * @return this request, so that setters can be chained
	 * @throws IllegalArgumentException when {@code maxOutputTokens} is below one
	 */
	public InferenceRequest setMaxOutputTokens(int maxOutputTokens) {
		if (maxOutputTokens < 1)
			throw new IllegalArgumentException("Request " + requestId + " has a maximum output of "
					+ maxOutputTokens);
		this.maxOutputTokens = maxOutputTokens;
		return this;
	}

	/**
	 * Output length the controller is allowed to know at admission time, as opposed to the
	 * oracle length drawn by the workload.
	 *
	 * @return the predicted output length, in tokens
	 */
	public int getPredictedOutputTokens() {
		return predictedOutputTokens;
	}

	/**
	 * Sets the output length the controller may use before the request completes.
	 *
	 * @param predictedOutputTokens the estimate, in tokens, at least one
	 * @return this request, so that setters can be chained
	 * @throws IllegalArgumentException when {@code predictedOutputTokens} is below one
	 */
	public InferenceRequest setPredictedOutputTokens(int predictedOutputTokens) {
		if (predictedOutputTokens < 1)
			throw new IllegalArgumentException("Request " + requestId + " has a predicted output of "
					+ predictedOutputTokens);
		this.predictedOutputTokens = predictedOutputTokens;
		return this;
	}

	/**
	 * Absolute time by which the first token is expected.
	 *
	 * @return the deadline as a simulation time, in seconds, {@code 0} when none applies
	 */
	public double getTtftDeadline() {
		return ttftDeadline;
	}

	/**
	 * Sets the absolute time by which the first token is expected.
	 *
	 * @param ttftDeadline the deadline as a simulation time, in seconds, {@code 0} to disable
	 *                     it
	 * @return this request, so that setters can be chained
	 */
	public InferenceRequest setTtftDeadline(double ttftDeadline) {
		this.ttftDeadline = ttftDeadline;
		return this;
	}

	/**
	 * Absolute time by which the whole request, last token included, must finish.
	 *
	 * @return the deadline as a simulation time, in seconds, {@code 0} when none applies
	 */
	public double getEndToEndDeadline() {
		return endToEndDeadline;
	}

	/**
	 * Sets the absolute time by which the whole request must finish.
	 *
	 * @param endToEndDeadline the deadline as a simulation time, in seconds, {@code 0} to
	 *                         disable it
	 * @return this request, so that setters can be chained
	 */
	public InferenceRequest setEndToEndDeadline(double endToEndDeadline) {
		this.endToEndDeadline = endToEndDeadline;
		return this;
	}

	/**
	 * Per-token latency target the request is graded against.
	 *
	 * @return the target, in seconds per output token, {@code 0} when none applies
	 */
	public double getTpotTarget() {
		return tpotTarget;
	}

	/**
	 * Sets the per-token latency target used to grade the request.
	 *
	 * @param tpotTarget the target, in seconds per output token, {@code 0} to disable it
	 * @return this request, so that setters can be chained
	 */
	public InferenceRequest setTpotTarget(double tpotTarget) {
		this.tpotTarget = tpotTarget;
		return this;
	}

	/**
	 * Absolute time at which the client stops waiting and abandons the request.
	 *
	 * @return the cancellation time as a simulation time, in seconds, {@code 0} when the
	 *         request is never cancelled
	 */
	public double getCancellationTime() {
		return cancellationTime;
	}

	/**
	 * Sets the absolute time at which the client abandons the request.
	 *
	 * @param cancellationTime the cancellation time as a simulation time, in seconds,
	 *                         {@code 0} to never cancel
	 * @return this request, so that setters can be chained
	 */
	public InferenceRequest setCancellationTime(double cancellationTime) {
		this.cancellationTime = cancellationTime;
		return this;
	}

	/**
	 * Session the request belongs to, read by prefix-aware extensions that reuse cached
	 * context across successive requests.
	 *
	 * @return the session identifier, empty when the request has no session
	 */
	public String getSessionId() {
		return sessionId;
	}

	/**
	 * Associates the request with a session, or clears the association.
	 *
	 * @param sessionId the session identifier, {@code null} is stored as an empty string
	 * @return this request, so that setters can be chained
	 */
	public InferenceRequest setSessionId(String sessionId) {
		this.sessionId = sessionId == null ? "" : sessionId;
		return this;
	}

	/**
	 * Lifecycle state the request currently occupies.
	 *
	 * @return the current state, {@link RequestState#CREATED} before the request is admitted
	 */
	public RequestState getState() {
		return state;
	}

	/**
	 * Machine-readable code explaining why the request ended, for example the rejection or
	 * timeout cause.
	 *
	 * @return the reason code, empty when the request succeeded or has not finished yet
	 */
	public String getOutcomeReason() {
		return outcomeReason;
	}

	/**
	 * Replica that serves, or served, the request.
	 *
	 * @return the replica identifier, {@code -1} when no replica was assigned yet
	 */
	public int getReplicaId() {
		return replicaId;
	}

	/**
	 * Records the replica chosen for the request, by the placement or routing policy.
	 *
	 * @param replicaId the replica identifier, {@code -1} to mark the request as unassigned
	 */
	public void setReplicaId(int replicaId) {
		this.replicaId = replicaId;
	}

	/**
	 * Time at which the request reached the service, the origin of TTFT and end-to-end
	 * latency.
	 *
	 * @return the arrival time as a simulation time, in seconds
	 */
	public double getArrivalTime() {
		return arrivalTime;
	}

	/**
	 * Time at which the admission policy accepted the request.
	 *
	 * @return the admission time as a simulation time, in seconds, {@code 0} when the request
	 *         has not been admitted
	 */
	public double getAdmittedTime() {
		return admittedTime;
	}

	/**
	 * Records the time at which the admission policy accepted the request.
	 *
	 * @param admittedTime the admission time as a simulation time, in seconds
	 */
	public void setAdmittedTime(double admittedTime) {
		this.admittedTime = admittedTime;
	}

	/**
	 * Time at which the request first entered the queue of its replica.
	 *
	 * @return the queue entry time as a simulation time, in seconds, {@code 0} when the
	 *         request was never queued
	 */
	public double getQueueEntryTime() {
		return queueEntryTime;
	}

	/**
	 * Records that the request entered a replica queue. The same value becomes the start of
	 * the current queueing step.
	 *
	 * @param queueEntryTime the queue entry time as a simulation time, in seconds
	 */
	public void setQueueEntryTime(double queueEntryTime) {
		this.queueEntryTime = queueEntryTime;
		this.lastQueueEntryTime = queueEntryTime;
	}

	/**
	 * Start of the most recent queueing step, which moves forward when a preempted request is
	 * queued again.
	 *
	 * @return the time as a simulation time, in seconds, {@code 0} when the request was never
	 *         queued
	 */
	public double getLastQueueEntryTime() {
		return lastQueueEntryTime;
	}

	/**
	 * Time at which the first prefill chunk of the request reached the GPU.
	 *
	 * @return the prefill start as a simulation time, in seconds, {@code 0} when prefill has
	 *         not started
	 */
	public double getPrefillStartTime() {
		return prefillStartTime;
	}

	/**
	 * Records the time at which the first prefill chunk reached the GPU.
	 *
	 * @param prefillStartTime the start time as a simulation time, in seconds
	 */
	public void setPrefillStartTime(double prefillStartTime) {
		this.prefillStartTime = prefillStartTime;
	}

	/**
	 * Time at which the first token was emitted, the end of prefill from the client view.
	 *
	 * @return the time as a simulation time, in seconds, {@code 0} while no token exists
	 */
	public double getFirstTokenTime() {
		return firstTokenTime;
	}

	/**
	 * Time at which the last token of the request was emitted.
	 *
	 * @return the time as a simulation time, in seconds, {@code 0} while no token exists
	 */
	public double getLastTokenTime() {
		return lastTokenTime;
	}

	/**
	 * Time at which the request reached its terminal state, completion or otherwise.
	 *
	 * @return the time as a simulation time, in seconds, {@code 0} while the request is still
	 *         inside the system
	 */
	public double getTerminalTime() {
		return terminalTime;
	}

	/**
	 * Time the prompt spent crossing the access link between the client and the device, counting
	 * the wait behind the transfers of the requests ahead of it.
	 * <p>
	 * The wait starts when the client releases the request, which is {@link #getArrivalTime()}, so
	 * the first-token and end-to-end latencies already contain this figure. It is reported on its
	 * own to separate the time the network cost from the time the deployment did.
	 *
	 * @return seconds, zero when the deployment declares no link
	 */
	public double getUplinkSeconds() {
		return uplinkSeconds;
	}

	/**
	 * Time the response spent crossing the access link on its way back to the client.
	 *
	 * @return seconds, zero when no response was sent
	 */
	public double getDownlinkSeconds() {
		return downlinkSeconds;
	}

	/**
	 * Instant the client held the whole response, which is the end of the request from its point of
	 * view and therefore later than {@link #getTerminalTime()} by the downlink time.
	 *
	 * @return the time as a simulation time, in seconds, {@code 0} while the response was not sent
	 */
	public double getDeliveryTime() {
		return deliveryTime;
	}

	/**
	 * Sets the instant the client held the response.
	 *
	 * @param deliveryTime the time as a simulation time, in seconds
	 */
	public void setDeliveryTime(double deliveryTime) {
		this.deliveryTime = deliveryTime;
	}

	/**
	 * Adds time the prompt spent crossing the access link.
	 *
	 * @param seconds the uplink time to add, in seconds; non-positive values are ignored
	 */
	public void addUplinkSeconds(double seconds) {
		if (seconds > 0)
			uplinkSeconds += seconds;
	}

	/**
	 * Adds time the response spent crossing the access link.
	 *
	 * @param seconds the downlink time to add, in seconds; non-positive values are ignored
	 */
	public void addDownlinkSeconds(double seconds) {
		if (seconds > 0)
			downlinkSeconds += seconds;
	}

	/**
	 * Tokens generated so far, the first one being produced by the prefill.
	 *
	 * @return the number of tokens generated, in tokens, {@code 0} before the first one
	 */
	public int getGeneratedTokens() {
		return generatedTokens;
	}

	/**
	 * Tokens of the context currently held in the KV cache for this request, which is what a
	 * preemption would discard.
	 *
	 * @return the number of cached tokens, in tokens
	 */
	public int getCachedTokens() {
		return cachedTokens;
	}

	/**
	 * Records how much of the context is currently cached for the request.
	 *
	 * @param cachedTokens the number of cached tokens, negative values are clamped to
	 *                     {@code 0}
	 */
	public void setCachedTokens(int cachedTokens) {
		this.cachedTokens = Math.max(0, cachedTokens);
	}

	/**
	 * KV-cache blocks currently reserved for the request, used by the memory admission check.
	 *
	 * @return the number of blocks held
	 */
	public int getAllocatedBlocks() {
		return allocatedBlocks;
	}

	/**
	 * Records the KV-cache blocks reserved for the request.
	 *
	 * @param allocatedBlocks the number of blocks, negative values are clamped to {@code 0}
	 */
	public void setAllocatedBlocks(int allocatedBlocks) {
		this.allocatedBlocks = Math.max(0, allocatedBlocks);
	}

	/**
	 * Sum of the decode-iteration durations observed after the first token.
	 *
	 * @return the accumulated inter-token latency, in seconds
	 */
	public double getInterTokenLatencySum() {
		return interTokenLatencySum;
	}

	/**
	 * Number of decode iterations whose duration was accumulated, the denominator of the
	 * mean inter-token latency.
	 *
	 * @return the count, {@code 0} when no decode iteration was observed
	 */
	public int getInterTokenLatencyCount() {
		return interTokenLatencyCount;
	}

	/**
	 * Worst inter-token latency observed by the request, useful for tail analysis.
	 *
	 * @return the maximum inter-token latency, in seconds, {@code 0} when none was observed
	 */
	public double getMaxInterTokenLatency() {
		return maxInterTokenLatency;
	}

	/**
	 * Total time the request spent waiting in replica queues, across every queueing step
	 * including those that followed a preemption.
	 *
	 * @return the waiting time, in seconds
	 */
	public double getQueueingSeconds() {
		return queueingSeconds;
	}

	/**
	 * Total prefill work done for the request, including prefills replayed after a
	 * preemption.
	 *
	 * @return the prefill time, in seconds
	 */
	public double getPrefillSeconds() {
		return prefillSeconds;
	}

	/**
	 * Adds one prefill duration to the total, a preempted request may accumulate several.
	 *
	 * @param seconds the prefill duration to add, in seconds
	 */
	public void addPrefillSeconds(double seconds) {
		prefillSeconds += seconds;
	}

	/**
	 * Total time the request spent decoding tokens.
	 *
	 * @return the decode time, in seconds
	 */
	public double getDecodeSeconds() {
		return decodeSeconds;
	}

	/**
	 * Adds one decoding duration to the total.
	 *
	 * @param seconds the decode duration to add, in seconds
	 */
	public void addDecodeSeconds(double seconds) {
		decodeSeconds += seconds;
	}

	/**
	 * Energy attributed to the request by the power model while it occupied a replica.
	 *
	 * @return the energy, in joules
	 */
	public double getEnergyJoule() {
		return energyJoule;
	}

	/**
	 * Adds energy consumed on behalf of the request.
	 *
	 * @param joule the energy to add, in joules; non-positive values are ignored
	 */
	public void addEnergyJoule(double joule) {
		if (joule > 0)
			energyJoule += joule;
	}

	/**
	 * Number of times the request lost its cached state, for example because its KV-cache
	 * blocks were reclaimed, and had to be queued again.
	 *
	 * @return the preemption count, {@code 0} when the request was never preempted
	 */
	public int getPreemptionCount() {
		return preemptionCount;
	}

	/**
	 * Records one more preemption of the request.
	 */
	public void incrementPreemptions() {
		preemptionCount++;
	}
}
