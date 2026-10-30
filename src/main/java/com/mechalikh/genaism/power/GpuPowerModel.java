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
package com.mechalikh.genaism.power;

import com.mechalikh.genaism.domain.GpuProfile;
import com.mechalikh.genaism.domain.Provenance;

/**
 * Integrates the power declared by a device over the time the replica holds it.
 * <p>
 * The device is modelled with two constant power levels, idle and active, so the energy is the
 * exact integral of a piecewise-constant function: each interval the server reports is charged
 * as {@code power * seconds}. No third level is invented for a batch that is only partly full,
 * because that number cannot be known without a measurement of the backend, and a made-up
 * intermediate power would look like a result.
 * <p>
 * Per-request energy comes from {@link #shareOf(double, double, double)}, which splits the
 * energy of an interval across the sequences that occupied the device during it. The rule is
 * documented in the artifacts, because two attributions of the same interval can differ by a
 * large factor and a reader has to know which one produced a figure.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public class GpuPowerModel {

	/** Device the figures belong to, for error messages. */
	protected final String deviceId;
	/** Power drawn while holding no work, in watts. */
	protected final double idleWatts;
	/** Power drawn while executing, in watts. */
	protected final double activeWatts;
	/** Where the two power figures come from. */
	protected final Provenance provenance;
	/** Time the device was executing, in seconds. */
	protected double activeSeconds;
	/** Time the device was idle, in seconds. */
	protected double idleSeconds;
	/** Energy charged for the active intervals, in joules. */
	protected double activeEnergyJoule;
	/** Energy charged for the idle intervals, in joules. */
	protected double idleEnergyJoule;

	/**
	 * Reads the power figures of a device.
	 *
	 * @param device the executing device
	 */
	public GpuPowerModel(GpuProfile device) {
		if (device == null)
			throw new IllegalArgumentException("A power model needs the device it describes");
		device.validate();
		this.deviceId = device.getId();
		this.idleWatts = device.getIdlePowerWatts();
		this.activeWatts = device.getActivePowerWatts();
		this.provenance = device.getProvenance();
	}

	/**
	 * Charges an interval during which the device executed work.
	 *
	 * @param seconds interval length, zero or more
	 * @return joules added
	 * @throws IllegalArgumentException when the interval is negative or not finite
	 */
	public double recordActive(double seconds) {
		checkInterval(seconds, deviceId);
		activeSeconds += seconds;
		double joule = activeWatts * seconds;
		activeEnergyJoule += joule;
		return joule;
	}

	/**
	 * Charges an interval during which the device held no work.
	 *
	 * @param seconds interval length, zero or more
	 * @return joules added
	 * @throws IllegalArgumentException when the interval is negative or not finite
	 */
	public double recordIdle(double seconds) {
		checkInterval(seconds, deviceId);
		idleSeconds += seconds;
		double joule = idleWatts * seconds;
		idleEnergyJoule += joule;
		return joule;
	}

	/**
	 * Returns the energy of all the intervals charged so far.
	 *
	 * @return joules
	 */
	public double totalJoule() {
		return activeEnergyJoule + idleEnergyJoule;
	}

	/**
	 * Returns the energy of the active intervals.
	 *
	 * @return joules
	 */
	public double activeEnergyJoule() {
		return activeEnergyJoule;
	}

	/**
	 * Returns the energy of the idle intervals.
	 *
	 * @return joules
	 */
	public double idleEnergyJoule() {
		return idleEnergyJoule;
	}

	/**
	 * Returns the energy per second the device draws while executing.
	 *
	 * @return active power, in watts
	 */
	public double activePowerWatts() {
		return activeWatts;
	}

	/**
	 * Returns the energy per second the device draws while idle.
	 *
	 * @return idle power, in watts
	 */
	public double idlePowerWatts() {
		return idleWatts;
	}

	/**
	 * Returns the mean power over a duration.
	 *
	 * @param durationSeconds the wall-clock simulation duration, positive
	 * @return watts, or zero when the duration is not positive
	 */
	public double averagePowerWatts(double durationSeconds) {
		return durationSeconds <= 0 ? 0 : totalJoule() / durationSeconds;
	}

	/**
	 * Returns the fraction of a duration during which the device executed work.
	 *
	 * @param durationSeconds the wall-clock simulation duration, positive
	 * @return a ratio between zero and one, clamped
	 */
	public double utilisation(double durationSeconds) {
		if (durationSeconds <= 0)
			return 0;
		return Math.min(1, activeSeconds / durationSeconds);
	}

	/**
	 * Returns the seconds spent executing.
	 *
	 * @return active seconds
	 */
	public double getActiveSeconds() {
		return activeSeconds;
	}

	/**
	 * Returns the seconds spent idle.
	 *
	 * @return idle seconds
	 */
	public double getIdleSeconds() {
		return idleSeconds;
	}

	/**
	 * Returns the origin of the power figures.
	 *
	 * @return the provenance
	 */
	public Provenance provenance() {
		return provenance;
	}

	/**
	 * Splits the energy of an interval across the sequences that occupied the device during it.
	 *
	 * @param intervalJoule the energy of the interval, in joules
	 * @param weight        the share of the requesting sequence, in the unit the caller uses
	 * @param totalWeight   the sum of the shares of every sequence in the interval, positive
	 * @return the joules attributed to the sequence, zero when the total weight is not positive
	 */
	public static double shareOf(double intervalJoule, double weight, double totalWeight) {
		if (totalWeight <= 0)
			return 0;
		return intervalJoule * weight / totalWeight;
	}

	/**
	 * Converts joules to watt-hours, the unit PureEdgeSim reports energy in.
	 *
	 * @param joule the energy
	 * @return watt-hours
	 */
	public static double wattHours(double joule) {
		return joule / 3600.0;
	}

	/**
	 * Rejects an interval that cannot be integrated.
	 *
	 * @param seconds  the interval
	 * @param deviceId the device it belongs to
	 */
	protected static void checkInterval(double seconds, String deviceId) {
		if (seconds < 0)
			throw new IllegalArgumentException("Device \"" + deviceId + "\" cannot integrate a negative interval of "
					+ seconds + " s");
		if (Double.isNaN(seconds) || Double.isInfinite(seconds))
			throw new IllegalArgumentException("Device \"" + deviceId + "\" cannot integrate an interval of " + seconds
					+ " s");
	}

	/**
	 * Returns a one-line summary of the device power levels and of the energy integrated so far,
	 * with the energy expressed in joules.
	 *
	 * @return the device, both power levels and the total energy in joules
	 */
	@Override
	public String toString() {
		return "GpuPowerModel[" + deviceId + ", " + activeWatts + " W active / " + idleWatts + " W idle, "
				+ totalJoule() + " J]";
	}
}
