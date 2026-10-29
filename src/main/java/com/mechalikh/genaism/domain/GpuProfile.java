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
package com.mechalikh.genaism.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The resources of one accelerator, as the simulator sees them.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
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
	/** Bandwidth of the host to device copy of a request, in bytes per second, {@code 0} to ignore it. */
	protected double hostToDeviceBandwidthBytesPerSecond = 0;
	/** Latency added by the host to device copy, in seconds. */
	protected double hostTransferLatencySeconds = 0;
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
		if (kernelLaunchOverheadSeconds < 0 || hostTransferLatencySeconds < 0)
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
		copy.hostToDeviceBandwidthBytesPerSecond = hostToDeviceBandwidthBytesPerSecond;
		copy.hostTransferLatencySeconds = hostTransferLatencySeconds;
		copy.provenance = provenance;
		copy.note = note;
		return copy;
	}

	public String getId() {
		return id;
	}

	public GpuProfile setId(String id) {
		this.id = id;
		return this;
	}

	public String getDeviceType() {
		return deviceType;
	}

	public GpuProfile setDeviceType(String deviceType) {
		this.deviceType = deviceType;
		return this;
	}

	public double getMemoryBytes() {
		return memoryBytes;
	}

	public GpuProfile setMemoryBytes(double memoryBytes) {
		this.memoryBytes = memoryBytes;
		return this;
	}

	public double getUnusableMemoryFraction() {
		return unusableMemoryFraction;
	}

	public GpuProfile setUnusableMemoryFraction(double unusableMemoryFraction) {
		this.unusableMemoryFraction = unusableMemoryFraction;
		return this;
	}

	public double getMemoryBandwidthBytesPerSecond() {
		return memoryBandwidthBytesPerSecond;
	}

	public GpuProfile setMemoryBandwidthBytesPerSecond(double memoryBandwidthBytesPerSecond) {
		this.memoryBandwidthBytesPerSecond = memoryBandwidthBytesPerSecond;
		return this;
	}

	public Map<String, Double> getAchievedTeraFlopsByPrecision() {
		return Collections.unmodifiableMap(achievedTeraFlopsByPrecision);
	}

	public Map<String, Double> getPeakTeraFlopsByPrecision() {
		return Collections.unmodifiableMap(peakTeraFlopsByPrecision);
	}

	public double getIdlePowerWatts() {
		return idlePowerWatts;
	}

	public GpuProfile setIdlePowerWatts(double idlePowerWatts) {
		this.idlePowerWatts = idlePowerWatts;
		return this;
	}

	public double getActivePowerWatts() {
		return activePowerWatts;
	}

	public GpuProfile setActivePowerWatts(double activePowerWatts) {
		this.activePowerWatts = activePowerWatts;
		return this;
	}

	public double getKernelLaunchOverheadSeconds() {
		return kernelLaunchOverheadSeconds;
	}

	public GpuProfile setKernelLaunchOverheadSeconds(double kernelLaunchOverheadSeconds) {
		this.kernelLaunchOverheadSeconds = kernelLaunchOverheadSeconds;
		return this;
	}

	public double getHostToDeviceBandwidthBytesPerSecond() {
		return hostToDeviceBandwidthBytesPerSecond;
	}

	public GpuProfile setHostToDeviceBandwidthBytesPerSecond(double hostToDeviceBandwidthBytesPerSecond) {
		this.hostToDeviceBandwidthBytesPerSecond = hostToDeviceBandwidthBytesPerSecond;
		return this;
	}

	public double getHostTransferLatencySeconds() {
		return hostTransferLatencySeconds;
	}

	public GpuProfile setHostTransferLatencySeconds(double hostTransferLatencySeconds) {
		this.hostTransferLatencySeconds = hostTransferLatencySeconds;
		return this;
	}

	public Provenance getProvenance() {
		return provenance;
	}

	public GpuProfile setProvenance(Provenance provenance) {
		this.provenance = provenance;
		return this;
	}

	public String getNote() {
		return note;
	}

	public GpuProfile setNote(String note) {
		this.note = note;
		return this;
	}

	@Override
	public String toString() {
		return "GpuProfile[" + id + ", " + (memoryBytes / 1e9) + " GB, " + declaredPrecisions() + "]";
	}
}
