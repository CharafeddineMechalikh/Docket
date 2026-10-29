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
package com.mechalikh.genaism.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mechalikh.genaism.cost.ExecutionCostModel;
import com.mechalikh.genaism.domain.InferenceRequest;
import com.mechalikh.genaism.domain.OutcomeReason;
import com.mechalikh.genaism.domain.RequestState;
import com.mechalikh.genaism.metrics.RequestRecorder;
import com.mechalikh.genaism.policy.AdmissionDecision;
import com.mechalikh.genaism.policy.AdmissionPolicy;
import com.mechalikh.genaism.policy.ReplicaLoad;
import com.mechalikh.genaism.policy.RoutingPolicy;
import com.mechalikh.genaism.policy.SchedulingPolicy;
import com.mechalikh.pureedgesim.simulationengine.Event;
import com.mechalikh.pureedgesim.simulationengine.PureEdgeSim;
import com.mechalikh.pureedgesim.simulationengine.SimEntity;

/**
 * The entry point of a deployment: it admits requests, routes them to a replica, and is the only
 * place where a request is recorded.
 * <p>
 * Every request that reaches this entity ends its life through
 * {@link #finish(InferenceRequest, RequestState, OutcomeReason)}. Funnelling all terminal
 * outcomes through one method is what makes the invariant "no request is lost and none is
 * counted twice" checkable: a request that arrives is either still inside a replica or recorded
 * with a reason, and the counters of the two sets add up to the arrivals.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public class InferenceService extends SimEntity {

	/** Tag carrying a request that just arrived at the service. */
	public static final int REQUEST_ARRIVED = 10;
	/** Tag carrying the instant a client gives up on a request. */
	public static final int ABANDON_CHECK = 11;

	/** Model the replicas of this service serve. */
	protected final String modelId;
	/** Replicas, in creation order. */
	protected final List<ReplicaServer> replicas = new ArrayList<>();
	/** Replicas by their device identifier, so that two replicas never share a device silently. */
	protected final Map<String, ReplicaServer> replicasByDeviceId = new LinkedHashMap<>();
	/** Policy that decides whether a request enters the service. */
	protected final AdmissionPolicy admissionPolicy;
	/** Policy that decides which replica receives a request. */
	protected final RoutingPolicy routingPolicy;
	/** Policy that orders the waiting queue of every replica. */
	protected final SchedulingPolicy schedulingPolicy;
	/** Sink of the per-request rows. */
	protected final RequestRecorder recorder;
	/** Requests that reached the service, whether or not they were admitted. */
	protected long arrivedRequests;
	/** Requests recorded, used to check that nothing is lost. */
	protected long finishedRequests;

	/**
	 * Creates the service and its replicas.
	 *
	 * @param simulation      the event engine
	 * @param modelId         the model every replica serves
	 * @param specs           one specification per replica, at least one
	 * @param costModel       the model that prices the execution steps
	 * @param admissionPolicy the policy that decides whether a request enters
	 * @param routingPolicy   the policy that chooses the replica
	 * @param schedulingPolicy the policy that orders the waiting queue of a replica
	 * @param recorder        the sink of the rows
	 */
	public InferenceService(PureEdgeSim simulation, String modelId, List<ReplicaSpec> specs,
			ExecutionCostModel costModel, AdmissionPolicy admissionPolicy, RoutingPolicy routingPolicy,
			SchedulingPolicy schedulingPolicy, RequestRecorder recorder) {
		super(simulation);
		if (modelId == null || modelId.isEmpty())
			throw new IllegalArgumentException("A service needs the identifier of the model it serves");
		if (specs == null || specs.isEmpty())
			throw new IllegalArgumentException("Service for model \"" + modelId + "\" declares no replica");
		if (schedulingPolicy == null)
			throw new IllegalArgumentException("Service for model \"" + modelId + "\" declares no scheduling policy");
		this.modelId = modelId;
		this.admissionPolicy = admissionPolicy;
		this.routingPolicy = routingPolicy;
		this.schedulingPolicy = schedulingPolicy;
		this.recorder = recorder;
		for (ReplicaSpec spec : specs) {
			spec.validate();
			if (!spec.getModel().getId().equals(modelId))
				throw new IllegalArgumentException("Service for model \"" + modelId
						+ "\" received a replica of model \"" + spec.getModel().getId() + "\"");
			ReplicaServer previous = replicasByDeviceId.get(spec.getDevice().getId());
			if (previous != null)
				throw new IllegalArgumentException("Device \"" + spec.getDevice().getId() + "\" is already used by replica "
						+ previous.getId() + " of service \"" + modelId
						+ "\"; one device holds one replica, otherwise the two would share memory they each count as free");
			ReplicaServer replica = new ReplicaServer(simulation, this, spec, costModel);
			replicas.add(replica);
			replicasByDeviceId.put(spec.getDevice().getId(), replica);
		}
	}

	/**
	 * Hands an arrived request to the service at the current instant.
	 * <p>
	 * The request is not processed here: an event is scheduled with a zero delay so that the
	 * engine processes it inside the same time step, in insertion order. That keeps the arrival
	 * of two requests at the same instant deterministic.
	 *
	 * @param request the arrived request, in state {@link RequestState#CREATED}
	 */
	public void submit(InferenceRequest request) {
		schedule(this, 0.0, REQUEST_ARRIVED, request);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void processEvent(Event e) {
		InferenceRequest request = (InferenceRequest) e.getData();
		if (request == null)
			throw new IllegalStateException("Service \"" + modelId + "\" received tag " + e.getTag() + " with no request");
		switch (e.getTag()) {
		case REQUEST_ARRIVED:
			onArrival(request);
			break;
		case ABANDON_CHECK:
			onAbandonCheck(request);
			break;
		default:
			throw new IllegalArgumentException(getClass().getSimpleName() + " received unknown tag " + e.getTag());
		}
	}

	/**
	 * Admits, routes and queues a request, or ends its life with the reason it was refused.
	 *
	 * @param request the arrived request
	 */
	protected void onArrival(InferenceRequest request) {
		arrivedRequests++;
		request.transitionTo(RequestState.ARRIVED);
		ReplicaServer replica = routeTarget(request);
		if (replica == null) {
			finish(request, RequestState.REJECTED, OutcomeReason.NO_REPLICA_FOR_MODEL);
			return;
		}
		int prompt = request.getInputTokens();
		if (prompt > replica.getModel().getMaxContextTokens()) {
			finish(request, RequestState.REJECTED, OutcomeReason.CONTEXT_TOO_LONG);
			return;
		}
		if (prompt > maxCacheCapacityTokens()) {
			finish(request, RequestState.REJECTED, OutcomeReason.PROMPT_DOES_NOT_FIT);
			return;
		}
		AdmissionDecision decision = admissionPolicy.decide(request, loads(), simulation.clock());
		if (!decision.isAdmitted()) {
			finish(request, RequestState.REJECTED, decision.reason());
			return;
		}
		request.transitionTo(RequestState.ADMITTED);
		request.setAdmittedTime(simulation.clock());
		int chosen = routingPolicy.choose(loads(), request);
		if (chosen < 0) {
			finish(request, RequestState.REJECTED, OutcomeReason.ADMISSION_NO_CACHE_CAPACITY);
			return;
		}
		ReplicaServer target = replicaById(chosen);
		target.enqueue(request);
		scheduleAbandonChecks(request, target);
	}

	/**
	 * Schedules the events that end the life of a request when a client gives up or a deadline
	 * passes.
	 * <p>
	 * A target already in the past is checked at the current instant, which abandons the request
	 * as soon as it is queued: a deadline that expired before admission cannot be met, and
	 * pretending otherwise would report a request that was served.
	 *
	 * @param request  the admitted request
	 * @param replica  the replica that received it, which decides whether a missed deadline aborts
	 */
	protected void scheduleAbandonChecks(InferenceRequest request, ReplicaServer replica) {
		if (request.getCancellationTime() > 0)
			schedule(this, Math.max(0, request.getCancellationTime() - simulation.clock()), ABANDON_CHECK, request);
		if (replica.abortsOnDeadlineMiss() && request.getEndToEndDeadline() > 0)
			schedule(this, Math.max(0, request.getEndToEndDeadline() - simulation.clock()), ABANDON_CHECK, request);
	}

	/**
	 * Ends the life of a request that a client abandoned or that passed its deadline.
	 *
	 * @param request the request whose scheduled check fired
	 */
	protected void onAbandonCheck(InferenceRequest request) {
		if (request.getState().isTerminal())
			return;
		double clock = simulation.clock();
		boolean abandoned = request.getCancellationTime() > 0 && clock >= request.getCancellationTime();
		boolean overdue = request.getEndToEndDeadline() > 0 && clock >= request.getEndToEndDeadline();
		if (!abandoned && !overdue)
			return;
		ReplicaServer replica = request.getReplicaId() >= 0 ? replicaById(request.getReplicaId()) : null;
		if (replica != null)
			replica.drop(request);
		if (abandoned)
			finish(request, RequestState.CANCELLED, OutcomeReason.CLIENT_CANCELLED);
		else
			finish(request, RequestState.TIMED_OUT, OutcomeReason.DEADLINE_MISSED);
	}

	/**
	 * Ends the life of a request and writes its row.
	 * <p>
	 * The method is idempotent on purpose: a request can reach a terminal state through two paths
	 * in the same instant, a client abandoning while the replica finishes its last iteration. The
	 * first outcome wins and the second is ignored, because a request cannot have two reasons.
	 *
	 * @param request  the request ending
	 * @param outcome  the terminal state
	 * @param reason   the reason code written in the row
	 */
	public void finish(InferenceRequest request, RequestState outcome, OutcomeReason reason) {
		if (request.getState().isTerminal())
			return;
		if (request.getReplicaId() >= 0)
			replicaById(request.getReplicaId()).releaseResourcesOf(request);
		request.finish(outcome, reason.label(), simulation.clock());
		recorder.record(request);
		finishedRequests++;
	}

	/**
	 * Returns the load of every replica of the service, in replica order.
	 *
	 * @return the loads
	 */
	public List<ReplicaLoad> loads() {
		List<ReplicaLoad> loads = new ArrayList<>(replicas.size());
		for (ReplicaServer replica : replicas)
			loads.add(replica.snapshot());
		return loads;
	}

	/**
	 * Returns the largest cache of the replicas of the service, in tokens.
	 * <p>
	 * The replicas of one service may run on different devices, so a prompt that does not fit on
	 * one can still fit on another. Refusing a request that any replica could hold would hide
	 * capacity the deployment has.
	 *
	 * @return tokens
	 */
	protected int maxCacheCapacityTokens() {
		int max = 0;
		for (ReplicaServer replica : replicas)
			max = Math.max(max, replica.cacheCapacityTokens());
		return max;
	}

	/**
	 * Returns the replica that would receive the request, used for the checks a refusal needs.
	 *
	 * @param request the arrived request
	 * @return a replica serving the model of the request, or {@code null} when this service has
	 *         none
	 */
	protected ReplicaServer routeTarget(InferenceRequest request) {
		if (!request.getModelId().equals(modelId))
			return null;
		return replicas.isEmpty() ? null : replicas.get(0);
	}

	/**
	 * Returns a replica by its engine identifier.
	 *
	 * @param replicaId the identifier
	 * @return the replica
	 * @throws IllegalArgumentException when no replica has that identifier
	 */
	public ReplicaServer replicaById(int replicaId) {
		for (ReplicaServer replica : replicas)
			if (replica.getId() == replicaId)
				return replica;
		throw new IllegalArgumentException("Service \"" + modelId + "\" has no replica " + replicaId);
	}

	/**
	 * Ends the requests still inside the service when the experiment reaches its duration.
	 *
	 * @param reason why the requests end
	 * @return how many requests were still inside
	 */
	public int finishRemaining(OutcomeReason reason) {
		List<InferenceRequest> inside = new ArrayList<>();
		for (ReplicaServer replica : replicas)
			inside.addAll(replica.requestsInside());
		RequestState outcome = reason == OutcomeReason.CLIENT_CANCELLED ? RequestState.CANCELLED
				: RequestState.TIMED_OUT;
		for (InferenceRequest request : inside)
			finish(request, outcome, reason);
		return inside.size();
	}

	/**
	 * Checks that every request that arrived has been recorded, and that no replica holds
	 * resources of a recorded request.
	 *
	 * @throws IllegalStateException when a request was lost or a reservation leaked
	 */
	public void checkNoRequestIsLost() {
		for (ReplicaServer replica : replicas) {
			if (!replica.requestsInside().isEmpty())
				throw new IllegalStateException("Replica " + replica.getId() + " still holds "
						+ replica.requestsInside().size() + " requests at the end of the run");
			replica.checkCacheConsistency();
		}
		if (arrivedRequests != finishedRequests)
			throw new IllegalStateException("Service \"" + modelId + "\" saw " + arrivedRequests
					+ " arrivals but recorded " + finishedRequests + " requests; "
					+ (arrivedRequests - finishedRequests) + " requests were lost");
	}

	/**
	 * Resets the state the routing policy keeps between requests, before a repetition.
	 */
	public void resetPolicies() {
		routingPolicy.reset();
	}

	public String getModelId() {
		return modelId;
	}

	public List<ReplicaServer> getReplicas() {
		return Collections.unmodifiableList(replicas);
	}

	public AdmissionPolicy getAdmissionPolicy() {
		return admissionPolicy;
	}

	public RoutingPolicy getRoutingPolicy() {
		return routingPolicy;
	}

	public SchedulingPolicy getSchedulingPolicy() {
		return schedulingPolicy;
	}

	public RequestRecorder getRecorder() {
		return recorder;
	}

	public long getArrivedRequests() {
		return arrivedRequests;
	}

	public long getFinishedRequests() {
		return finishedRequests;
	}

	@Override
	public String toString() {
		return "InferenceService[" + modelId + ", " + replicas.size() + " replicas, arrived=" + arrivedRequests + "]";
	}
}
