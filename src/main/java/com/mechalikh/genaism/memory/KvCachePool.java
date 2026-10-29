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
package com.mechalikh.genaism.memory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The paged key-value cache of one replica.
 * <p>
 * Memory is handed out in blocks of a fixed number of tokens, as serving engines with paged
 * attention do, so a reservation rounds up. Rounding up is why the simulator can report a
 * capacity that is smaller than the raw byte budget: a sequence that caches 17 tokens with a
 * block size of 16 holds two blocks.
 * <p>
 * The pool only answers allocation questions. Choosing which request to preempt when the pool
 * is full belongs to the scheduling policy, because that decision needs the lifecycle state of
 * the requests, not their block counts.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public class KvCachePool {

	/** Tokens held by one block. */
	protected final int tokensPerBlock;
	/** Bytes the cache needs for one token. */
	protected final double bytesPerToken;
	/** Number of blocks the pool was created with. */
	protected final int totalBlocks;
	/** Blocks reserved per request, in reservation order. */
	protected final Map<Long, Integer> blocksByRequest = new LinkedHashMap<>();
	/** Blocks currently handed out. */
	protected int usedBlocks;
	/** Largest number of blocks handed out at once. */
	protected int peakUsedBlocks;
	/** Number of times a reservation was refused for lack of blocks. */
	protected long refusedReservations;
	/** Largest number of tokens held at once. */
	protected int peakUsedTokens;

	/**
	 * Creates a pool from a byte budget.
	 *
	 * @param poolBytes     bytes the budget grants to the cache, positive
	 * @param kvBytesPerToken bytes the model needs to cache one token, positive
	 * @param tokensPerBlock  tokens per block, at least one
	 * @throws IllegalArgumentException when a figure is not positive
	 * @throws IllegalStateException    when the budget does not even hold one block
	 */
	public KvCachePool(double poolBytes, double kvBytesPerToken, int tokensPerBlock) {
		if (poolBytes <= 0)
			throw new IllegalArgumentException("The cache pool needs a positive byte budget, found " + poolBytes);
		if (kvBytesPerToken <= 0)
			throw new IllegalArgumentException("The cache needs a positive number of bytes per token, found "
					+ kvBytesPerToken);
		if (tokensPerBlock < 1)
			throw new IllegalArgumentException("A block holds at least one token, found " + tokensPerBlock);
		this.bytesPerToken = kvBytesPerToken;
		this.tokensPerBlock = tokensPerBlock;
		this.totalBlocks = (int) Math.floor(poolBytes / (kvBytesPerToken * tokensPerBlock));
		if (totalBlocks < 1)
			throw new IllegalStateException("A budget of " + poolBytes + " bytes holds no block of " + tokensPerBlock
					+ " tokens at " + kvBytesPerToken + " bytes per token ("
					+ kvBytesPerToken * tokensPerBlock + " bytes per block)");
	}

	/**
	 * Returns the number of blocks needed to cache a number of tokens.
	 *
	 * @param tokens tokens to cache, zero or more
	 * @return blocks, rounded up
	 */
	public int blocksFor(int tokens) {
		if (tokens < 0)
			throw new IllegalArgumentException("A reservation cannot be negative, found " + tokens);
		return (tokens + tokensPerBlock - 1) / tokensPerBlock;
	}

	/**
	 * Resizes the reservation of a request so that it can hold the given number of tokens.
	 * <p>
	 * The operation is atomic: when the pool cannot grant the extra blocks, the previous
	 * reservation is left untouched and the refusal is reported.
	 *
	 * @param requestId the request whose reservation changes
	 * @param tokens    tokens the request needs cached after the resize
	 * @return {@code true} when the reservation covers {@code tokens}
	 * @throws IllegalArgumentException when {@code tokens} exceeds what the pool can ever hold
	 */
	public boolean reserve(long requestId, int tokens) {
		int needed = blocksFor(tokens);
		if (needed > totalBlocks)
			throw new IllegalArgumentException("A single sequence asks for " + tokens + " cached tokens (" + needed
					+ " blocks) but the device holds at most " + capacityTokens() + " tokens (" + totalBlocks
					+ " blocks); lower the context length or the number of replicas per device");
		int current = blocksByRequest.getOrDefault(requestId, 0);
		if (needed <= current) {
			if (needed == 0)
				return release(requestId);
			int freed = current - needed;
			blocksByRequest.put(requestId, needed);
			usedBlocks -= freed;
			return true;
		}
		int delta = needed - current;
		if (delta > freeBlocks()) {
			refusedReservations++;
			return false;
		}
		blocksByRequest.put(requestId, needed);
		usedBlocks += delta;
		peakUsedBlocks = Math.max(peakUsedBlocks, usedBlocks);
		peakUsedTokens = Math.max(peakUsedTokens, usedTokens());
		return true;
	}

	/**
	 * Returns the blocks held by a request.
	 *
	 * @param requestId the request to free
	 * @return {@code true} when the request held a reservation
	 */
	public boolean release(long requestId) {
		Integer held = blocksByRequest.remove(requestId);
		if (held == null)
			return false;
		usedBlocks -= held;
		return true;
	}

	/**
	 * Returns the blocks a request holds.
	 *
	 * @param requestId the request
	 * @return blocks, zero when the request holds none
	 */
	public int allocatedBlocks(long requestId) {
		return blocksByRequest.getOrDefault(requestId, 0);
	}

	/**
	 * Returns the tokens a request can keep cached with its current reservation.
	 *
	 * @param requestId the request
	 * @return tokens of capacity, zero when the request holds no block
	 */
	public int allocatedTokens(long requestId) {
		return allocatedBlocks(requestId) * tokensPerBlock;
	}

	/**
	 * Returns the blocks that are not handed out.
	 *
	 * @return free blocks
	 */
	public int freeBlocks() {
		return totalBlocks - usedBlocks;
	}

	/**
	 * Returns the tokens that fit in the free blocks.
	 *
	 * @return tokens
	 */
	public int freeTokens() {
		return freeBlocks() * tokensPerBlock;
	}

	/**
	 * Returns the number of tokens all the reservations can hold, rounding down the budget.
	 *
	 * @return tokens
	 */
	public int capacityTokens() {
		return totalBlocks * tokensPerBlock;
	}

	/**
	 * Returns whether the pool has no block left.
	 *
	 * @return {@code true} when a further reservation would be refused
	 */
	public boolean isFull() {
		return freeBlocks() == 0;
	}

	/**
	 * Returns the fraction of the pool that is handed out.
	 *
	 * @return a ratio between zero and one
	 */
	public double occupancyRatio() {
		return (double) usedBlocks / totalBlocks;
	}

	/**
	 * Returns the sum of the tokens the current reservations can hold.
	 * <p>
	 * This is capacity, not usage: a reservation that rounds up to a full block for one token
	 * counts the whole block.
	 *
	 * @return tokens
	 */
	public int usedTokens() {
		return usedBlocks * tokensPerBlock;
	}

	public int usedBlocks() {
		return usedBlocks;
	}

	public int peakUsedBlocks() {
		return peakUsedBlocks;
	}

	public int getTotalBlocks() {
		return totalBlocks;
	}

	public int getTokensPerBlock() {
		return tokensPerBlock;
	}

	public double getBytesPerToken() {
		return bytesPerToken;
	}

	/**
	 * Returns how many times a reservation was refused because the pool was short.
	 *
	 * @return refusals since the pool was created
	 */
	public long getRefusedReservations() {
		return refusedReservations;
	}

	/**
	 * Returns the largest number of tokens held at once.
	 *
	 * @return peak tokens
	 */
	public int getPeakUsedTokens() {
		return peakUsedTokens;
	}

	/**
	 * Returns the requests that hold a reservation, in reservation order.
	 *
	 * @return an immutable view of the keys
	 */
	public Set<Long> reservedRequests() {
		return Collections.unmodifiableSet(blocksByRequest.keySet());
	}

	/**
	 * Checks that the counters agree with the reservations.
	 * <p>
	 * Invariant tests call this after a run: a leak, where a finished request keeps its blocks,
	 * or a double release that pushes the used count below zero, is a bug in the server rather
	 * than a property of the workload.
	 *
	 * @throws IllegalStateException when the bookkeeping is inconsistent
	 */
	public void checkConsistency() {
		int sum = 0;
		for (int blocks : blocksByRequest.values()) {
			if (blocks < 1)
				throw new IllegalStateException("A reservation of " + blocks + " blocks cannot be kept in the pool");
			sum += blocks;
		}
		if (sum != usedBlocks)
			throw new IllegalStateException("The pool counts " + usedBlocks + " used blocks but its reservations hold "
					+ sum);
		if (usedBlocks < 0 || usedBlocks > totalBlocks)
			throw new IllegalStateException("The pool reports " + usedBlocks + " used blocks out of " + totalBlocks);
		if (peakUsedBlocks < usedBlocks)
			throw new IllegalStateException("The peak occupancy (" + peakUsedBlocks + ") is below the current one ("
					+ usedBlocks + ")");
	}

	@Override
	public String toString() {
		return "KvCachePool[" + usedBlocks + "/" + totalBlocks + " blocks, " + tokensPerBlock + " tokens each, peak "
				+ peakUsedBlocks + "]";
	}
}
