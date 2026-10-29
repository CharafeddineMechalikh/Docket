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
package com.mechalikh.genaism.metrics;

/**
 * One point of the resource time series of a replica.
 * <p>
 * The samples are taken on a fixed interval, so the series shows the shape of the occupancy and
 * of the batch, which is what a reader needs to judge whether a latency came from a busy device
 * or from an empty one. A sample is a snapshot, not an average over the interval.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public final class ResourceSample {

	/** Simulation time of the snapshot, in seconds. */
	private final double time;
	/** Replica the snapshot belongs to. */
	private final int replicaId;
	/** Sequences generating tokens at that instant. */
	private final int runningSequences;
	/** Requests waiting for their prefill at that instant. */
	private final int waitingRequests;
	/** Cache blocks handed out at that instant. */
	private final int usedBlocks;
	/** Cache blocks the replica holds in total. */
	private final int totalBlocks;
	/** Tokens the reservations can hold at that instant. */
	private final int reservedTokens;
	/** Energy charged to the replica up to that instant, in joules. */
	private final double energyJoule;
	/** Whether the device was executing rather than idle at that instant. */
	private final boolean executing;

	/**
	 * Creates a snapshot.
	 *
	 * @param time             simulation time, in seconds
	 * @param replicaId        the replica
	 * @param runningSequences sequences decoding, zero or more
	 * @param waitingRequests  requests queued, zero or more
	 * @param usedBlocks       cache blocks in use
	 * @param totalBlocks      cache blocks of the replica, at least one
	 * @param reservedTokens   tokens the reservations can hold
	 * @param energyJoule      energy consumed so far, joules
	 * @param executing        whether the device was executing at that instant
	 */
	public ResourceSample(double time, int replicaId, int runningSequences, int waitingRequests, int usedBlocks,
			int totalBlocks, int reservedTokens, double energyJoule, boolean executing) {
		if (time < 0 || Double.isNaN(time))
			throw new IllegalArgumentException("A sample cannot be taken at time " + time);
		if (runningSequences < 0 || waitingRequests < 0 || usedBlocks < 0 || reservedTokens < 0)
			throw new IllegalArgumentException("Replica " + replicaId + " reported a negative occupancy at t=" + time);
		if (totalBlocks < 1 || usedBlocks > totalBlocks)
			throw new IllegalArgumentException("Replica " + replicaId + " reports " + usedBlocks
					+ " blocks used out of " + totalBlocks + " at t=" + time);
		if (energyJoule < 0 || Double.isNaN(energyJoule))
			throw new IllegalArgumentException("Replica " + replicaId + " reports an energy of " + energyJoule
					+ " J at t=" + time);
		this.time = time;
		this.replicaId = replicaId;
		this.runningSequences = runningSequences;
		this.waitingRequests = waitingRequests;
		this.usedBlocks = usedBlocks;
		this.totalBlocks = totalBlocks;
		this.reservedTokens = reservedTokens;
		this.energyJoule = energyJoule;
		this.executing = executing;
	}

	public double time() {
		return time;
	}

	public int replicaId() {
		return replicaId;
	}

	public int runningSequences() {
		return runningSequences;
	}

	public int waitingRequests() {
		return waitingRequests;
	}

	public int usedBlocks() {
		return usedBlocks;
	}

	public int totalBlocks() {
		return totalBlocks;
	}

	public int reservedTokens() {
		return reservedTokens;
	}

	public double energyJoule() {
		return energyJoule;
	}

	public boolean isExecuting() {
		return executing;
	}

	/**
	 * Returns the fraction of the cache that was handed out.
	 *
	 * @return a ratio between zero and one
	 */
	public double cacheOccupancy() {
		return (double) usedBlocks / totalBlocks;
	}

	@Override
	public String toString() {
		return "ResourceSample[t=" + time + ", replica=" + replicaId + ", running=" + runningSequences + ", waiting="
				+ waitingRequests + ", blocks=" + usedBlocks + "/" + totalBlocks + ", energy=" + energyJoule + " J]";
	}
}
