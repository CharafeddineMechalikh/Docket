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

import com.mechalikh.genaism.domain.GpuProfile;
import com.mechalikh.genaism.domain.ModelProfile;

/**
 * Splits the memory of a device between the weights, the runtime reservation, the activation
 * slots and the key-value cache pool.
 * <p>
 * The split is decided once, when a replica is created, because it is a property of the
 * deployment rather than of the traffic: a model that does not fit cannot serve at all, and a
 * model that fits with no room for the cache can serve only one short request. Both cases
 * throw with the numbers that show why, instead of letting the replica degrade silently.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public class GpuMemoryManager {

	/** Device the budget is computed for. */
	private final GpuProfile device;
	/** Model whose weights occupy the memory. */
	private final ModelProfile model;
	/** Bytes the pool can hand out to cached tokens. */
	private final double kvPoolBytes;
	/** Bytes occupied by the weights. */
	private final double weightsBytes;
	/** Bytes kept back by the runtime on top of the unusable fraction of the device. */
	private final double runtimeOverheadBytes;
	/** Bytes kept back for the activations of the reserved sequence slots. */
	private final double activationReservationBytes;

	/**
	 * Computes the budget of a replica.
	 *
	 * @param device                the executing device
	 * @param model                 the served model
	 * @param runtimeOverheadBytes  bytes the runtime keeps back beyond the unusable fraction of
	 *                              the device, zero or more
	 * @param reservedSequenceSlots number of concurrent sequences the activations are reserved
	 *                              for, zero to reserve nothing
	 * @throws IllegalArgumentException when a profile is invalid or a reservation is negative
	 * @throws IllegalStateException    when the model does not fit in the memory of the device
	 */
	public GpuMemoryManager(GpuProfile device, ModelProfile model, double runtimeOverheadBytes,
			int reservedSequenceSlots) {
		if (device == null || model == null)
			throw new IllegalArgumentException("A memory budget needs both a device and a model");
		device.validate();
		model.validate();
		if (runtimeOverheadBytes < 0)
			throw new IllegalArgumentException("The runtime reservation cannot be negative, found "
					+ runtimeOverheadBytes);
		if (reservedSequenceSlots < 0)
			throw new IllegalArgumentException("The number of reserved sequence slots cannot be negative, found "
					+ reservedSequenceSlots);
		this.device = device;
		this.model = model;
		this.runtimeOverheadBytes = runtimeOverheadBytes;
		this.weightsBytes = model.weightBytes();
		this.activationReservationBytes = model.getActivationBytesPerSequence() * reservedSequenceSlots;
		this.kvPoolBytes = device.usableMemoryBytes() - weightsBytes - runtimeOverheadBytes - activationReservationBytes;

		if (kvPoolBytes <= 0)
			throw new IllegalStateException("Model \"" + model.getId() + "\" does not fit on device \"" + device.getId()
					+ "\": " + gb(weightsBytes) + " GB of weights, " + gb(runtimeOverheadBytes)
					+ " GB reserved for the runtime and " + gb(activationReservationBytes)
					+ " GB for the activations of " + reservedSequenceSlots + " slots leave "
					+ formatSignedGb(kvPoolBytes) + " GB for the key-value cache, out of "
					+ gb(device.usableMemoryBytes()) + " GB usable. Reduce the quantization, lower the reserved slots, "
					+ "or choose a device with more memory.");
		double tokensFit = Math.floor(kvPoolBytes / model.kvBytesPerToken());
		if (tokensFit < 1)
			throw new IllegalStateException("Device \"" + device.getId() + "\" keeps " + gb(kvPoolBytes)
					+ " GB for the cache of model \"" + model.getId() + "\", which is less than one token at "
					+ model.kvBytesPerToken() + " bytes per token");
	}

	/**
	 * Computes the budget with no activation reservation.
	 *
	 * @param device the executing device
	 * @param model  the served model
	 * @return the budget
	 */
	public static GpuMemoryManager of(GpuProfile device, ModelProfile model) {
		return new GpuMemoryManager(device, model, 0, 0);
	}

	/**
	 * Returns the bytes available for cached tokens.
	 *
	 * @return pool bytes
	 */
	public double kvPoolBytes() {
		return kvPoolBytes;
	}

	public double getWeightsBytes() {
		return weightsBytes;
	}

	public double getRuntimeOverheadBytes() {
		return runtimeOverheadBytes;
	}

	public double getActivationReservationBytes() {
		return activationReservationBytes;
	}

	/**
	 * Returns how many tokens the pool could hold if it were fully used.
	 *
	 * @return cached tokens, truncated
	 */
	public long maxCachedTokens() {
		return (long) Math.floor(kvPoolBytes / model.kvBytesPerToken());
	}

	/**
	 * Returns the fraction of the device memory the weights occupy.
	 *
	 * @return a ratio between zero and one
	 */
	public double weightsFractionOfDevice() {
		return weightsBytes / device.getMemoryBytes();
	}

	/**
	 * Returns a one-line description of the split, for the run summary.
	 *
	 * @return the description, in gigabytes
	 */
	public String describe() {
		return String.format("device %s: %.1f GB usable = %.1f GB weights + %.1f GB runtime + %.1f GB activations "
				+ "+ %.1f GB cache pool (%d tokens)", device.getId(), gb(device.usableMemoryBytes()),
				gb(weightsBytes), gb(runtimeOverheadBytes), gb(activationReservationBytes), gb(kvPoolBytes),
				maxCachedTokens());
	}

	/**
	 * Converts bytes to decimal gigabytes.
	 *
	 * @param bytes the size
	 * @return gigabytes
	 */
	protected static double gb(double bytes) {
		return bytes / 1e9;
	}

	/**
	 * Formats a size that may be negative, to show a shortfall.
	 *
	 * @param bytes the size
	 * @return gigabytes, rounded to two decimals
	 */
	protected static String formatSignedGb(double bytes) {
		return String.format("%.2f", gb(bytes));
	}

	public GpuProfile getDevice() {
		return device;
	}

	public ModelProfile getModel() {
		return model;
	}

	@Override
	public String toString() {
		return "GpuMemoryManager[" + describe() + "]";
	}
}
