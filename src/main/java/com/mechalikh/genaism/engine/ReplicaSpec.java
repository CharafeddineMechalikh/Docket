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

	public ModelProfile getModel() {
		return model;
	}

	public ReplicaSpec setModel(ModelProfile model) {
		this.model = model;
		return this;
	}

	public GpuProfile getDevice() {
		return device;
	}

	public ReplicaSpec setDevice(GpuProfile device) {
		this.device = device;
		return this;
	}

	public int getMaxBatchSize() {
		return maxBatchSize;
	}

	public ReplicaSpec setMaxBatchSize(int maxBatchSize) {
		this.maxBatchSize = maxBatchSize;
		return this;
	}

	public int getMaxPrefillChunkTokens() {
		return maxPrefillChunkTokens;
	}

	public ReplicaSpec setMaxPrefillChunkTokens(int maxPrefillChunkTokens) {
		this.maxPrefillChunkTokens = maxPrefillChunkTokens;
		return this;
	}

	public boolean isAllowPreemption() {
		return allowPreemption;
	}

	public ReplicaSpec setAllowPreemption(boolean allowPreemption) {
		this.allowPreemption = allowPreemption;
		return this;
	}

	public VictimOrder getVictimOrder() {
		return victimOrder;
	}

	public ReplicaSpec setVictimOrder(VictimOrder victimOrder) {
		this.victimOrder = victimOrder;
		return this;
	}

	public boolean isAbortOnDeadlineMiss() {
		return abortOnDeadlineMiss;
	}

	public ReplicaSpec setAbortOnDeadlineMiss(boolean abortOnDeadlineMiss) {
		this.abortOnDeadlineMiss = abortOnDeadlineMiss;
		return this;
	}

	public int getTokensPerBlock() {
		return tokensPerBlock;
	}

	public ReplicaSpec setTokensPerBlock(int tokensPerBlock) {
		this.tokensPerBlock = tokensPerBlock;
		return this;
	}

	public double getRuntimeOverheadBytes() {
		return runtimeOverheadBytes;
	}

	public ReplicaSpec setRuntimeOverheadBytes(double runtimeOverheadBytes) {
		this.runtimeOverheadBytes = runtimeOverheadBytes;
		return this;
	}

	public int getReservedActivationSlots() {
		return reservedActivationSlots;
	}

	public ReplicaSpec setReservedActivationSlots(int reservedActivationSlots) {
		this.reservedActivationSlots = reservedActivationSlots;
		return this;
	}

	public String getServiceId() {
		return serviceId;
	}

	public ReplicaSpec setServiceId(String serviceId) {
		this.serviceId = serviceId == null || serviceId.isEmpty() ? "service" : serviceId;
		return this;
	}

	@Override
	public String toString() {
		return "ReplicaSpec[" + serviceId + ", model=" + model.getId() + ", device=" + device.getId() + ", batch "
				+ maxBatchSize + ", chunk " + maxPrefillChunkTokens + ", preemption " + victimOrder + "]";
	}
}
