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
package com.mechalikh.docket.metrics;

/**
 * One point of the resource time series of a replica.
 * <p>
 * The samples are taken on a fixed interval, so the series shows the shape of the occupancy and
 * of the batch, which is what a reader needs to judge whether a latency came from a busy device
 * or from an empty one. A sample is a snapshot, not an average over the interval.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 0.1
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

	/**
	 * Returns the instant of the snapshot on the simulation clock, in seconds.
	 * <p>
	 * The samples of a replica are evenly spaced by the sampling interval, so the mean of their
	 * times is the mid-point of the interval the series covers.
	 *
	 * @return simulation seconds since the start of the run
	 */
	public double time() {
		return time;
	}

	/**
	 * Returns the replica the snapshot was taken on.
	 * <p>
	 * Samples are grouped by this identifier before any mean is taken, so the series of one
	 * replica never mixes the occupancy of two devices.
	 *
	 * @return the engine identifier of the replica
	 */
	public int replicaId() {
		return replicaId;
	}

	/**
	 * Returns how many sequences were generating tokens at the instant of the snapshot.
	 * <p>
	 * The mean over the samples of a replica is the average batch size, in sequences.
	 *
	 * @return sequences decoding, zero or more
	 */
	public int runningSequences() {
		return runningSequences;
	}

	/**
	 * Returns how many requests were queued for their prefill at the instant of the snapshot.
	 * <p>
	 * The mean over the samples of a replica is the average queue length, in requests.
	 *
	 * @return requests waiting, zero or more
	 */
	public int waitingRequests() {
		return waitingRequests;
	}

	/**
	 * Returns how many cache blocks were handed out at the instant of the snapshot.
	 * <p>
	 * The mean over the samples of a replica is the average block usage, in blocks, and the
	 * maximum is the peak occupancy the series records.
	 *
	 * @return blocks in use, between zero and {@link #totalBlocks()}
	 */
	public int usedBlocks() {
		return usedBlocks;
	}

	/**
	 * Returns how many cache blocks the replica holds in total.
	 * <p>
	 * The capacity is fixed for the whole run, so the mean over the samples is that constant.
	 *
	 * @return blocks of the cache, at least one
	 */
	public int totalBlocks() {
		return totalBlocks;
	}

	/**
	 * Returns how many tokens the live reservations could hold at the instant of the snapshot.
	 * <p>
	 * The mean over the samples of a replica is the average token footprint of the requests it
	 * held, in tokens.
	 *
	 * @return reserved tokens, zero or more
	 */
	public int reservedTokens() {
		return reservedTokens;
	}

	/**
	 * Returns the energy charged to the replica up to the instant of the snapshot.
	 * <p>
	 * The value is cumulative, in joules, so the series rises with the clock and is not averaged
	 * across the samples: the difference between two samples is the energy of the interval
	 * between them, and the last sample of a run is the total of that replica.
	 *
	 * @return energy consumed so far, in joules
	 */
	public double energyJoule() {
		return energyJoule;
	}

	/**
	 * Tells whether the device was executing rather than idle at the instant of the snapshot.
	 * <p>
	 * The share of the samples that return {@code true} is the duty cycle of the replica over
	 * the run, a fraction in [0,1].
	 *
	 * @return {@code true} when the device was executing
	 */
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

	/**
	 * Returns a one-line summary with the instant, the replica, the queue and batch sizes, the
	 * block usage and the energy.
	 *
	 * @return the summary line
	 */
	@Override
	public String toString() {
		return "ResourceSample[t=" + time + ", replica=" + replicaId + ", running=" + runningSequences + ", waiting="
				+ waitingRequests + ", blocks=" + usedBlocks + "/" + totalBlocks + ", energy=" + energyJoule + " J]";
	}
}
