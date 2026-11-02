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
package com.mechalikh.docket.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mechalikh.docket.cost.ExecutionCostModel;
import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.domain.ModelProfile;
import com.mechalikh.docket.domain.OutcomeReason;
import com.mechalikh.docket.domain.RequestState;
import com.mechalikh.docket.metrics.RequestRecorder;
import com.mechalikh.docket.network.AccessLink;
import com.mechalikh.docket.network.LinkProfile;
import com.mechalikh.docket.policy.AdmissionDecision;
import com.mechalikh.docket.policy.AdmissionPolicy;
import com.mechalikh.docket.policy.ReplicaLoad;
import com.mechalikh.docket.policy.RoutingPolicy;
import com.mechalikh.docket.policy.SchedulingPolicy;
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
 * <p>
 * Where the deployment declares an access link, the chosen replica is reached through it: the
 * request is admitted and routed on the instant its prompt is released, then waits for the bearer
 * before it enters the queue of that replica. The policies therefore see the clock of the gateway,
 * which is the clock a real entry point has when it decides, and the time the prompt spends on the
 * wire is charged to the latency the client measures.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 0.1
 */
public class InferenceService extends SimEntity {

	/** Tag carrying a request that just arrived at the service. */
	public static final int REQUEST_ARRIVED = 10;
	/** Tag carrying the instant a client gives up on a request. */
	public static final int ABANDON_CHECK = 11;
	/** Tag carrying a prompt that finished crossing the access link of its replica. */
	public static final int UPLINK_DELIVERED = 12;
	/** Tag carrying a key-value cache that finished crossing the fabric to a decode replica. */
	public static final int KV_TRANSFER_DONE = 13;

	/** Model the replicas of this service serve. */
	protected final String modelId;
	/** Architecture of the served model, read for the size of a cache hand-off. */
	protected final ModelProfile model;
	/** Replicas, in creation order. */
	protected final List<ReplicaServer> replicas = new ArrayList<>();
	/** Whether the deployment splits prefill and decode onto different replicas. */
	protected final boolean disaggregated;
	/** Replicas that prefill prompts: the prefill instances of a split deployment, or all replicas otherwise. */
	protected final List<ReplicaServer> prefillStage = new ArrayList<>();
	/** Replicas that decode sequences handed to them by a prefill instance. */
	protected final List<ReplicaServer> decodeStage = new ArrayList<>();
	/**
	 * Bearer a finished prompt cache crosses to reach a decode replica, {@code null} when the
	 * hand-off is assumed instant. It is a single ledger shared by every hand-off, because one
	 * fabric serving the whole deployment is one bottleneck.
	 */
	protected final AccessLink fabric;
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
	 * Requests whose prompt is still on its way to the replica chosen for them, by request
	 * identifier, valued with the identifier of that replica. The map is the list of what the
	 * access links are carrying, so a run that stops at its horizon closes these rows too.
	 */
	protected final Map<Long, PendingDestination> uplinkPending = new LinkedHashMap<>();
	/**
	 * Requests whose cache is still crossing the fabric to a decode replica, by request identifier,
	 * valued with the identifier of that replica. The mirror of {@link #uplinkPending}: a run that
	 * stops at its horizon has to close these too, so they are tracked in their own ledger rather
	 * than folded into a replica, which holds no request while its cache is in flight.
	 */
	protected final Map<Long, PendingDestination> transferPending = new LinkedHashMap<>();

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
	 * @param fabric          the bearer a prompt cache crosses to reach a decode replica, or
	 *                        {@code null} when the hand-off is instant and the deployment does not
	 *                        disaggregate
	 */
	public InferenceService(PureEdgeSim simulation, String modelId, List<ReplicaSpec> specs,
			ExecutionCostModel costModel, AdmissionPolicy admissionPolicy, RoutingPolicy routingPolicy,
			SchedulingPolicy schedulingPolicy, RequestRecorder recorder, LinkProfile fabric) {
		super(simulation);
		if (modelId == null || modelId.isEmpty())
			throw new IllegalArgumentException("A service needs the identifier of the model it serves");
		if (specs == null || specs.isEmpty())
			throw new IllegalArgumentException("Service for model \"" + modelId + "\" declares no replica");
		if (schedulingPolicy == null)
			throw new IllegalArgumentException("Service for model \"" + modelId + "\" declares no scheduling policy");
		this.modelId = modelId;
		this.model = specs.get(0).getModel();
		this.admissionPolicy = admissionPolicy;
		this.routingPolicy = routingPolicy;
		this.schedulingPolicy = schedulingPolicy;
		this.recorder = recorder;
		this.fabric = fabric == null ? null : new AccessLink(fabric);
		// One queue per declared bearer, shared by every replica that names it, so two devices
		// behind one access point wait on each other. The ledgers are built here rather than
		// carried in the scenario because a repetition starts with empty links.
		Map<LinkProfile, AccessLink> links = new IdentityHashMap<>();
		boolean split = false;
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
			AccessLink link = null;
			if (spec.getLink() != null)
				link = links.computeIfAbsent(spec.getLink(), AccessLink::new);
			ReplicaServer replica = new ReplicaServer(simulation, this, spec, costModel, link);
			replicas.add(replica);
			replicasByDeviceId.put(spec.getDevice().getId(), replica);
			if (spec.getRole() == ReplicaSpec.Role.PREFILL)
				prefillStage.add(replica);
			else if (spec.getRole() == ReplicaSpec.Role.DECODE)
				decodeStage.add(replica);
			if (spec.getRole() != ReplicaSpec.Role.COLOCATED)
				split = true;
		}
		disaggregated = split;
		if (disaggregated) {
			// A deployment that split only one of the two phases has nowhere to send a finished
			// prompt, or nothing to prefill the sequences it decodes, so it cannot run at all.
			if (prefillStage.isEmpty() || decodeStage.isEmpty())
				throw new IllegalArgumentException("Service for model \"" + modelId
						+ "\" disaggregates prefill and decode but declares " + prefillStage.size()
						+ " prefill and " + decodeStage.size() + " decode replica(s); a split deployment needs at"
						+ " least one of each");
		} else {
			prefillStage.addAll(replicas);
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
		case UPLINK_DELIVERED:
			onUplinkDelivered(request);
			break;
		case KV_TRANSFER_DONE:
			onKvTransferDone(request);
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
		ReplicaServer target = routeIn(prefillStage, request);
		if (target == null) {
			finish(request, RequestState.REJECTED, OutcomeReason.ADMISSION_NO_CACHE_CAPACITY);
			return;
		}
		AccessLink link = target.getLink();
		if (link == null) {
			target.enqueue(request);
			scheduleAbandonChecks(request, target);
			return;
		}
		// The device receives the prompt no earlier than the bearer releases it, so the replica
		// cannot hold the request yet. A client that stops waiting is still checked from the
		// instant it released the request: a flight that outlasts the patience of the client ends
		// the request on the link instead of queueing work nobody asks for any more.
		if (request.getCancellationTime() > 0)
			scheduleAbandonAt(request.getCancellationTime(), request);
		double delivery = link.sendUplink(request, simulation.clock());
		uplinkPending.put(request.getRequestId(), new PendingDestination(request, target.getId()));
		schedule(this, delivery - simulation.clock(), UPLINK_DELIVERED, request);
	}

	/**
	 * Asks the routing policy which replica of a stage should take the request.
	 * <p>
	 * The policy sees only the replicas of the stage, in stage order, so an index it returns is
	 * read back against that same list. A split deployment therefore never routes a fresh prompt to
	 * a decode instance, which holds no prompt to prefill, and never hands a finished cache to a
	 * prefill instance, which decodes nothing.
	 *
	 * @param stage   the replicas eligible for this leg of the journey
	 * @param request the request being placed
	 * @return the chosen replica, or {@code null} when the policy refuses or the stage is empty
	 */
	protected ReplicaServer routeIn(List<ReplicaServer> stage, InferenceRequest request) {
		if (stage.isEmpty())
			return null;
		List<ReplicaLoad> view = new ArrayList<>(stage.size());
		for (ReplicaServer replica : stage)
			view.add(replica.snapshot());
		int chosenId = routingPolicy.choose(view, request);
		// The policy answers with a replica identifier, not a position, and the identifier counts
		// every replica of the model across the whole service. A stage is a slice of that set, so a
		// decode instance can be the first replica of its list yet carry the second identifier: the
		// choice is resolved by matching identifiers, never by reading the list at that number.
		if (chosenId < 0)
			return null;
		for (ReplicaServer replica : stage)
			if (replica.getId() == chosenId)
				return replica;
		return null;
	}

	/**
	 * Hands a prompt whose flight ended to the replica it was routed to.
	 * <p>
	 * A request the client abandoned while its prompt was on the wire is already terminal, so the
	 * delivery is dropped: the bytes were carried, but the replica never sees the request.
	 *
	 * @param request the request whose prompt reached its device
	 */
	protected void onUplinkDelivered(InferenceRequest request) {
		PendingDestination pending = uplinkPending.remove(request.getRequestId());
		if (pending == null || request.getState().isTerminal())
			return;
		ReplicaServer target = replicaById(pending.replicaId);
		target.enqueue(request);
		scheduleAbandonChecks(request, target);
	}

	/**
	 * Hands the finished cache of a prefilled prompt to a decode instance over the fabric.
	 * <p>
	 * Called by a prefill replica once a prompt is fully cached. The request leaves its prefill
	 * device with no owner: the decode device has not taken it yet and the prefill device has given
	 * its blocks back, so it is carried in {@link #transferPending} until the fabric releases it,
	 * exactly as an access link carries a prompt before its replica is chosen. With no bearer
	 * configured the hand-off is instant and the sequence enters the decode queue at once.
	 *
	 * @param request the request whose prompt finished prefilling, still in
	 *                {@link RequestState#PREFILLING}
	 * @param clock   the instant the prefill device released the cache, in seconds
	 */
	public void handoff(InferenceRequest request, double clock) {
		// The prefill device has already given its blocks back, so from here the sequence belongs to
		// no replica until a decode device adopts it, exactly as a prompt on an access link carries
		// no owner before it is delivered. finish() then frees nothing on behalf of the prefill
		// device, because that device holds nothing any more.
		request.setReplicaId(-1);
		ReplicaServer target = routeIn(decodeStage, request);
		if (target == null) {
			// No decode instance will take the sequence, which a policy can force by refusing every
			// load; the request had its prompt computed, so it ends as a capacity refusal.
			finish(request, RequestState.REJECTED, OutcomeReason.ADMISSION_NO_CACHE_CAPACITY);
			return;
		}
		request.transitionTo(RequestState.TRANSFERRING);
		if (fabric == null) {
			deliverHandoff(request, target);
			return;
		}
		double bytes = request.getCachedTokens() * model.kvBytesPerToken();
		double delivery = fabric.sendKvTransfer(bytes, clock);
		transferPending.put(request.getRequestId(), new PendingDestination(request, target.getId()));
		schedule(this, delivery - clock, KV_TRANSFER_DONE, request);
	}

	/**
	 * Gives a cache that finished crossing the fabric to the decode replica it was routed to.
	 * <p>
	 * A request the client abandoned mid-transfer is already terminal, so the arrival is dropped:
	 * the bytes were carried, but no device decodes a sequence nobody waits for.
	 *
	 * @param request the request whose cache reached its decode device
	 */
	protected void onKvTransferDone(InferenceRequest request) {
		PendingDestination pending = transferPending.remove(request.getRequestId());
		if (pending == null || request.getState().isTerminal())
			return;
		deliverHandoff(request, replicaById(pending.replicaId));
	}

	/**
	 * Enters a transferred sequence in the queue of its decode replica.
	 *
	 * @param request  the request whose cache arrived
	 * @param target   the decode replica that will generate its tokens
	 */
	protected void deliverHandoff(InferenceRequest request, ReplicaServer target) {
		// The request no longer belongs to any replica while it is in flight, so it carries none
		// until the decode device takes it; finish() then frees the right device's resources.
		request.setReplicaId(-1);
		target.enqueue(request);
		scheduleAbandonChecks(request, target);
	}

	/**
	 * A request a bearer is carrying, together with the replica it is bound for: a prompt still on
	 * its way up, or a finished cache still crossing the fabric to a decode instance.
	 */
	protected static class PendingDestination {
		/** Request being carried. */
		protected final InferenceRequest request;
		/** Replica waiting for it. */
		protected final int replicaId;

		/**
		 * Remembers one in-flight request.
		 *
		 * @param request   the request being carried
		 * @param replicaId the replica it is bound for
		 */
		protected PendingDestination(InferenceRequest request, int replicaId) {
			this.request = request;
			this.replicaId = replicaId;
		}
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
			scheduleAbandonAt(request.getCancellationTime(), request);
		if (replica.abortsOnDeadlineMiss() && request.getEndToEndDeadline() > 0)
			scheduleAbandonAt(request.getEndToEndDeadline(), request);
	}

	/**
	 * Arms the check that ends a request at an absolute instant, now when that instant already
	 * passed.
	 *
	 * @param instant the simulation time to wake up at, in seconds
	 * @param request the request to check
	 */
	protected void scheduleAbandonAt(double instant, InferenceRequest request) {
		schedule(this, Math.max(0, instant - simulation.clock()), ABANDON_CHECK, request);
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
	 * <p>
	 * The routing policy is then told the request finished. That callback is the only place a
	 * placement decision meets its consequence, so it is what a policy that learns from observed
	 * latency rather than from a predicted cost can exist at all.
	 * <p>
	 * The row is written at the instant the device ended its work, not at the instant the client
	 * reads the response: a request that is still on its way back is complete, and deferring the
	 * row would leave the engine without the counter that proves no request was lost. The trip
	 * home is written in the row as {@code downlink_s} and {@code delivery_time_s}.
	 *
	 * @param request  the request ending
	 * @param outcome  the terminal state
	 * @param reason   the reason code written in the row
	 */
	public void finish(InferenceRequest request, RequestState outcome, OutcomeReason reason) {
		if (request.getState().isTerminal())
			return;
		uplinkPending.remove(request.getRequestId());
		transferPending.remove(request.getRequestId());
		if (request.getReplicaId() >= 0)
			replicaById(request.getReplicaId()).releaseResourcesOf(request);
		double clock = simulation.clock();
		request.finish(outcome, reason.label(), clock);
		chargeDownlink(request, outcome, clock);
		recorder.record(request);
		finishedRequests++;
		routingPolicy.onFinished(request);
	}

	/**
	 * Puts the response of a completed request on the bearer that reaches its client.
	 * <p>
	 * Only a response that exists travels: a refused or abandoned request carries no tokens, and
	 * its delivery instant is the instant it ended. The figure is a ledger entry, not an event,
	 * because nothing in the engine waits for the client to read the answer.
	 *
	 * @param request the request that just reached a terminal state
	 * @param outcome the terminal state it reached
	 * @param clock   the instant it ended, in seconds
	 */
	protected void chargeDownlink(InferenceRequest request, RequestState outcome, double clock) {
		AccessLink link = request.getReplicaId() >= 0 ? replicaById(request.getReplicaId()).getLink() : null;
		request.setDeliveryTime(clock);
		if (link != null && outcome == RequestState.COMPLETED && request.getGeneratedTokens() > 0)
			request.setDeliveryTime(link.sendDownlink(request, clock));
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
		for (PendingDestination pending : uplinkPending.values())
			inside.add(pending.request);
		uplinkPending.clear();
		for (PendingDestination pending : transferPending.values())
			inside.add(pending.request);
		transferPending.clear();
		RequestState outcome = reason == OutcomeReason.CLIENT_CANCELLED ? RequestState.CANCELLED
				: RequestState.TIMED_OUT;
		int closed = 0;
		for (InferenceRequest request : inside) {
			if (request.getState().isTerminal())
				continue;
			finish(request, outcome, reason);
			closed++;
		}
		return closed;
	}

	/**
	 * Checks that every request that arrived has been recorded, that no replica holds resources of
	 * a recorded request, and that no prompt is still on a bearer.
	 * <p>
	 * A request a link is carrying counts as arrived but is held by no replica, so the third check
	 * is what keeps an in-flight prompt from disappearing between the two counters that agree.
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
		if (!uplinkPending.isEmpty())
			throw new IllegalStateException("Service \"" + modelId + "\" ends with " + uplinkPending.size()
					+ " prompts still on an access link; a request that was released must be recorded even when it"
					+ " never reached its replica");
		if (!transferPending.isEmpty())
			throw new IllegalStateException("Service \"" + modelId + "\" ends with " + transferPending.size()
					+ " caches still on the fabric; a prompt handed to a decode instance must be recorded even when"
					+ " the transfer never completed");
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

	/**
	 * Returns the identifier of the model every replica of this service serves.
	 * <p>
	 * A request is routed inside the service only when its model identifier matches this one,
	 * so the value decides which requests the deployment can admit.
	 *
	 * @return the model identifier
	 */
	public String getModelId() {
		return modelId;
	}

	/**
	 * Returns the replicas of the service in creation order.
	 * <p>
	 * The order fixes the replica identifiers the routing policies see in {@link #loads()}, and
	 * it is the order in which a shutdown visits the replicas.
	 *
	 * @return an unmodifiable view, one entry per declared replica
	 */
	public List<ReplicaServer> getReplicas() {
		return Collections.unmodifiableList(replicas);
	}

	/**
	 * Returns the policy that decides whether an arrived request enters the service.
	 * <p>
	 * The policy is consulted once per arrival, after the model and the context length have
	 * been checked, and its refusal is recorded with the reason it gives.
	 *
	 * @return the admission policy
	 */
	public AdmissionPolicy getAdmissionPolicy() {
		return admissionPolicy;
	}

	/**
	 * Returns the policy that chooses the replica of an admitted request.
	 * <p>
	 * The policy picks an index into the list returned by {@link #loads()}, and a negative
	 * choice rejects the request with {@link OutcomeReason#ADMISSION_NO_CACHE_CAPACITY}.
	 *
	 * @return the routing policy
	 */
	public RoutingPolicy getRoutingPolicy() {
		return routingPolicy;
	}

	/**
	 * Returns the policy that orders the waiting queue of every replica.
	 * <p>
	 * The same policy instance serves all the replicas, and each replica keeps its own queue
	 * that the policy rearranges before each scheduling decision.
	 *
	 * @return the scheduling policy
	 */
	public SchedulingPolicy getSchedulingPolicy() {
		return schedulingPolicy;
	}

	/**
	 * Returns the sink that receives the row of every request when its life ends.
	 *
	 * @return the request recorder
	 */
	public RequestRecorder getRecorder() {
		return recorder;
	}

	/**
	 * Returns how many requests have reached the service since the start of the run.
	 * <p>
	 * Arrivals are counted before the policies run, so a refused request is included. At the end
	 * of a run the count must equal {@link #getFinishedRequests()}, otherwise a request was lost.
	 *
	 * @return arrivals counted on the simulation clock
	 */
	public long getArrivedRequests() {
		return arrivedRequests;
	}

	/**
	 * Returns how many requests have been recorded since the start of the run.
	 * <p>
	 * Every terminal outcome passes through
	 * {@link #finish(InferenceRequest, RequestState, OutcomeReason)}, so the count also covers
	 * the requests ended by the shutdown, and it is the denominator of the completion ratio of
	 * the service.
	 *
	 * @return recorded requests counted on the simulation clock
	 */
	public long getFinishedRequests() {
		return finishedRequests;
	}

	/**
	 * Returns a one-line summary with the model, the number of replicas and the arrivals.
	 *
	 * @return the summary line
	 */
	@Override
	public String toString() {
		return "InferenceService[" + modelId + ", " + replicas.size() + " replicas, arrived=" + arrivedRequests + "]";
	}
}
