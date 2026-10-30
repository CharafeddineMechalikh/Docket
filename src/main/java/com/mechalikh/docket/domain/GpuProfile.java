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
package com.mechalikh.docket.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The resources of one accelerator, as the simulator sees them.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 0.1
 */
public class GpuProfile {

	/** Identifier used by the cluster configuration. */
	protected String id = "";
	/** Free-text device family, for instance {@code datacenter-accelerator}. */
	protected String deviceType = "accelerator";
	/** Total high-bandwidth memory, in bytes. */
	protected double memoryBytes;
	/** Fraction of the memory the runtime and the allocator keep for themselves. */
	protected double unusableMemoryFraction = 0.05;
	/** Memory bandwidth used by the roofline estimate, in bytes per second. */
	protected double memoryBandwidthBytesPerSecond;
	/**
	 * Achieved throughput per precision, in Tera floating-point operations per second. These
	 * are the values the execution model divides by, so they must be achievable, not peak.
	 */
	protected Map<String, Double> achievedTeraFlopsByPrecision = new LinkedHashMap<>();
	/** Published peak throughput per precision, in Tera flops per second, for reporting only. */
	protected Map<String, Double> peakTeraFlopsByPrecision = new LinkedHashMap<>();
	/** Power drawn while the device holds no work, in watts. */
	protected double idlePowerWatts;
	/** Power drawn while the device executes, in watts. */
	protected double activePowerWatts;
	/** Time the runtime spends launching the kernels of one decode iteration, in seconds. */
	protected double kernelLaunchOverheadSeconds = 0;
	/** Where the numbers of this profile come from. */
	protected Provenance provenance = Provenance.SYNTHETIC;
	/** Free-text note shown in the artifacts, used to record the assumptions behind the values. */
	protected String note = "";

	/**
	 * Registers an achieved throughput for one precision.
	 *
	 * @param precision the weight precision, case insensitive
	 * @param teraFlops achievable Tera flops per second, positive
	 * @return this profile
	 */
	public GpuProfile addAchievedTeraFlops(String precision, double teraFlops) {
		if (precision == null || precision.trim().isEmpty())
			throw new IllegalArgumentException("A throughput needs the precision it applies to");
		if (teraFlops <= 0)
			throw new IllegalArgumentException("The achieved throughput of precision \"" + precision
					+ "\" must be positive, found " + teraFlops);
		achievedTeraFlopsByPrecision.put(precision.trim().toLowerCase(Locale.ROOT), teraFlops);
		return this;
	}

	/**
	 * Registers a published peak throughput for one precision. The execution model never reads
	 * these values; they are reported so a reader can see the gap.
	 *
	 * @param precision the weight precision, case insensitive
	 * @param teraFlops peak Tera flops per second, positive
	 * @return this profile
	 */
	public GpuProfile addPeakTeraFlops(String precision, double teraFlops) {
		if (teraFlops <= 0)
			throw new IllegalArgumentException("The peak throughput of precision \"" + precision
					+ "\" must be positive, found " + teraFlops);
		peakTeraFlopsByPrecision.put(precision.trim().toLowerCase(Locale.ROOT), teraFlops);
		return this;
	}

	/**
	 * Returns the floating-point capacity the execution model uses.
	 * <p>
	 * A precision that was not declared is an error rather than a guess: transferring a
	 * throughput from another precision, or falling back to the peak value, would silently
	 * change the prediction.
	 *
	 * @param precision the weight precision of the model
	 * @return floating-point operations per second
	 * @throws IllegalArgumentException when the precision has no declared throughput
	 */
	public double flopsPerSecond(String precision) {
		String key = precision == null ? "" : precision.trim().toLowerCase(Locale.ROOT);
		Double teraFlops = achievedTeraFlopsByPrecision.get(key);
		if (teraFlops == null)
			throw new IllegalArgumentException("Device \"" + id + "\" declares no achieved throughput for precision \""
					+ key + "\"; declared precisions are " + declaredPrecisions()
					+ ". Peak values are not used for execution time.");
		return teraFlops * 1e12;
	}

	/**
	 * Returns the precisions this device declares an achievable throughput for.
	 *
	 * @return the sorted precision names
	 */
	public TreeSet<String> declaredPrecisions() {
		return new TreeSet<>(achievedTeraFlopsByPrecision.keySet());
	}

	/**
	 * Returns the memory the simulator may allocate workloads from.
	 *
	 * @return usable bytes, after the runtime reservation
	 */
	public double usableMemoryBytes() {
		return memoryBytes * (1 - unusableMemoryFraction);
	}

	/**
	 * Checks that the device can be scored by an execution model.
	 *
	 * @throws IllegalArgumentException when a required characteristic is missing
	 */
	public void validate() {
		if (id == null || id.isEmpty())
			throw new IllegalArgumentException("A device needs an id");
		if (memoryBytes <= 0)
			throw new IllegalArgumentException("Device \"" + id + "\": memoryBytes must be positive");
		if (unusableMemoryFraction < 0 || unusableMemoryFraction >= 1)
			throw new IllegalArgumentException("Device \"" + id + "\": unusableMemoryFraction must be in [0, 1)");
		if (memoryBandwidthBytesPerSecond <= 0)
			throw new IllegalArgumentException("Device \"" + id + "\": memoryBandwidthBytesPerSecond must be positive");
		if (achievedTeraFlopsByPrecision.isEmpty())
			throw new IllegalArgumentException("Device \"" + id
					+ "\": at least one achieved throughput must be declared");
		if (idlePowerWatts < 0 || activePowerWatts < 0)
			throw new IllegalArgumentException("Device \"" + id + "\": power figures cannot be negative");
		if (activePowerWatts < idlePowerWatts)
			throw new IllegalArgumentException("Device \"" + id + "\": the active power (" + activePowerWatts
					+ " W) is below the idle power (" + idlePowerWatts + " W)");
		if (kernelLaunchOverheadSeconds < 0)
			throw new IllegalArgumentException("Device \"" + id + "\": overheads cannot be negative");
	}

	/**
	 * Returns the achieved throughput declared for one precision, in Tera flops per second.
	 * <p>
	 * Reporting reads this value; the execution time uses {@link #flopsPerSecond(String)}, which
	 * converts it to operations per second.
	 *
	 * @param precision the weight precision, case insensitive
	 * @return Tera flops per second, or {@code Double.NaN} when the precision was not declared
	 */
	public double getAchievedTeraFlops(String precision) {
		Double teraFlops = achievedTeraFlopsByPrecision
				.get(precision == null ? "" : precision.trim().toLowerCase(Locale.ROOT));
		return teraFlops == null ? Double.NaN : teraFlops;
	}

	/**
	 * Returns a copy of the profile, so that several replicas of the same device model can carry
	 * distinct identifiers.
	 * <p>
	 * A deployment of four identical accelerators holds four devices: the service refuses two
	 * replicas on one identifier, because each would treat the whole memory as free.
	 *
	 * @return an independent copy with the same figures
	 */
	public GpuProfile copy() {
		GpuProfile copy = new GpuProfile();
		copy.id = id;
		copy.deviceType = deviceType;
		copy.memoryBytes = memoryBytes;
		copy.unusableMemoryFraction = unusableMemoryFraction;
		copy.memoryBandwidthBytesPerSecond = memoryBandwidthBytesPerSecond;
		copy.achievedTeraFlopsByPrecision = new LinkedHashMap<>(achievedTeraFlopsByPrecision);
		copy.peakTeraFlopsByPrecision = new LinkedHashMap<>(peakTeraFlopsByPrecision);
		copy.idlePowerWatts = idlePowerWatts;
		copy.activePowerWatts = activePowerWatts;
		copy.kernelLaunchOverheadSeconds = kernelLaunchOverheadSeconds;
		copy.provenance = provenance;
		copy.note = note;
		return copy;
	}

	/**
	 * Returns the name the cluster configuration uses to identify this device.
	 * <p>
	 * The identifier must stay unique across the deployment, because two replicas on one
	 * identifier would each treat the whole device memory as free.
	 *
	 * @return the identifier, empty when the profile was not loaded from a configuration
	 */
	public String getId() {
		return id;
	}

	/**
	 * Sets the name this device is known by, which must stay unique across the deployment.
	 *
	 * @param id the identifier, non-empty for {@link #validate()} to pass
	 * @return this profile
	 */
	public GpuProfile setId(String id) {
		this.id = id;
		return this;
	}

	/**
	 * Returns the free-text device family, which only labels the device in the artifacts and
	 * never enters a timing or memory computation.
	 *
	 * @return the family label, for instance {@code datacenter-accelerator}
	 */
	public String getDeviceType() {
		return deviceType;
	}

	/**
	 * Sets the free-text device family shown in the artifacts.
	 *
	 * @param deviceType the family label, for instance {@code datacenter-accelerator}
	 * @return this profile
	 */
	public GpuProfile setDeviceType(String deviceType) {
		this.deviceType = deviceType;
		return this;
	}

	/**
	 * Returns the total high-bandwidth memory of the device, in bytes.
	 * <p>
	 * This is the raw capacity as the vendor states it (the configuration expresses it in GiB);
	 * {@link #usableMemoryBytes()} applies the reservation of the runtime afterwards.
	 *
	 * @return total capacity in bytes, positive
	 */
	public double getMemoryBytes() {
		return memoryBytes;
	}

	/**
	 * Sets the total high-bandwidth memory of the device, before any reservation is taken.
	 *
	 * @param memoryBytes capacity in bytes, positive
	 * @return this profile
	 */
	public GpuProfile setMemoryBytes(double memoryBytes) {
		this.memoryBytes = memoryBytes;
		return this;
	}

	/**
	 * Returns the share of the device memory that the runtime and the allocator keep for
	 * themselves, so the simulator never sizes a workload against the full capacity.
	 * <p>
	 * The default of {@code 0.05} leaves ninety-five percent of the memory available to the
	 * weights, the activations and the key-value cache.
	 *
	 * @return the reserved fraction, in {@code [0, 1)}
	 */
	public double getUnusableMemoryFraction() {
		return unusableMemoryFraction;
	}

	/**
	 * Sets the share of the device memory that the runtime and the allocator keep for themselves.
	 *
	 * @param unusableMemoryFraction the reserved fraction, in {@code [0, 1)}
	 * @return this profile
	 */
	public GpuProfile setUnusableMemoryFraction(double unusableMemoryFraction) {
		this.unusableMemoryFraction = unusableMemoryFraction;
		return this;
	}

	/**
	 * Returns the memory bandwidth the roofline estimate divides by, in bytes per second.
	 * <p>
	 * The configuration expresses this figure in decimal GB/s, which the loader converts to bytes.
	 * Decode steps that cannot reach their arithmetic intensity are limited by this value.
	 *
	 * @return bandwidth in bytes per second, positive
	 */
	public double getMemoryBandwidthBytesPerSecond() {
		return memoryBandwidthBytesPerSecond;
	}

	/**
	 * Sets the memory bandwidth used by the roofline estimate.
	 *
	 * @param memoryBandwidthBytesPerSecond bandwidth in bytes per second, positive (GB/s times
	 *                                      {@code 1e9})
	 * @return this profile
	 */
	public GpuProfile setMemoryBandwidthBytesPerSecond(double memoryBandwidthBytesPerSecond) {
		this.memoryBandwidthBytesPerSecond = memoryBandwidthBytesPerSecond;
		return this;
	}

	/**
	 * Returns the throughput the execution model divides by, per precision, in Tera floating-point
	 * operations per second.
	 * <p>
	 * These are achievable figures, not the published peaks: using a peak here would understate
	 * every service time. The map is keyed by lower-case precision name, such as {@code fp16}.
	 *
	 * @return an unmodifiable map from precision to achievable TFLOPS
	 */
	public Map<String, Double> getAchievedTeraFlopsByPrecision() {
		return Collections.unmodifiableMap(achievedTeraFlopsByPrecision);
	}

	/**
	 * Returns the published peak throughput per precision, in Tera floating-point operations per
	 * second, reported next to the achieved figures so a reader can see the gap.
	 * <p>
	 * The execution model never reads these values, and the map is empty unless peaks were
	 * declared.
	 *
	 * @return an unmodifiable map from precision to peak TFLOPS
	 */
	public Map<String, Double> getPeakTeraFlopsByPrecision() {
		return Collections.unmodifiableMap(peakTeraFlopsByPrecision);
	}

	/**
	 * Returns the power the device draws while it holds no work, in watts.
	 *
	 * @return idle power in watts, zero when power is not modelled
	 */
	public double getIdlePowerWatts() {
		return idlePowerWatts;
	}

	/**
	 * Sets the power the device draws while it holds no work.
	 *
	 * @param idlePowerWatts idle power in watts, zero or more and not above the active power
	 * @return this profile
	 */
	public GpuProfile setIdlePowerWatts(double idlePowerWatts) {
		this.idlePowerWatts = idlePowerWatts;
		return this;
	}

	/**
	 * Returns the power the device draws while it executes a batch, in watts.
	 *
	 * @return active power in watts, zero when power is not modelled
	 */
	public double getActivePowerWatts() {
		return activePowerWatts;
	}

	/**
	 * Sets the power the device draws while it executes a batch.
	 *
	 * @param activePowerWatts active power in watts, zero or more and not below the idle power
	 * @return this profile
	 */
	public GpuProfile setActivePowerWatts(double activePowerWatts) {
		this.activePowerWatts = activePowerWatts;
		return this;
	}

	/**
	 * Returns the time the runtime spends launching the kernels of one decode iteration, in
	 * seconds, which is added to every step regardless of the batch size.
	 * <p>
	 * The configuration expresses this figure in milliseconds. The overhead is what makes a batch
	 * of a few sequences pay for the whole device.
	 *
	 * @return launch overhead in seconds, zero when it is not modelled
	 */
	public double getKernelLaunchOverheadSeconds() {
		return kernelLaunchOverheadSeconds;
	}

	/**
	 * Sets the time the runtime spends launching the kernels of one decode iteration.
	 *
	 * @param kernelLaunchOverheadSeconds overhead in seconds, zero or more (milliseconds divided
	 *                                    by {@code 1e3})
	 * @return this profile
	 */
	public GpuProfile setKernelLaunchOverheadSeconds(double kernelLaunchOverheadSeconds) {
		this.kernelLaunchOverheadSeconds = kernelLaunchOverheadSeconds;
		return this;
	}

	/**
	 * Returns where the numbers of this profile come from, so a report can separate measured
	 * figures from assumed ones.
	 *
	 * @return the provenance tag, synthetic unless the configuration says otherwise
	 */
	public Provenance getProvenance() {
		return provenance;
	}

	/**
	 * Sets where the numbers of this profile come from.
	 *
	 * @param provenance the provenance tag
	 * @return this profile
	 */
	public GpuProfile setProvenance(Provenance provenance) {
		this.provenance = provenance;
		return this;
	}

	/**
	 * Returns the free-text note that records the assumptions behind the values, which is carried
	 * into the artifacts unchanged.
	 *
	 * @return the note, empty when none was written
	 */
	public String getNote() {
		return note;
	}

	/**
	 * Sets the free-text note that records the assumptions behind the values.
	 *
	 * @param note the note, empty when there is nothing to explain
	 * @return this profile
	 */
	public GpuProfile setNote(String note) {
		this.note = note;
		return this;
	}

	/**
	 * Returns a one-line summary with the identifier, the capacity in decimal GB and the
	 * precisions the device declares.
	 *
	 * @return the summary line
	 */
	@Override
	public String toString() {
		return "GpuProfile[" + id + ", " + (memoryBytes / 1e9) + " GB, " + declaredPrecisions() + "]";
	}
}
