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

/**
 * The load of one replica, as the routing and admission policies can see it.
 * <p>
 * Policies are given a view rather than the replica itself so that they cannot change the
 * system: a routing decision that mutated the server would make the order of the decisions
 * depend on the implementation of the policy.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 0.1
 */
public final class ReplicaLoad {

	/** Identifier of the replica. */
	private final int replicaId;
	/** Identifier of the device this replica runs on, distinct for every replica of a fleet. */
	private final String deviceId;
	/** Device family of the machine, the tier a placement policy can order replicas by. */
	private final String tier;
	/** Largest batch this replica may form, a per-machine limit in a heterogeneous fleet. */
	private final int maxBatchSize;
	/** Requests waiting for their prefill at this replica. */
	private final int waitingRequests;
	/** Sequences currently generating tokens on the device. */
	private final int runningSequences;
	/** Cache blocks the pool still holds back. */
	private final int freeCacheTokens;
	/** Total cache capacity of the replica, in tokens. */
	private final int totalCacheTokens;
	/** Work already promised to the waiting requests, in seconds. */
	private final double pendingWorkSeconds;
	/**
	 * Seconds this replica needs to prefill the request the snapshot was taken for, published by the
	 * serving loop from the deployment's execution cost model, or {@code NaN} when the snapshot
	 * carries no price.
	 */
	private final double requestPrefillSeconds;
	/**
	 * Seconds one generated token costs this replica at the batch it is holding now, derived from the
	 * deployment's execution cost model, or {@code NaN} when the snapshot carries no price.
	 */
	private final double decodeSecondsPerToken;
	/**
	 * One-way propagation delay of the bearer this replica is reached through, in seconds, zero when
	 * the deployment models no access link.
	 */
	private final double accessSeconds;

	/**
	 * Describes the load of a replica.
	 *
	 * @param replicaId          the replica identifier
	 * @param deviceId           the identifier of the device the replica runs on
	 * @param tier               the device family, as the scenario declares it
	 * @param maxBatchSize       the batch ceiling of this replica
	 * @param waitingRequests    queued requests, zero or more
	 * @param runningSequences   sequences decoding now, zero or more
	 * @param freeCacheTokens    tokens the cache pool can still hand out
	 * @param totalCacheTokens   cache capacity of the replica, in tokens
	 * @param pendingWorkSeconds estimated work of the waiting requests, in seconds
	 */
	public ReplicaLoad(int replicaId, String deviceId, String tier, int maxBatchSize, int waitingRequests,
			int runningSequences, int freeCacheTokens, int totalCacheTokens, double pendingWorkSeconds) {
		this(replicaId, deviceId, tier, maxBatchSize, waitingRequests, runningSequences, freeCacheTokens,
				totalCacheTokens, pendingWorkSeconds, Double.NaN, Double.NaN, 0);
	}

	/**
	 * Describes the load of one replica together with the marginal prices its device carries.
	 * <p>
	 * The three figures a serving loop adds here are not measurements of this request: they are the
	 * price one prompt token and one generated token have at this replica right now, taken from the
	 * deployment's execution cost model, plus the propagation delay of the bearer its clients reach
	 * it through. A policy that only counts requests or queue depth leaves them unused; a policy that
	 * has to say how long a request would take, a fidelity grant or a placement rule, reads them
	 * instead of assuming a device is fast because it is called cloud.
	 *
	 * @param replicaId                    the replica identifier
	 * @param deviceId                     the identifier of the device the replica runs on
	 * @param tier                         the device family, as the scenario declares it
	 * @param maxBatchSize                 the batch ceiling of this replica
	 * @param waitingRequests              queued requests, zero or more
	 * @param runningSequences             sequences decoding now, zero or more
	 * @param freeCacheTokens              tokens the cache pool can still hand out
	 * @param totalCacheTokens             cache capacity of the replica, in tokens
	 * @param pendingWorkSeconds           estimated work of the waiting requests, in seconds
	 * @param requestPrefillSeconds        seconds this replica needs to prefill the request the
	 *                                     snapshot was taken for, {@code NaN} when it publishes no
	 *                                     price
	 * @param decodeSecondsPerToken        seconds one generated token costs here at the current
	 *                                     batch, {@code NaN} when the snapshot publishes no price
	 * @param accessSeconds                one-way delay of the bearer, propagation plus the prompt's
	 *                                     own transfer, in seconds, zero when the deployment models
	 *                                     no access link
	 */
	public ReplicaLoad(int replicaId, String deviceId, String tier, int maxBatchSize, int waitingRequests,
			int runningSequences, int freeCacheTokens, int totalCacheTokens, double pendingWorkSeconds,
			double requestPrefillSeconds, double decodeSecondsPerToken, double accessSeconds) {
		if (waitingRequests < 0 || runningSequences < 0 || freeCacheTokens < 0)
			throw new IllegalArgumentException("Replica " + replicaId + " reports a negative load");
		if (totalCacheTokens < freeCacheTokens)
			throw new IllegalArgumentException("Replica " + replicaId + " reports " + freeCacheTokens
					+ " free tokens out of a capacity of " + totalCacheTokens);
		if (pendingWorkSeconds < 0 || Double.isNaN(pendingWorkSeconds))
			throw new IllegalArgumentException("Replica " + replicaId + " reports a pending work of "
					+ pendingWorkSeconds + " s");
		if (maxBatchSize < 1)
			throw new IllegalArgumentException("Replica " + replicaId + " reports a batch ceiling of " + maxBatchSize);
		if (requestPrefillSeconds < 0)
			throw new IllegalArgumentException("Replica " + replicaId + " reports a prefill estimate of "
					+ requestPrefillSeconds + " s; an estimate is zero or positive, or NaN when the snapshot "
					+ "publishes none");
		if (decodeSecondsPerToken < 0)
			throw new IllegalArgumentException("Replica " + replicaId + " reports a decode price of "
					+ decodeSecondsPerToken + " s per generated token; a price is zero or positive, or NaN when the "
					+ "snapshot publishes none");
		if (accessSeconds < 0 || Double.isNaN(accessSeconds))
			throw new IllegalArgumentException("Replica " + replicaId + " reports an access delay of " + accessSeconds
					+ " s; a deployment that names no bearer reports zero");
		this.replicaId = replicaId;
		this.deviceId = deviceId == null ? "" : deviceId;
		this.tier = tier == null ? "" : tier;
		this.maxBatchSize = maxBatchSize;
		this.waitingRequests = waitingRequests;
		this.runningSequences = runningSequences;
		this.freeCacheTokens = freeCacheTokens;
		this.totalCacheTokens = totalCacheTokens;
		this.pendingWorkSeconds = pendingWorkSeconds;
		this.requestPrefillSeconds = requestPrefillSeconds;
		this.decodeSecondsPerToken = decodeSecondsPerToken;
		this.accessSeconds = accessSeconds;
	}

	/**
	 * Returns the identifier of the replica the snapshot was taken on.
	 * <p>
	 * It is the engine identifier, the same value a routing policy returns to choose a replica.
	 * The load is a snapshot read when the policy asked for it, not a live handle on the server:
	 * the numbers keep the instant they were taken and never follow later changes.
	 *
	 * @return the replica identifier
	 */
	public int replicaId() {
		return replicaId;
	}

	/**
	 * Returns the identifier of the device the replica runs on.
	 * <p>
	 * A fleet gives every machine its own identifier, so this distinguishes two replicas of one
	 * profile from one replica of two profiles.
	 *
	 * @return the device identifier
	 */
	public String deviceId() {
		return deviceId;
	}

	/**
	 * Returns the device family the scenario declared for this replica, which is how a placement
	 * policy names tiers, for instance {@code edge-device} or {@code cloud-accelerator}.
	 * <p>
	 * The value is a label the scenario chose, not a property the simulator measures; it orders
	 * machines only as far as the person who wrote the file said they should be ordered.
	 *
	 * @return the tier label
	 */
	public String tier() {
		return tier;
	}

	/**
	 * Returns the batch ceiling of this replica, which a heterogeneous fleet sets per device.
	 *
	 * @return sequences
	 */
	public int maxBatchSize() {
		return maxBatchSize;
	}

	/**
	 * Returns how many requests were queued for their prefill in the snapshot.
	 * <p>
	 * A queued request holds no sequence yet; it waits for a decode slot and for the cache of
	 * its prompt. The count is taken at one instant, so it does not follow the queue afterwards.
	 *
	 * @return waiting requests, zero or more
	 */
	public int waitingRequests() {
		return waitingRequests;
	}

	/**
	 * Returns how many sequences were generating tokens in the snapshot.
	 * <p>
	 * Each one is a request past its prefill that occupies a slot of the current batch, so the
	 * value is the batch size the device was serving at that instant.
	 *
	 * @return sequences decoding, zero or more
	 */
	public int runningSequences() {
		return runningSequences;
	}

	/**
	 * Returns how many tokens the cache of the replica could still hand out in the snapshot.
	 * <p>
	 * Tokens held by the running sequences and by the requests already promised a reservation
	 * are excluded, so the value is what an admitted request could actually claim.
	 *
	 * @return free cache tokens, between zero and {@link #totalCacheTokens()}
	 */
	public int freeCacheTokens() {
		return freeCacheTokens;
	}

	/**
	 * Returns the cache capacity of the replica, in tokens.
	 * <p>
	 * The capacity is fixed for the run, so every snapshot of one replica reports the same
	 * value and the free tokens can be read as a fraction of it.
	 *
	 * @return cache capacity in tokens, at least the free tokens
	 */
	public int totalCacheTokens() {
		return totalCacheTokens;
	}

	/**
	 * Returns the estimated work already promised to the requests in the queue.
	 * <p>
	 * The estimate is the sum of one prefill step and as many decode iterations as the predicted
	 * output length of each waiting request, priced at the batch size of the replica. It is a
	 * proxy for the wait, not a promise: a request that arrives later but is placed first does
	 * not change it.
	 *
	 * @return seconds
	 */
	public double pendingWorkSeconds() {
		return pendingWorkSeconds;
	}

	/**
	 * Returns the seconds this replica would spend prefilling the prompt of the request the snapshot
	 * was taken for.
	 * <p>
	 * The figure comes from the execution cost model of the deployment, so two replicas of one fleet
	 * publish different numbers for one prompt, and a scenario that changes the device, the precision
	 * or the prefill chunk changes the answer. It prices the prompt as one step; a chunked prefill
	 * re-reads the weights per chunk, so a long prompt on a small chunk is priced slightly cheaply,
	 * which is the same approximation the queue's pending work already makes.
	 *
	 * @return seconds, or {@code NaN} when the snapshot publishes no price
	 */
	public double requestPrefillSeconds() {
		return requestPrefillSeconds;
	}

	/**
	 * Returns the price of one generated token at this replica, in seconds.
	 * <p>
	 * This is the decode iteration a sequence pays at the batch width and the cached context the
	 * replica is holding when the snapshot was taken, so it rises with the traffic the machine is
	 * already carrying and with the context its sequences have grown to, which is exactly what a
	 * placement or a fidelity rule needs to see. Speculative decoding, where on, lowers it because a
	 * step emits several tokens.
	 *
	 * @return seconds per generated token, or {@code NaN} when the snapshot publishes no price
	 */
	public double decodeSecondsPerToken() {
		return decodeSecondsPerToken;
	}

	/**
	 * Returns the one-way delay the bearer in front of this replica would add to the request the
	 * snapshot was taken for.
	 * <p>
	 * The figure is the propagation delay plus the transfer time of the prompt at the raw capacity of
	 * the bearer, which is what a hop costs when nothing is queued in front of it: the wait behind the
	 * transfers already on the wire is not included, because a decision taken now cannot know what
	 * will be pushed before it. A deployment that names no bearer reports zero.
	 *
	 * @return seconds
	 */
	public double accessSeconds() {
		return accessSeconds;
	}

	/**
	 * Predicts how long the request the snapshot was taken for would take at this replica if it were
	 * granted the given reasoning budget.
	 * <p>
	 * The prediction adds the delay of the hop to the work the queue already promised, the prefill of
	 * this prompt and the decode of the budget at the batch this machine is holding now. Every term
	 * but the queue comes from the deployment's execution cost model, so the seconds a smaller budget
	 * saves are the seconds the roofline says they are, and they differ between the tiers of a fleet:
	 * that is what makes a fidelity grant a serving decision rather than a multiplier. It is a
	 * prediction of the instant the snapshot was taken, not of the future, and it says nothing about
	 * the requests that arrive after it.
	 *
	 * @param outputTokens the reasoning budget the estimate prices, at least one
	 * @return the predicted delay in seconds, or {@code NaN} when the snapshot publishes no prices
	 */
	public double predictedDelaySeconds(int outputTokens) {
		if (Double.isNaN(requestPrefillSeconds) || Double.isNaN(decodeSecondsPerToken))
			return Double.NaN;
		if (outputTokens < 1)
			throw new IllegalArgumentException("Replica " + replicaId + " is asked to price " + outputTokens
					+ " generated tokens, at least one is required");
		return accessSeconds + pendingWorkSeconds + requestPrefillSeconds
				+ outputTokens * decodeSecondsPerToken;
	}

	/**
	 * Returns the number of requests in the replica, waiting and running together.
	 *
	 * @return requests inside the replica
	 */
	public int insideReplica() {
		return waitingRequests + runningSequences;
	}

	/**
	 * Tells whether the replica could hold the cache of a request of the given size.
	 *
	 * @param tokens context length the request reaches
	 * @return {@code true} when the free cache covers it
	 */
	public boolean canHoldContext(int tokens) {
		return freeCacheTokens >= tokens;
	}

	/**
	 * Returns a one-line summary with the replica, the queue and batch sizes, the free cache as
	 * a fraction of the capacity, and the pending work.
	 *
	 * @return the summary line
	 */
	@Override
	public String toString() {
		StringBuilder out = new StringBuilder("ReplicaLoad[").append(replicaId).append(" on ").append(deviceId)
				.append(" (").append(tier).append("), waiting ").append(waitingRequests).append(", running ")
				.append(runningSequences).append(", free cache ").append(freeCacheTokens).append('/').append(totalCacheTokens)
				.append(" tokens, pending ").append(String.format("%.3f", pendingWorkSeconds)).append(" s");
		if (!Double.isNaN(requestPrefillSeconds))
			out.append(", prefill ").append(String.format("%.3f", requestPrefillSeconds)).append(" s");
		if (!Double.isNaN(decodeSecondsPerToken))
			out.append(", token ").append(String.format("%.5f", decodeSecondsPerToken)).append(" s");
		if (accessSeconds > 0)
			out.append(", access ").append(String.format("%.3f", accessSeconds)).append(" s");
		return out.append(']').toString();
	}

	/**
	 * Sorts the loads of a service by replica id, so a policy that scans them is deterministic.
	 *
	 * @param loads the loads, in any order
	 * @return the same list, sorted
	 */
	public static List<ReplicaLoad> sortedById(List<ReplicaLoad> loads) {
		loads.sort((a, b) -> Integer.compare(a.replicaId, b.replicaId));
		return loads;
	}

	/**
	 * Returns the load of the replica with the given identifier.
	 *
	 * @param loads     the loads of the service
	 * @param replicaId the replica to find
	 * @return the load, or {@code null} when the service has no such replica
	 */
	public static ReplicaLoad of(List<ReplicaLoad> loads, int replicaId) {
		for (ReplicaLoad load : loads)
			if (load.replicaId == replicaId)
				return load;
		return null;
	}

	/**
	 * Reports whether any replica holds a request, used by the shutdown check.
	 *
	 * @param loads the loads of the service
	 * @return {@code true} when every replica is empty
	 */
	public static boolean allIdle(List<ReplicaLoad> loads) {
		return loads.stream().allMatch(load -> load.insideReplica() == 0);
	}

	/**
	 * The request seen by a policy, so that the policy cannot mutate it.
	 *
	 * @param request the request being routed or admitted
	 * @return the request, never null
	 */
	protected static InferenceRequest requireRequest(InferenceRequest request) {
		if (request == null)
			throw new IllegalArgumentException("A policy decision needs the request it applies to");
		return request;
	}
}
