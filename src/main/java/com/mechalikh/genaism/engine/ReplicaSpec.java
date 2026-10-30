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

import com.mechalikh.genaism.domain.GpuProfile;
import com.mechalikh.genaism.domain.ModelProfile;

/**
 * The deployment of one model on one device, with the limits its serving loop respects.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public class ReplicaSpec {

	/** Which sequence a full cache suspends first. */
	public enum VictimOrder {
		/** The sequence that started decoding last is suspended first, which protects the requests close to finishing. */
		NEWEST_FIRST("newest-first"),
		/** The lowest priority sequence is suspended first. */
		LOWEST_PRIORITY("lowest-priority"),
		/** The sequence holding the most cache is suspended first, which frees the most memory per victim. */
		LONGEST_CONTEXT("longest-context");

		/** Label written to the artifacts. */
		private final String label;

		VictimOrder(String label) {
			this.label = label;
		}

		/**
		 * Returns the configuration name.
		 *
		 * @return the label
		 */
		public String label() {
			return label;
		}

		/**
		 * Finds an order by its configuration name.
		 *
		 * @param value the configured name, case insensitive
		 * @return the order
		 * @throws IllegalArgumentException when no order matches
		 */
		public static VictimOrder from(String value) {
			for (VictimOrder order : values())
				if (order.label.equalsIgnoreCase(value.trim()))
					return order;
			StringBuilder known = new StringBuilder();
			for (VictimOrder order : values())
				known.append(known.length() == 0 ? "" : ", ").append(order.label);
			throw new IllegalArgumentException("Unknown preemption order \"" + value + "\"; known orders are " + known);
		}

		/**
		 * Returns the configuration name, so the order prints as it is written in a scenario file.
		 *
		 * @return the label
		 */
		@Override
		public String toString() {
			return label;
		}
	}

	/** Model this replica serves. */
	protected ModelProfile model;
	/** Device this replica runs on. */
	protected GpuProfile device;
	/** Largest number of sequences one decode iteration may contain. */
	protected int maxBatchSize = 64;
	/** Tokens one prefill step may read; zero means the whole prompt is read in one step. */
	protected int maxPrefillChunkTokens = 2048;
	/** Whether a full cache may suspend a running sequence. */
	protected boolean allowPreemption = true;
	/** Which sequence is suspended when the cache is full. */
	protected VictimOrder victimOrder = VictimOrder.NEWEST_FIRST;
	/** Whether a request past its end-to-end deadline is aborted instead of finished normally. */
	protected boolean abortOnDeadlineMiss = false;
	/** Tokens one cache block holds. */
	protected int tokensPerBlock = 16;
	/** Bytes the runtime keeps back beyond the unusable fraction of the device. */
	protected double runtimeOverheadBytes = 0;
	/** Number of sequences whose activations are reserved out of the cache budget. */
	protected int reservedActivationSlots = 0;
	/** Identifier of the service this replica belongs to, for the logs. */
	protected String serviceId = "service";

	/**
	 * Checks that the deployment can run.
	 *
	 * @throws IllegalArgumentException when a profile is missing or a limit is out of range
	 */
	public void validate() {
		if (model == null)
			throw new IllegalArgumentException("A replica needs the model it serves");
		if (device == null)
			throw new IllegalArgumentException("Replica of model \"" + model.getId() + "\" needs a device");
		model.validate();
		device.validate();
		if (maxBatchSize < 1)
			throw new IllegalArgumentException("Replica of model \"" + model.getId()
					+ "\": maxBatchSize must be at least one sequence");
		if (maxPrefillChunkTokens < 0)
			throw new IllegalArgumentException("Replica of model \"" + model.getId()
					+ "\": maxPrefillChunkTokens cannot be negative, use zero for an unchunked prefill");
		if (tokensPerBlock < 1)
			throw new IllegalArgumentException("Replica of model \"" + model.getId()
					+ "\": a cache block holds at least one token");
		if (runtimeOverheadBytes < 0)
			throw new IllegalArgumentException("Replica of model \"" + model.getId()
					+ "\": the runtime reservation cannot be negative");
		if (reservedActivationSlots < 0)
			throw new IllegalArgumentException("Replica of model \"" + model.getId()
					+ "\": the number of reserved activation slots cannot be negative");
		if (reservedActivationSlots > 0 && reservedActivationSlots < maxBatchSize)
			throw new IllegalArgumentException("Replica of model \"" + model.getId()
					+ "\": " + reservedActivationSlots + " activation slots are reserved but a batch may hold "
					+ maxBatchSize + " sequences; reserve at least as many slots as the batch limit, or zero for none");
	}

	/**
	 * Returns the largest context the deployment may cache on this replica.
	 * <p>
	 * The model caps it with its own context window, and the device caps it with the cache pool;
	 * a request longer than the smaller of the two cannot be served here at all.
	 *
	 * @param cachePoolTokens tokens the pool of the replica holds
	 * @return the usable context length, in tokens
	 */
	public int maxContextTokens(int cachePoolTokens) {
		return Math.min(model.getMaxContextTokens(), cachePoolTokens);
	}

	/**
	 * Returns the number of tokens a prefill step reads for a prompt of the given length.
	 *
	 * @param promptTokens tokens left to prefill, at least one
	 * @return chunk size, the whole prompt when chunking is off
	 */
	public int prefillChunkTokens(int promptTokens) {
		if (promptTokens < 1)
			throw new IllegalArgumentException("A prefill step reads at least one token, found " + promptTokens);
		return maxPrefillChunkTokens <= 0 ? promptTokens : Math.min(promptTokens, maxPrefillChunkTokens);
	}

	/**
	 * Returns the model this replica serves, whose weights and key-value cache are sized against
	 * the memory of the device.
	 *
	 * @return the served model, null until it is set
	 */
	public ModelProfile getModel() {
		return model;
	}

	/**
	 * Sets the model this replica serves.
	 *
	 * @param model the served model, required by {@link #validate()}
	 * @return this spec
	 */
	public ReplicaSpec setModel(ModelProfile model) {
		this.model = model;
		return this;
	}

	/**
	 * Returns the accelerator this replica runs on, which fixes its memory capacity, throughput
	 * and power draw.
	 *
	 * @return the executing device, null until it is set
	 */
	public GpuProfile getDevice() {
		return device;
	}

	/**
	 * Sets the accelerator this replica runs on.
	 *
	 * @param device the executing device, required by {@link #validate()}
	 * @return this spec
	 */
	public ReplicaSpec setDevice(GpuProfile device) {
		this.device = device;
		return this;
	}

	/**
	 * Returns the ceiling on the number of sequences one decode iteration may contain. The
	 * scheduler forms a batch up to this size, so the value trades per-token latency against
	 * throughput: a larger batch shares the launch overhead and the weight reads over more
	 * sequences, but each individual step takes longer.
	 *
	 * @return the batch ceiling, in sequences
	 */
	public int getMaxBatchSize() {
		return maxBatchSize;
	}

	/**
	 * Sets the ceiling on the number of sequences one decode iteration may contain.
	 *
	 * @param maxBatchSize the ceiling in sequences, at least one
	 * @return this spec
	 */
	public ReplicaSpec setMaxBatchSize(int maxBatchSize) {
		this.maxBatchSize = maxBatchSize;
		return this;
	}

	/**
	 * Returns the number of prompt tokens one prefill step may read. A long prompt is split into
	 * chunks of this size, which lets the scheduler interleave its prefill with the decode steps
	 * of other requests instead of holding the device for the whole prompt.
	 *
	 * @return tokens per prefill step, or zero when the whole prompt is read in one step
	 */
	public int getMaxPrefillChunkTokens() {
		return maxPrefillChunkTokens;
	}

	/**
	 * Sets the number of prompt tokens one prefill step may read.
	 *
	 * @param maxPrefillChunkTokens tokens per step, zero for an unchunked prefill
	 * @return this spec
	 */
	public ReplicaSpec setMaxPrefillChunkTokens(int maxPrefillChunkTokens) {
		this.maxPrefillChunkTokens = maxPrefillChunkTokens;
		return this;
	}

	/**
	 * Tells whether a full cache may suspend a running sequence to admit a waiting one. With
	 * preemption off, a request that cannot fit waits for cache to be freed by completions
	 * instead, which avoids recomputing suspended context but can starve long requests.
	 *
	 * @return {@code true} when a full cache may suspend a running sequence
	 */
	public boolean isAllowPreemption() {
		return allowPreemption;
	}

	/**
	 * Sets whether a full cache may suspend a running sequence.
	 *
	 * @param allowPreemption {@code true} to let a full cache suspend a running sequence
	 * @return this spec
	 */
	public ReplicaSpec setAllowPreemption(boolean allowPreemption) {
		this.allowPreemption = allowPreemption;
		return this;
	}

	/**
	 * Returns the rule that chooses which sequence a full cache suspends first, which decides
	 * whether the memory pressure falls on the newest request, the least important one or the
	 * one holding the most context.
	 *
	 * @return the victim rule, ignored when preemption is off
	 */
	public VictimOrder getVictimOrder() {
		return victimOrder;
	}

	/**
	 * Sets the rule that chooses which sequence a full cache suspends first.
	 *
	 * @param victimOrder the victim rule, applied only when preemption is allowed
	 * @return this spec
	 */
	public ReplicaSpec setVictimOrder(VictimOrder victimOrder) {
		this.victimOrder = victimOrder;
		return this;
	}

	/**
	 * Tells whether a request that passes its end-to-end deadline is aborted rather than finished
	 * normally. Aborting keeps late work out of the reported latency and frees its cache earlier,
	 * at the cost of a lower goodput.
	 *
	 * @return {@code true} when requests past their deadline are aborted
	 */
	public boolean isAbortOnDeadlineMiss() {
		return abortOnDeadlineMiss;
	}

	/**
	 * Sets whether a request that passes its end-to-end deadline is aborted.
	 *
	 * @param abortOnDeadlineMiss {@code true} to abort requests past their deadline
	 * @return this spec
	 */
	public ReplicaSpec setAbortOnDeadlineMiss(boolean abortOnDeadlineMiss) {
		this.abortOnDeadlineMiss = abortOnDeadlineMiss;
		return this;
	}

	/**
	 * Returns the number of tokens one cache block holds. The key-value cache is handed out and
	 * shared between requests in whole blocks, so a smaller block wastes less memory on a
	 * partially filled sequence, while a larger block keeps the bookkeeping shorter.
	 *
	 * @return tokens per cache block
	 */
	public int getTokensPerBlock() {
		return tokensPerBlock;
	}

	/**
	 * Sets the number of tokens one cache block holds.
	 *
	 * @param tokensPerBlock tokens per block, at least one
	 * @return this spec
	 */
	public ReplicaSpec setTokensPerBlock(int tokensPerBlock) {
		this.tokensPerBlock = tokensPerBlock;
		return this;
	}

	/**
	 * Returns the memory this replica keeps back on top of the unusable fraction of the device,
	 * in bytes, for buffers the runtime allocates outside the weights and the key-value cache.
	 * <p>
	 * The configuration expresses this figure in GiB; the reservation is taken from the usable
	 * memory before the cache pool is sized.
	 *
	 * @return bytes reserved for the runtime, zero when none are held back
	 */
	public double getRuntimeOverheadBytes() {
		return runtimeOverheadBytes;
	}

	/**
	 * Sets the memory this replica keeps back on top of the unusable fraction of the device.
	 *
	 * @param runtimeOverheadBytes bytes reserved for the runtime, zero or more (GiB times
	 *                             {@code 2^30})
	 * @return this spec
	 */
	public ReplicaSpec setRuntimeOverheadBytes(double runtimeOverheadBytes) {
		this.runtimeOverheadBytes = runtimeOverheadBytes;
		return this;
	}

	/**
	 * Returns the number of sequence slots whose activations are kept out of the key-value cache
	 * budget, so a full batch always finds the working memory it needs without competing with
	 * cached tokens.
	 *
	 * @return the reserved slots, or zero when activations are not reserved
	 */
	public int getReservedActivationSlots() {
		return reservedActivationSlots;
	}

	/**
	 * Sets the number of sequence slots whose activations are kept out of the cache budget.
	 *
	 * @param reservedActivationSlots the reserved slots, zero for none or at least the batch
	 *                                ceiling so a full batch always fits
	 * @return this spec
	 */
	public ReplicaSpec setReservedActivationSlots(int reservedActivationSlots) {
		this.reservedActivationSlots = reservedActivationSlots;
		return this;
	}

	/**
	 * Returns the identifier of the service this replica belongs to, used to group replicas in
	 * the logs and the run summary.
	 *
	 * @return the service identifier, {@code service} when none was set
	 */
	public String getServiceId() {
		return serviceId;
	}

	/**
	 * Sets the service identifier this replica is reported under, falling back to {@code service}
	 * when the value is null or empty.
	 *
	 * @param serviceId the service identifier
	 * @return this spec
	 */
	public ReplicaSpec setServiceId(String serviceId) {
		this.serviceId = serviceId == null || serviceId.isEmpty() ? "service" : serviceId;
		return this;
	}

	/**
	 * Returns a one-line summary with the service, the model and device identifiers, and the
	 * batch, chunk and preemption limits.
	 *
	 * @return the summary line
	 */
	@Override
	public String toString() {
		return "ReplicaSpec[" + serviceId + ", model=" + model.getId() + ", device=" + device.getId() + ", batch "
				+ maxBatchSize + ", chunk " + maxPrefillChunkTokens + ", preemption " + victimOrder + "]";
	}
}
