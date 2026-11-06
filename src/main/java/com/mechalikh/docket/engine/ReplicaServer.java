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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.mechalikh.docket.cost.CostEstimate;
import com.mechalikh.docket.cost.DecodeBatch;
import com.mechalikh.docket.cost.ExecutionCostModel;
import com.mechalikh.docket.domain.GpuProfile;
import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.domain.ModelProfile;
import com.mechalikh.docket.domain.OutcomeReason;
import com.mechalikh.docket.domain.RequestState;
import com.mechalikh.docket.memory.GpuMemoryManager;
import com.mechalikh.docket.memory.KvCachePool;
import com.mechalikh.docket.metrics.ResourceSample;
import com.mechalikh.docket.network.AccessLink;
import com.mechalikh.docket.policy.ReplicaLoad;
import com.mechalikh.docket.policy.SchedulingPolicy;
import com.mechalikh.docket.power.GpuPowerModel;
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
 * @since Docket 0.1
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
	/**
	 * Bearer the traffic of this replica queues on, {@code null} when the deployment declares no
	 * access link. Several replicas served by devices behind one access point hold the same object,
	 * which is what makes their prompts wait for each other.
	 */
	protected final AccessLink link;
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
	/** Duration charged to the decode iteration in flight, so its accepted tokens split it evenly. */
	protected double iterationSeconds;
	/** Number of tokens each sequence in the iteration in flight emits, one when speculation is off. */
	protected int iterationAccepted = 1;
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
	/** Tokens emitted by decode iterations, one per iteration when speculative decoding is off. */
	protected long decodeTokenEmissions;
	/** Requests whose prompt was served at least partly from the prefix cache. */
	protected long prefixHits;
	/** Prompt tokens served from the prefix cache instead of recomputed. */
	protected long prefixReusedTokens;
	/**
	 * Prompts already cached for reuse, keyed by {@link InferenceRequest#prefixKey()}, holding the
	 * prompt length each key reached. Bounded so a long run cannot grow it without limit; the oldest
	 * entry is dropped when the cap is reached.
	 */
	protected final java.util.LinkedHashMap<String, Integer> prefixByPrompt = new java.util.LinkedHashMap<>();
	/** Entries the prefix map holds before the oldest is dropped. */
	protected static final int PREFIX_CACHE_CAPACITY = 10000;

	/**
	 * Creates a replica.
	 *
	 * @param simulation the event engine
	 * @param service    the service owning the replica
	 * @param spec       the deployment limits
	 * @param cost       the model that prices the steps
	 * @param link       the bearer the traffic of this replica queues on, {@code null} when the
	 *                   deployment models no access link
	 */
	public ReplicaServer(PureEdgeSim simulation, InferenceService service, ReplicaSpec spec, ExecutionCostModel cost,
			AccessLink link) {
		super(simulation);
		this.service = service;
		this.spec = spec;
		this.model = spec.getModel();
		this.device = spec.getDevice();
		this.link = link;
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
	 * the next iteration. Every branch either schedules a step, removes a request, releases a cache
	 * or ends the loop, and the release happens at most once, so it cannot spin.
	 */
	protected void pump() {
		if (isBusy())
			return;
		double clock = simulation.clock();
		if (spec.getRole() == ReplicaSpec.Role.DECODE) {
			pumpDecode(clock);
			return;
		}
		// Each retry of the loop below drops one refused head from the queue, so the size of the
		// queue bounds the number of retries a healthy configuration can need.
		int retries = waiting.size();
		boolean releasedWaiting = false;
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
				if (retries-- > 0)
					continue;
			} else if (spec.isAllowPreemption() && preemptOneVictim()) {
				continue;
			}
			if (!releasedWaiting && releaseWaitingCaches()) {
				releasedWaiting = true;
				retries = waiting.size();
				// The batch gets the first refusal on the blocks just released, otherwise the head
				// of the queue takes them back before a single token is emitted.
				if (startDecodeIteration())
					return;
				continue;
			}
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
		applyPrefixReuse(head);
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
	 * Gives back the cached chunks of the queued requests, which is the way out of a full queue.
	 * <p>
	 * A suspended prefill keeps its blocks so that a resumed prompt is not recomputed. If several
	 * suspended requests together fill the pool while nothing runs, every one of them is refused
	 * for lack of the very blocks it holds, and the replica would idle with its cache full. No
	 * sequence can be preempted at that point, because preemption draws its victims from the
	 * running set. Releasing the queues' caches is the last resort that lets the head start again,
	 * at the cost of recomputing prompts that had made partial progress.
	 *
	 * @return {@code true} when at least one request gave its cache back
	 */
	protected boolean releaseWaitingCaches() {
		boolean released = false;
		for (InferenceRequest request : waiting) {
			if (request.getCachedTokens() <= 0)
				continue;
			pool.release(request.getRequestId());
			request.setCachedTokens(0);
			request.setAllocatedBlocks(0);
			released = true;
		}
		return released;
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
		// A prefill instance stops at the cache: it emits no token and decodes nothing, it hands
		// the finished prompt state to a decode instance over the fabric.
		if (spec.getRole() == ReplicaSpec.Role.PREFILL) {
			handoffToDecode(request);
			pump();
			return;
		}
		joinBatch(request);
		pump();
	}

	/**
	 * Gives the cache of a fully prefilled prompt to the service for its trip to a decode instance.
	 * <p>
	 * The replica releases its own bookkeeping first: the sequence is no longer this device's
	 * responsibility, and the blocks it held are given back so the next prompt can be prefilled.
	 * The cached-token count stays on the request, because that count is the size of the transfer.
	 *
	 * @param request the request whose prompt finished prefilling
	 */
	protected void handoffToDecode(InferenceRequest request) {
		releaseOf(request);
		service.handoff(request, simulation.clock());
	}

	/**
	 * Runs one scheduling pass of a decode instance: adopt transferred sequences, then iterate.
	 * <p>
	 * A decode replica never prefills and never suspends a running sequence. Its sequences arrive
	 * with their cache already computed elsewhere, so a suspension would throw away state this
	 * device cannot recompute, and the whole point of disaggregation is that a decode step is not
	 * delayed by the prefill of another request. The loop therefore only makes room from completions.
	 *
	 * @param clock current simulation time
	 */
	protected void pumpDecode(double clock) {
		sortWaitingIfNeeded();
		while (!waiting.isEmpty() && running.size() < spec.getMaxBatchSize()) {
			InferenceRequest head = waiting.get(0);
			int tokens = head.getCachedTokens();
			if (pool.blocksFor(tokens) > pool.getTotalBlocks()) {
				waiting.remove(0);
				head.setCachedTokens(0);
				releaseOf(head);
				service.finish(head, RequestState.REJECTED, OutcomeReason.PROMPT_DOES_NOT_FIT);
				continue;
			}
			if (!pool.reserve(head.getRequestId(), tokens)) {
				cacheRefusals++;
				break;
			}
			waiting.remove(0);
			head.addQueueingTime(clock - head.getLastQueueEntryTime());
			joinBatch(head);
			if (head.getState().isTerminal())
				continue;
		}
		if (startDecodeIteration())
			return;
		if (running.isEmpty()) {
			chargeIdleUntil(clock);
			return;
		}
		stalledSteps++;
		chargeIdleUntil(clock);
	}

	/**
	 * Places a fully prefilled request in the decode batch and emits the token its prefill produced.
	 *
	 * @param request the request that finished its prefill
	 */
	protected void joinBatch(InferenceRequest request) {
		prefilling = null;
		// A prompt that finished prefilling here moves PREFILLING to DECODING; a sequence handed
		// over by a prefill instance arrives QUEUED with its cache already reserved, and joins the
		// batch without a prefill of its own.
		if (request.getState() == RequestState.PREFILLING || request.getState() == RequestState.QUEUED)
			request.transitionTo(RequestState.DECODING);
		pendingWorkByRequest.remove(request.getRequestId());
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
		// One token per sequence when speculation is off, so the reservation, the cost call and the
		// emission below all reduce to the plain continuous-batching step they were before the mechanism.
		int room = spec.acceptedTokensPerStep();
		List<InferenceRequest> batch = new ArrayList<>();
		for (InferenceRequest request : running.values()) {
			if (batch.size() >= spec.getMaxBatchSize())
				break;
			if (pool.reserve(request.getRequestId(), request.getCachedTokens() + room))
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
		DecodeBatch shape = new DecodeBatch(batch.size(), contexts, max);
		CostEstimate estimate = room > 1
				? cost.speculativeIteration(model, device, shape, spec.getDraftTokens())
				: cost.decodeIteration(model, device, shape);
		double duration = estimate.seconds();
		for (InferenceRequest request : batch) {
			request.addDecodeSeconds(duration / batch.size());
			request.setAllocatedBlocks(pool.allocatedBlocks(request.getRequestId()));
		}
		chargeEnergyOfStep(duration, batch);
		decodeIterations++;
		batchSizeSum += batch.size();
		peakBatchSize = Math.max(peakBatchSize, batch.size());
		iterationSeconds = duration;
		iterationAccepted = room;
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
		int accepted = iterationAccepted;
		double secondsThisStep = iterationSeconds;
		iterationAccepted = 1;
		iterationSeconds = 0;
		if (batch == null) {
			pump();
			return;
		}
		List<InferenceRequest> finished = new ArrayList<>();
		for (InferenceRequest request : batch) {
			if (request.getState() != RequestState.DECODING)
				continue;
			if (accepted <= 1) {
				request.setCachedTokens(request.getCachedTokens() + 1);
				request.emitToken(clock, clock - previousTokenTime(request));
				decodeTokenEmissions++;
				if (isFinished(request))
					finished.add(request);
				else if (reachesContextLimit(request))
					stopAtContextLimit(request);
				continue;
			}
			// A verification step emits its accepted tokens at the end of one iteration, so the step
			// duration is split evenly over them. The loop stops the instant the sequence has produced
			// everything it asked for or would pass the context window, so speculation never overshoots.
			double perToken = secondsThisStep / accepted;
			for (int t = 0; t < accepted; t++) {
				if (isFinished(request))
					break;
				if (reachesContextLimit(request)) {
					stopAtContextLimit(request);
					break;
				}
				request.setCachedTokens(request.getCachedTokens() + 1);
				request.emitToken(clock, perToken);
				decodeTokenEmissions++;
			}
			if (request.getState() == RequestState.DECODING && isFinished(request))
				finished.add(request);
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
		publishPrefix(request);
		service.finish(request, RequestState.COMPLETED, OutcomeReason.CONTEXT_LIMIT_REACHED);
		releaseResourcesOf(request);
	}

	/**
	 * Ends a request that generated everything it asked for.
	 *
	 * @param request the completed request
	 */
	protected void complete(InferenceRequest request) {
		publishPrefix(request);
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
	 * Gives a fresh request the prompt prefix another request already left in the cache.
	 * <p>
	 * When the mechanism is off the method returns at once, so a prefill starts from zero cached
	 * tokens exactly as it did before it existed. When it is on and the whole prompt of a completed
	 * request was cached under the same prefix key, the blocks are reserved here and {@code request}
	 * starts with those tokens already cached, so the prefill recompute is skipped for them. The
	 * blocks still occupy the pool as they would after a recompute, so cache accounting is unchanged;
	 * only the recomputation cost falls.
	 *
	 * @param request the request about to be prefilled, at the head of the queue
	 */
	protected void applyPrefixReuse(InferenceRequest request) {
		if (!spec.isPrefixCache())
			return;
		// Only a prompt that has not begun to be cached can take the shortcut; a resumed or partially
		// prefilled sequence keeps the progress it already made.
		if (request.getCachedTokens() != 0 || request.getGeneratedTokens() != 0)
			return;
		int shared = Math.min(sharedPrefixTokens(request), request.getInputTokens());
		if (shared <= 0)
			return;
		if (!pool.reserve(request.getRequestId(), shared)) {
			cacheRefusals++;
			return;
		}
		request.setCachedTokens(shared);
		request.setAllocatedBlocks(pool.allocatedBlocks(request.getRequestId()));
		request.addReusedPrefixTokens(shared);
		prefixHits++;
		prefixReusedTokens += shared;
	}

	/**
	 * Returns the prompt length already cached for the key this request would reuse, or zero when
	 * the key has not been published yet.
	 *
	 * @param request the request whose shared prefix is looked up
	 * @return cached prompt tokens, zero when there is no entry
	 */
	protected int sharedPrefixTokens(InferenceRequest request) {
		Integer cached = prefixByPrompt.get(request.prefixKey());
		return cached == null ? 0 : cached;
	}

	/**
	 * Publishes the prompt of a finishing request so a later request with the same prefix key can
	 * reuse it. The map is bounded: the least recently used entry is dropped once the cap is reached,
	 * so a long run cannot grow the index without limit.
	 *
	 * @param request the request leaving the replica after a successful serve
	 */
	protected void publishPrefix(InferenceRequest request) {
		if (!spec.isPrefixCache())
			return;
		if (prefixByPrompt.size() >= PREFIX_CACHE_CAPACITY && !prefixByPrompt.containsKey(request.prefixKey()))
			prefixByPrompt.remove(prefixByPrompt.keySet().iterator().next());
		prefixByPrompt.put(request.prefixKey(), request.getInputTokens());
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
		// One token per sequence per step when speculation is off, so the divisor below leaves the
		// estimate exactly as it was before the mechanism.
		int room = spec.acceptedTokensPerStep();
		double perToken = cost.decodeIteration(model, device, new DecodeBatch(1, prompt + 1, prompt + 1)).seconds();
		int tokens = Math.max(0, Math.min(request.getPredictedOutputTokens(), request.getMaxOutputTokens()) - 1);
		// A decode replica inherits the prompt cache, so the work it still owes a waiting request
		// is the remaining tokens alone; charging it a prefill would overstate its load and steer
		// routing away from the instances that are the cheapest place to finish a sequence.
		if (spec.getRole() == ReplicaSpec.Role.DECODE)
			return perToken * tokens / room;
		// A prompt whose prefix is already cached owes only the tokens after the shared prefix.
		int reused = spec.isPrefixCache() ? Math.min(sharedPrefixTokens(request), prompt) : 0;
		double prefill = cost.prefillStep(model, device, Math.max(1, prompt - reused), reused).seconds();
		return prefill + perToken * tokens / room;
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
		return new ReplicaLoad(getId(), device.getId(), device.getDeviceType(), spec.getMaxBatchSize(), waiting.size(),
				running.size(), pool.freeTokens(), pool.capacityTokens(), pending);
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

	/**
	 * Returns the cache pool that holds the block reservations of the sequences, the structure that
	 * decides, at each decode iteration, which running sequence has room for one more token.
	 *
	 * @return the pool, never {@code null}
	 */
	public KvCachePool getPool() {
		return pool;
	}

	/**
	 * Returns the memory budget of the device, from which the size of the cache pool is derived
	 * once, when the replica is built.
	 *
	 * @return the memory manager, never {@code null}
	 */
	public GpuMemoryManager getMemory() {
		return memory;
	}

	/**
	 * Returns the energy integrator of the device, the one that receives an active interval for
	 * every charged step and an idle interval for every gap between two steps.
	 *
	 * @return the power model, never {@code null}
	 */
	public GpuPowerModel getPower() {
		return power;
	}

	/**
	 * Returns the model profile every step of this replica is priced against.
	 *
	 * @return the model, never {@code null}
	 */
	public ModelProfile getModel() {
		return model;
	}

	/**
	 * Returns the device profile every step of this replica is priced against, the source of the
	 * idle power used when the serving loop has nothing to schedule.
	 *
	 * @return the device, never {@code null}
	 */
	public GpuProfile getDevice() {
		return device;
	}

	/**
	 * Returns the bearer the traffic of this replica queues on.
	 * <p>
	 * The service consults it twice: before the queueing step, to hold the request for the time its
	 * prompt needs to reach the device, and when a response ends, to charge the tokens travelling
	 * back. A {@code null} answer means the deployment assumes the clients are attached to the
	 * device itself.
	 *
	 * @return the access link, {@code null} when the replica is not reached over one
	 */
	public AccessLink getLink() {
		return link;
	}

	/**
	 * Returns the deployment limits of the replica: the batch cap, the prefill chunk shape, the
	 * context window and the preemption order the serving loop reads.
	 *
	 * @return the specification, never {@code null}
	 */
	public ReplicaSpec getSpec() {
		return spec;
	}

	/**
	 * Returns the phase of serving this replica is responsible for, which the serving loop reads to
	 * decide whether it prefills, decodes or does both.
	 *
	 * @return the role, {@link ReplicaSpec.Role#COLOCATED} for every replica of an unsplit
	 *         deployment
	 */
	public ReplicaSpec.Role getRole() {
		return spec.getRole();
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

	/**
	 * Returns how many prefill chunks the replica scheduled since the start of the run, one per
	 * charged step that read a chunk of a prompt, residual chunks included.
	 *
	 * @return prefill steps, a plain count
	 */
	public long getPrefillSteps() {
		return prefillSteps;
	}

	/**
	 * Returns how many decode iterations the replica started since the start of the run, which is
	 * the denominator of {@link #meanBatchSize()} and of the mean iteration cost.
	 *
	 * @return decode iterations, a plain count
	 */
	public long getDecodeIterations() {
		return decodeIterations;
	}

	/**
	 * Returns the largest number of sequences that shared one decode iteration, against the batch
	 * cap of the specification.
	 *
	 * @return sequences in the widest iteration, zero when no iteration ran
	 */
	public int getPeakBatchSize() {
		return peakBatchSize;
	}

	/**
	 * Returns how many running sequences were suspended to free cache, each suspension costing a
	 * re-prefill of the prompt and of every token already produced.
	 *
	 * @return preemption events, a plain count
	 */
	public long getPreemptions() {
		return preemptions;
	}

	/**
	 * Returns how often the pool refused a reservation, whether for a prefill chunk or for the next
	 * token of a running sequence, so this counter moves without a suspension.
	 *
	 * @return refused reservations, a plain count
	 */
	public long getCacheRefusals() {
		return cacheRefusals;
	}

	/**
	 * Returns how often the serving loop ended without scheduling a step while the replica still
	 * held requests, and charged the gap to the device as idle time.
	 *
	 * @return stalled decisions, a plain count
	 */
	public long getStalledSteps() {
		return stalledSteps;
	}

	/**
	 * Returns the number of tokens the decode iterations emitted, which exceeds the iteration count
	 * only when speculative decoding lets a step emit more than one token per sequence.
	 *
	 * @return emitted decode tokens, a plain count
	 */
	public long getDecodeTokenEmissions() {
		return decodeTokenEmissions;
	}

	/**
	 * Returns how many requests took at least some of their prompt from the prefix cache. Zero until
	 * the mechanism is turned on, since a request with prefix reuse off always recomputes its prompt.
	 *
	 * @return requests served partly from a shared prefix
	 */
	public long getPrefixHits() {
		return prefixHits;
	}

	/**
	 * Returns the prompt tokens served from the prefix cache instead of recomputed, summed over the
	 * requests that hit it.
	 *
	 * @return reused prompt tokens, a plain count
	 */
	public long getPrefixReusedTokens() {
		return prefixReusedTokens;
	}

	/**
	 * Returns one line for logs and test failures, with the identifier, the model, the device and
	 * the queue, running and block counts at the instant of the call.
	 *
	 * @return the summary line
	 */
	@Override
	public String toString() {
		return String.format(Locale.ROOT,
				"ReplicaServer[id=%d, model=%s, device=%s, waiting=%d, running=%d, blocks=%d/%d]", getId(),
				model.getId(), device.getId(), waiting.size(), running.size(), pool.usedBlocks(),
				pool.getTotalBlocks());
	}
}
