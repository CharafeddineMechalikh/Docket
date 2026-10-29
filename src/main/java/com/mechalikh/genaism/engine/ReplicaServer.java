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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.mechalikh.genaism.cost.CostEstimate;
import com.mechalikh.genaism.cost.DecodeBatch;
import com.mechalikh.genaism.cost.ExecutionCostModel;
import com.mechalikh.genaism.domain.GpuProfile;
import com.mechalikh.genaism.domain.InferenceRequest;
import com.mechalikh.genaism.domain.ModelProfile;
import com.mechalikh.genaism.domain.OutcomeReason;
import com.mechalikh.genaism.domain.RequestState;
import com.mechalikh.genaism.memory.GpuMemoryManager;
import com.mechalikh.genaism.memory.KvCachePool;
import com.mechalikh.genaism.metrics.ResourceSample;
import com.mechalikh.genaism.policy.ReplicaLoad;
import com.mechalikh.genaism.policy.SchedulingPolicy;
import com.mechalikh.genaism.power.GpuPowerModel;
import com.mechalikh.pureedgesim.simulationengine.Event;
import com.mechalikh.pureedgesim.simulationengine.PureEdgeSim;
import com.mechalikh.pureedgesim.simulationengine.SimEntity;

/**
 * One model running on one device, served with continuous batching.
 * <p>
 * The device is a single server: one step executes at a time, and a step is either a prefill
 * chunk or one decode iteration over the batch. Durations come from an
 * {@link ExecutionCostModel}, reservations from a {@link KvCachePool}, and energy from a
 * {@link GpuPowerModel}, so the server holds no execution formula of its own.
 * <p>
 * What a decode iteration contains is decided by the cache, not by the wish of the scheduler: a
 * sequence that cannot reserve the block its next token needs is left out of the iteration, and
 * when the whole batch is blocked the replica suspends a sequence under the configured preemption
 * order. A suspended sequence loses its cache and is re-prefilled over its prompt and every token
 * it already produced, so the recomputation cost is charged again when it resumes. A prefill that
 * is interrupted keeps its chunks: they are already in the cache.
 * <p>
 * Prefill and decode never share a step. Chunked prefill is a sequence of prefill steps, each of
 * which re-reads the weights, which is why a small chunk costs more than a large one for the same
 * prompt.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public class ReplicaServer extends SimEntity {

	/** Tag carrying the end of a prefill chunk. */
	public static final int PREFILL_DONE = 21;
	/** Tag carrying the end of one decode iteration. */
	public static final int ITERATION_DONE = 22;

	/** Service this replica belongs to. */
	protected final InferenceService service;
	/** Deployment limits. */
	protected final ReplicaSpec spec;
	/** Model served here. */
	protected final ModelProfile model;
	/** Device executing here. */
	protected final GpuProfile device;
	/** Prices the steps. */
	protected final ExecutionCostModel cost;
	/** Memory budget of the replica. */
	protected final GpuMemoryManager memory;
	/** Cache reservations. */
	protected final KvCachePool pool;
	/** Energy integrator. */
	protected final GpuPowerModel power;
	/** Waiting requests, sorted when {@link #waitingDirty} is set. */
	protected final List<InferenceRequest> waiting = new ArrayList<>();
	/** Sequences generating tokens, in the order they started decoding. */
	protected final LinkedHashMap<Long, InferenceRequest> running = new LinkedHashMap<>();
	/** Predicted work of the waiting requests, by request identifier. */
	protected final Map<Long, Double> pendingWorkByRequest = new LinkedHashMap<>();
	/** Whether the waiting list must be sorted before its head is read. */
	protected boolean waitingDirty;
	/** Request whose chunk is executing, {@code null} when no prefill is in flight. */
	protected InferenceRequest prefilling;
	/** Sequences taking part in the iteration in flight, {@code null} when none. */
	protected List<InferenceRequest> iterationBatch;
	/** Instant the last charged interval ended. */
	protected double lastAccountedTime;
	/** Number of prefill steps executed. */
	protected long prefillSteps;
	/** Number of decode iterations executed. */
	protected long decodeIterations;
	/** Sum of the batch sizes over the iterations, for the mean batch. */
	protected long batchSizeSum;
	/** Largest batch observed. */
	protected int peakBatchSize;
	/** Number of times a sequence was suspended to free cache. */
	protected long preemptions;
	/** Number of refused cache reservations. */
	protected long cacheRefusals;
	/** Number of times the replica could not start a step while holding requests. */
	protected long stalledSteps;

	/**
	 * Creates a replica.
	 *
	 * @param simulation the event engine
	 * @param service    the service owning the replica
	 * @param spec       the deployment limits
	 * @param cost       the model that prices the steps
	 */
	public ReplicaServer(PureEdgeSim simulation, InferenceService service, ReplicaSpec spec, ExecutionCostModel cost) {
		super(simulation);
		this.service = service;
		this.spec = spec;
		this.model = spec.getModel();
		this.device = spec.getDevice();
		this.cost = cost;
		this.memory = new GpuMemoryManager(device, model, spec.getRuntimeOverheadBytes(),
				spec.getReservedActivationSlots());
		this.pool = new KvCachePool(memory.kvPoolBytes(), model.kvBytesPerToken(), spec.getTokensPerBlock());
		this.power = new GpuPowerModel(device);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void processEvent(Event e) {
		switch (e.getTag()) {
		case PREFILL_DONE:
			onPrefillChunkDone((InferenceRequest) e.getData());
			break;
		case ITERATION_DONE:
			onIterationDone();
			break;
		default:
			throw new IllegalArgumentException("Replica " + getId() + " received unknown tag " + e.getTag());
		}
	}

	/**
	 * Queues a request that the service routed here.
	 *
	 * @param request the admitted request, in state {@link RequestState#ADMITTED}
	 */
	public void enqueue(InferenceRequest request) {
		request.setReplicaId(getId());
		request.transitionTo(RequestState.QUEUED);
		request.setQueueEntryTime(simulation.clock());
		waiting.add(request);
		waitingDirty = true;
		pendingWorkByRequest.put(request.getRequestId(), predictedWaitingWork(request));
		pump();
	}

	/**
	 * Removes a request from the replica without ending its life, used when the client abandons it
	 * or a deadline aborts it.
	 *
	 * @param request the request to remove
	 * @return {@code true} when the replica held the request
	 */
	public boolean drop(InferenceRequest request) {
		if (!holds(request))
			return false;
		waiting.remove(request);
		running.remove(request.getRequestId());
		if (prefilling == request)
			prefilling = null;
		releaseOf(request);
		return true;
	}

	/**
	 * Frees the cache and the counters of a request that left the replica, without touching its
	 * lifecycle state.
	 *
	 * @param request the finished request
	 */
	public void releaseResourcesOf(InferenceRequest request) {
		waiting.remove(request);
		running.remove(request.getRequestId());
		if (prefilling == request)
			prefilling = null;
		releaseOf(request);
	}

	/**
	 * Drops the reservation and the pending work entry of a request.
	 *
	 * @param request the request leaving the replica
	 */
	protected void releaseOf(InferenceRequest request) {
		pendingWorkByRequest.remove(request.getRequestId());
		pool.release(request.getRequestId());
		request.setAllocatedBlocks(0);
	}

	/**
	 * Returns whether the replica still holds the request in any of its three sets.
	 *
	 * @param request the request to look for
	 * @return {@code true} when the replica is responsible for it
	 */
	protected boolean holds(InferenceRequest request) {
		return waiting.contains(request) || running.containsKey(request.getRequestId()) || prefilling == request;
	}

	/**
	 * Returns every request the replica currently holds.
	 *
	 * @return waiting, prefilling and running requests together
	 */
	public List<InferenceRequest> requestsInside() {
		List<InferenceRequest> inside = new ArrayList<>(waiting.size() + running.size() + 1);
		inside.addAll(waiting);
		if (prefilling != null)
			inside.add(prefilling);
		inside.addAll(running.values());
		return inside;
	}

	/**
	 * Decides the next step of the device and schedules it, or charges an idle interval.
	 * <p>
	 * The loop keeps trying because a refusal is local: a prefill that the cache stopped must not
	 * block a decode iteration that has room, and a step that the batch limit stopped must not block
	 * the next iteration. Every branch either schedules a step, removes a request, or ends the loop,
	 * so it cannot spin.
	 */
	protected void pump() {
		if (isBusy())
			return;
		double clock = simulation.clock();
		// Each retry of the loop below drops one refused head from the queue, so the size of the
		// queue bounds the number of retries a healthy configuration can need.
		int retries = waiting.size();
		while (true) {
			if (waiting.isEmpty() && running.isEmpty()) {
				chargeIdleUntil(clock);
				return;
			}
			if (startNextPrefillChunk())
				return;
			if (startDecodeIteration())
				return;
			if (running.isEmpty()) {
				if (retries-- <= 0) {
					stalledSteps++;
					chargeIdleUntil(clock);
					return;
				}
				continue;
			}
			if (spec.isAllowPreemption() && preemptOneVictim())
				continue;
			stalledSteps++;
			chargeIdleUntil(clock);
			return;
		}
	}

	/**
	 * Returns whether a step of this replica is still in flight.
	 *
	 * @return {@code true} while prefilling or iterating
	 */
	public boolean isBusy() {
		return prefilling != null || iterationBatch != null;
	}

	/**
	 * Charges the interval since the last accounted instant as idle time.
	 *
	 * @param clock current simulation time
	 */
	protected void chargeIdleUntil(double clock) {
		power.recordIdle(Math.max(0, clock - lastAccountedTime));
		lastAccountedTime = Math.max(lastAccountedTime, clock);
	}

	/**
	 * Takes the next chunk of the request at the head of the queue, or refuses it when its whole
	 * sequence can never fit in the cache of the device.
	 *
	 * @return {@code true} when a step was scheduled
	 */
	protected boolean startNextPrefillChunk() {
		if (waiting.isEmpty() || running.size() + (prefilling == null ? 0 : 1) >= spec.getMaxBatchSize())
			return false;
		sortWaitingIfNeeded();
		InferenceRequest head = waiting.get(0);
		int target = prefillTarget(head);
		if (pool.blocksFor(target) > pool.getTotalBlocks()) {
			waiting.remove(0);
			head.setCachedTokens(0);
			releaseOf(head);
			service.finish(head, RequestState.REJECTED, OutcomeReason.PROMPT_DOES_NOT_FIT);
			return false;
		}
		waiting.remove(0);
		head.addQueueingTime(simulation.clock() - head.getLastQueueEntryTime());
		head.transitionTo(RequestState.PREFILLING);
		if (head.getPrefillStartTime() == 0)
			head.setPrefillStartTime(simulation.clock());
		prefilling = head;
		return schedulePrefillChunk(head, target);
	}

	/**
	 * Reserves the cache of the next chunk, prices it and schedules its end.
	 *
	 * @param request the request being prefilled, in state {@link RequestState#PREFILLING}
	 * @param target  cached tokens the prefill aims at
	 * @return {@code true} when a step was scheduled
	 */
	protected boolean schedulePrefillChunk(InferenceRequest request, int target) {
		int already = request.getCachedTokens();
		if (already >= target) {
			joinBatch(request);
			return false;
		}
		int chunk = Math.min(spec.prefillChunkTokens(target - already), target - already);
		if (!pool.reserve(request.getRequestId(), already + chunk)) {
			cacheRefusals++;
			suspendPrefill(request);
			return false;
		}
		request.setAllocatedBlocks(pool.allocatedBlocks(request.getRequestId()));
		CostEstimate estimate = cost.prefillStep(model, device, chunk, already);
		request.addPrefillSeconds(estimate.seconds());
		chargeEnergyOfStep(estimate.seconds(), request);
		prefillSteps++;
		schedule(this, estimate.seconds(), PREFILL_DONE, request);
		return true;
	}

	/**
	 * Returns a partially prefilled request to the queue, keeping the chunks already cached.
	 *
	 * @param request the request that cannot continue its prefill now
	 */
	protected void suspendPrefill(InferenceRequest request) {
		prefilling = null;
		request.transitionTo(RequestState.PREEMPTED);
		request.transitionTo(RequestState.QUEUED);
		request.setQueueEntryTime(simulation.clock());
		waiting.add(request);
		waitingDirty = true;
		pendingWorkByRequest.put(request.getRequestId(), predictedWaitingWork(request));
	}

	/**
	 * Completes a prefill chunk and decides what the device does next.
	 *
	 * @param request the request whose chunk ended
	 */
	protected void onPrefillChunkDone(InferenceRequest request) {
		chargeIdleUntil(simulation.clock());
		if (request.getState() != RequestState.PREFILLING) {
			prefilling = null;
			pump();
			return;
		}
		int target = prefillTarget(request);
		request.setCachedTokens(Math.min(target, request.getCachedTokens() + lastChunkTokens(request, target)));
		prefilling = null;
		if (request.getCachedTokens() < target) {
			prefilling = request;
			if (schedulePrefillChunk(request, target))
				return;
			pump();
			return;
		}
		joinBatch(request);
		pump();
	}

	/**
	 * Places a fully prefilled request in the decode batch and emits the token its prefill produced.
	 *
	 * @param request the request that finished its prefill
	 */
	protected void joinBatch(InferenceRequest request) {
		prefilling = null;
		if (request.getState() == RequestState.PREFILLING)
			request.transitionTo(RequestState.DECODING);
		request.setAllocatedBlocks(pool.allocatedBlocks(request.getRequestId()));
		running.put(request.getRequestId(), request);
		if (request.getGeneratedTokens() == 0)
			request.emitToken(simulation.clock(), 0);
		if (isFinished(request))
			complete(request);
	}

	/**
	 * Starts one decode iteration over the running sequences that can reserve their next token.
	 *
	 * @return {@code true} when an iteration was scheduled
	 */
	protected boolean startDecodeIteration() {
		if (running.isEmpty() || prefilling != null)
			return false;
		List<InferenceRequest> batch = new ArrayList<>();
		for (InferenceRequest request : running.values()) {
			if (batch.size() >= spec.getMaxBatchSize())
				break;
			if (pool.reserve(request.getRequestId(), request.getCachedTokens() + 1))
				batch.add(request);
			else
				cacheRefusals++;
		}
		if (batch.isEmpty())
			return false;
		iterationBatch = batch;
		long contexts = 0;
		int max = 0;
		for (InferenceRequest request : batch) {
			contexts += request.getCachedTokens();
			max = Math.max(max, request.getCachedTokens());
		}
		CostEstimate estimate = cost.decodeIteration(model, device, new DecodeBatch(batch.size(), contexts, max));
		double duration = estimate.seconds();
		for (InferenceRequest request : batch) {
			request.addDecodeSeconds(duration / batch.size());
			request.setAllocatedBlocks(pool.allocatedBlocks(request.getRequestId()));
		}
		chargeEnergyOfStep(duration, batch);
		decodeIterations++;
		batchSizeSum += batch.size();
		peakBatchSize = Math.max(peakBatchSize, batch.size());
		schedule(this, duration, ITERATION_DONE);
		return true;
	}

	/**
	 * Applies the result of a decode iteration: one token per sequence in the batch, then the
	 * completions.
	 */
	protected void onIterationDone() {
		double clock = simulation.clock();
		chargeIdleUntil(clock);
		List<InferenceRequest> batch = iterationBatch;
		iterationBatch = null;
		if (batch == null) {
			pump();
			return;
		}
		List<InferenceRequest> finished = new ArrayList<>();
		for (InferenceRequest request : batch) {
			if (request.getState() != RequestState.DECODING)
				continue;
			request.setCachedTokens(request.getCachedTokens() + 1);
			request.emitToken(clock, clock - previousTokenTime(request));
			if (isFinished(request))
				finished.add(request);
			else if (reachesContextLimit(request))
				stopAtContextLimit(request);
		}
		for (InferenceRequest request : finished)
			complete(request);
		pump();
	}

	/**
	 * Returns whether the request generated every token it may produce.
	 *
	 * @param request a running request
	 * @return {@code true} when it is done
	 */
	protected boolean isFinished(InferenceRequest request) {
		return request.getGeneratedTokens() >= Math.min(request.getOutputTokens(), request.getMaxOutputTokens());
	}

	/**
	 * Returns whether one more token would pass the context window of the model.
	 *
	 * @param request a running request
	 * @return {@code true} when the cache of the sequence fills the window or the pool
	 */
	protected boolean reachesContextLimit(InferenceRequest request) {
		return request.getCachedTokens() >= spec.maxContextTokens(pool.capacityTokens());
	}

	/**
	 * Ends a request that filled the context window before producing everything it asked for.
	 * <p>
	 * The row keeps the length the request asked for next to the length it produced, so a reader
		* can see the truncation instead of a request that appears to have finished its work.
	 *
	 * @param request the truncated request
	 */
	protected void stopAtContextLimit(InferenceRequest request) {
		service.finish(request, RequestState.COMPLETED, OutcomeReason.CONTEXT_LIMIT_REACHED);
		releaseResourcesOf(request);
	}

	/**
	 * Ends a request that generated everything it asked for.
	 *
	 * @param request the completed request
	 */
	protected void complete(InferenceRequest request) {
		service.finish(request, RequestState.COMPLETED, OutcomeReason.SUCCESS);
		releaseResourcesOf(request);
	}

	/**
	 * Suspends one running sequence to free cache.
	 *
	 * @return {@code true} when a sequence was suspended
	 */
	protected boolean preemptOneVictim() {
		InferenceRequest victim = selectVictim();
		if (victim == null)
			return false;
		running.remove(victim.getRequestId());
		pool.release(victim.getRequestId());
		victim.setCachedTokens(0);
		victim.setAllocatedBlocks(0);
		victim.incrementPreemptions();
		victim.transitionTo(RequestState.PREEMPTED);
		victim.transitionTo(RequestState.QUEUED);
		victim.setQueueEntryTime(simulation.clock());
		preemptions++;
		waiting.add(victim);
		waitingDirty = true;
		pendingWorkByRequest.put(victim.getRequestId(), predictedWaitingWork(victim));
		return true;
	}

	/**
	 * Chooses the sequence to suspend, following the configured order.
	 *
	 * @return the victim, or {@code null} when no sequence is running
	 */
	protected InferenceRequest selectVictim() {
		if (running.isEmpty())
			return null;
		List<InferenceRequest> candidates = new ArrayList<>(running.values());
		switch (spec.getVictimOrder()) {
		case LOWEST_PRIORITY:
			candidates.sort(Comparator.comparingInt(InferenceRequest::getPriority)
					.thenComparingLong(InferenceRequest::getRequestId));
			return candidates.get(candidates.size() - 1);
		case LONGEST_CONTEXT:
			candidates.sort(Comparator.comparingInt(InferenceRequest::getCachedTokens)
					.thenComparingLong(InferenceRequest::getRequestId));
			return candidates.get(candidates.size() - 1);
		case NEWEST_FIRST:
		default:
			return candidates.get(candidates.size() - 1);
		}
	}

	/**
	 * Returns the number of cached tokens a prefill must reach for a request.
	 * <p>
	 * A resumed request recomputes its prompt and every token it already produced, so the target
	 * grows with the progress it made before being suspended.
	 *
	 * @param request the request being prefilled
	 * @return tokens that must be in the cache before the next token is emitted
	 */
	protected static int prefillTarget(InferenceRequest request) {
		return request.getInputTokens() + request.getGeneratedTokens();
	}

	/**
	 * Returns the size of the chunk that would be read next.
	 *
	 * @param request the request being prefilled
	 * @param target  cached tokens the prefill aims at
	 * @return tokens read by the chunk
	 */
	protected int lastChunkTokens(InferenceRequest request, int target) {
		int remaining = target - request.getCachedTokens();
		return Math.max(1, Math.min(spec.prefillChunkTokens(remaining), remaining));
	}

	/**
	 * Returns the instant the request emitted its previous token.
	 *
	 * @param request a running request
	 * @return seconds, or the queue entry time when the request has not emitted a token yet
	 */
	protected double previousTokenTime(InferenceRequest request) {
		return request.getLastTokenTime() > 0 ? request.getLastTokenTime() : request.getQueueEntryTime();
	}

	/**
	 * Charges the energy of a step to the sequences that took part in it, splitting it equally.
	 * <p>
	 * A step is charged at the instant it is scheduled, over its whole predicted duration, so no
	 * interval overlaps another: the device executes one step at a time.
	 *
	 * @param seconds      duration of the step
	 * @param participants the requests taking part in the step
	 */
	protected void chargeEnergyOfStep(double seconds, List<InferenceRequest> participants) {
		openStep(seconds);
		double joule = power.recordActive(seconds);
		for (InferenceRequest request : participants)
			request.addEnergyJoule(GpuPowerModel.shareOf(joule, 1, participants.size()));
	}

	/**
	 * Closes the timeline before a step is charged, then marks the step's span as paid for.
	 * <p>
	 * A step is charged over its whole predicted duration at the instant it is scheduled, so the
	 * interval between the last accounted instant and the start of the step is device idle time and
	 * must be charged as such. Without this, a replica that waits for the next arrival accumulates
	 * an uncharged gap, and the energy of a run comes out below the idle power times the duration,
	 * which is the floor no device holding the experiment can go under.
	 *
	 * @param seconds the duration of the step about to be charged
	 */
	protected void openStep(double seconds) {
		chargeIdleUntil(simulation.clock());
		// The step's own span is charged now, so the horizon moves past its end. A step that is
		// preempted before that end leaves the horizon ahead of the clock, which is correct: the
		// time was already paid for and must not be charged a second time as idle.
		lastAccountedTime = Math.max(lastAccountedTime, simulation.clock() + seconds);
	}

	/**
	 * Charges the energy of a step to one request.
	 *
	 * @param seconds duration of the step
	 * @param request the request executing
	 */
	protected void chargeEnergyOfStep(double seconds, InferenceRequest request) {
		openStep(seconds);
		request.addEnergyJoule(power.recordActive(seconds));
	}

	/**
	 * Estimates the work a waiting request will ask of the device, which is what the routing
	 * policies compare between replicas.
	 *
	 * @param request the request being queued
	 * @return predicted seconds
	 */
	protected double predictedWaitingWork(InferenceRequest request) {
		int prompt = request.getInputTokens();
		double prefill = cost.prefillStep(model, device, prompt, 0).seconds();
		double perToken = cost.decodeIteration(model, device, new DecodeBatch(1, prompt + 1, prompt + 1)).seconds();
		int tokens = Math.max(0, Math.min(request.getPredictedOutputTokens(), request.getMaxOutputTokens()) - 1);
		return prefill + perToken * tokens;
	}

	/**
	 * Sorts the waiting list with the policy order, when it changed since the last read.
	 */
	protected void sortWaitingIfNeeded() {
		if (!waitingDirty)
			return;
		SchedulingPolicy policy = service.getSchedulingPolicy();
		waiting.sort(policy.waitingOrder());
		waitingDirty = false;
	}

	/**
	 * Charges the time between the last accounted instant and the end of the experiment.
	 * <p>
	 * A step charges its whole predicted duration when it is scheduled, so an interval that ends
	 * the run while a step is in flight is already paid for, and only a truly idle tail is missing.
	 *
	 * @param clock the instant to account up to
	 */
	public void accountUntil(double clock) {
		chargeIdleUntil(clock);
	}

	/**
	 * Returns the load the policies of the service see.
	 *
	 * @return the snapshot of this replica
	 */
	public ReplicaLoad snapshot() {
		double pending = 0;
		for (double work : pendingWorkByRequest.values())
			pending += work;
		return new ReplicaLoad(getId(), waiting.size(), running.size(), pool.freeTokens(), pool.capacityTokens(),
				pending);
	}

	/**
	 * Samples the occupancy of the replica, for the resource time series.
	 *
	 * @return the snapshot
	 */
	public ResourceSample sample() {
		return new ResourceSample(simulation.clock(), getId(), running.size(), waiting.size(), pool.usedBlocks(),
				pool.getTotalBlocks(), pool.usedTokens(), power.totalJoule(), isBusy());
	}

	/**
	 * Checks that no block is held by a request that left the replica, and that none is over
	 * committed.
	 *
	 * @throws IllegalStateException when the bookkeeping is inconsistent
	 */
	public void checkCacheConsistency() {
		pool.checkConsistency();
		Set<Long> held = pool.reservedRequests();
		List<InferenceRequest> inside = requestsInside();
		for (long requestId : held) {
			boolean found = false;
			for (InferenceRequest request : inside)
				if (request.getRequestId() == requestId)
					found = true;
			if (!found)
				throw new IllegalStateException("Replica " + getId() + " holds " + pool.allocatedBlocks(requestId)
						+ " blocks for request " + requestId + " which the replica no longer has");
		}
		if (pool.usedBlocks() > pool.getTotalBlocks())
			throw new IllegalStateException("Replica " + getId() + " handed out " + pool.usedBlocks() + " blocks out of "
					+ pool.getTotalBlocks());
	}

	public KvCachePool getPool() {
		return pool;
	}

	public GpuMemoryManager getMemory() {
		return memory;
	}

	public GpuPowerModel getPower() {
		return power;
	}

	public ModelProfile getModel() {
		return model;
	}

	public GpuProfile getDevice() {
		return device;
	}

	public ReplicaSpec getSpec() {
		return spec;
	}

	/**
	 * Returns the tokens the cache of this replica can hold in total.
	 *
	 * @return tokens
	 */
	public int cacheCapacityTokens() {
		return pool.capacityTokens();
	}

	/**
	 * Returns whether a request past its end-to-end deadline is aborted here.
	 *
	 * @return the configured behaviour
	 */
	public boolean abortsOnDeadlineMiss() {
		return spec.isAbortOnDeadlineMiss();
	}

	/**
	 * Returns the mean batch size over the executed iterations.
	 *
	 * @return sequences per iteration, zero when no iteration ran
	 */
	public double meanBatchSize() {
		return decodeIterations == 0 ? 0 : (double) batchSizeSum / decodeIterations;
	}

	public long getPrefillSteps() {
		return prefillSteps;
	}

	public long getDecodeIterations() {
		return decodeIterations;
	}

	public int getPeakBatchSize() {
		return peakBatchSize;
	}

	public long getPreemptions() {
		return preemptions;
	}

	public long getCacheRefusals() {
		return cacheRefusals;
	}

	public long getStalledSteps() {
		return stalledSteps;
	}

	@Override
	public String toString() {
		return String.format(Locale.ROOT,
				"ReplicaServer[id=%d, model=%s, device=%s, waiting=%d, running=%d, blocks=%d/%d]", getId(),
				model.getId(), device.getId(), waiting.size(), running.size(), pool.usedBlocks(),
				pool.getTotalBlocks());
	}
}
