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
	 * Describes the load of a replica.
	 *
	 * @param replicaId          the replica identifier
	 * @param waitingRequests    queued requests, zero or more
	 * @param runningSequences   sequences decoding now, zero or more
	 * @param freeCacheTokens    tokens the cache pool can still hand out
	 * @param totalCacheTokens   cache capacity of the replica, in tokens
	 * @param pendingWorkSeconds estimated work of the waiting requests, in seconds
	 */
	public ReplicaLoad(int replicaId, int waitingRequests, int runningSequences, int freeCacheTokens,
			int totalCacheTokens, double pendingWorkSeconds) {
		if (waitingRequests < 0 || runningSequences < 0 || freeCacheTokens < 0)
			throw new IllegalArgumentException("Replica " + replicaId + " reports a negative load");
		if (totalCacheTokens < freeCacheTokens)
			throw new IllegalArgumentException("Replica " + replicaId + " reports " + freeCacheTokens
					+ " free tokens out of a capacity of " + totalCacheTokens);
		if (pendingWorkSeconds < 0 || Double.isNaN(pendingWorkSeconds))
			throw new IllegalArgumentException("Replica " + replicaId + " reports a pending work of "
					+ pendingWorkSeconds + " s");
		this.replicaId = replicaId;
		this.waitingRequests = waitingRequests;
		this.runningSequences = runningSequences;
		this.freeCacheTokens = freeCacheTokens;
		this.totalCacheTokens = totalCacheTokens;
		this.pendingWorkSeconds = pendingWorkSeconds;
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
		return "ReplicaLoad[" + replicaId + ", waiting " + waitingRequests + ", running " + runningSequences
				+ ", free cache " + freeCacheTokens + "/" + totalCacheTokens + " tokens, pending "
				+ String.format("%.3f", pendingWorkSeconds) + " s]";
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
