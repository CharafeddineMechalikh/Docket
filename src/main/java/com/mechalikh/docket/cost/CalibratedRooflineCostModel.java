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
package com.mechalikh.docket.cost;

import java.util.LinkedHashSet;
import java.util.Set;

import com.mechalikh.docket.domain.Provenance;

/**
 * The same roofline arithmetic as {@link RooflineAnalyticalCostModel}, with its two device
 * coefficients measured on a real backend instead of chosen by hand.
 * <p>
 * The form is unchanged on purpose. What makes an estimate measured here is that the achieved
 * throughput and the effective memory bandwidth were fitted to a benchmark of this project, and
 * the source of that benchmark is carried with every artifact. The arithmetic is still the
 * roofline: its two coefficients are device-wide, so they transfer from the fitted model and
 * backend only as far as those stay comparable. The estimate warnings say so.
 * <p>
 * The calibration source is mandatory. A run that claims measured provenance without naming the
 * benchmark it was fitted to is refused at load time, so the label cannot be set by accident.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 1.1
 */
public class CalibratedRooflineCostModel extends RooflineAnalyticalCostModel {

	/** Identifier written to the artifacts. */
	public static final String NAME = "roofline-calibrated";

	/** The benchmark and hardware the coefficients were fitted to. */
	protected final String calibrationSource;

	/**
	 * Creates the model.
	 *
	 * @param calibrationSource the benchmark and hardware the coefficients come from, non-empty
	 * @throws IllegalArgumentException when the source is missing or blank
	 */
	public CalibratedRooflineCostModel(String calibrationSource) {
		if (calibrationSource == null || calibrationSource.trim().isEmpty())
			throw new IllegalArgumentException("The cost model \"" + NAME + "\" needs \"cost.calibration.source\" "
					+ "to name the benchmark and hardware its coefficients were fitted to");
		this.calibrationSource = calibrationSource.trim();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String name() {
		return NAME;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The coefficients are measured by this project, so the cost evidence is measured. The
	 * scenario-level label still takes the weakest of the model, the device and this model, so a
	 * calibrated cost model cannot lift a synthetic profile to measured.
	 */
	@Override
	public Provenance provenance() {
		return Provenance.MEASURED;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean isCalibrated() {
		return true;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String calibrationSource() {
		return calibrationSource;
	}

	/**
	 * Replaces the analytical warnings with the limits of a fitted device coefficient.
	 * <p>
	 * This method runs while the superclass is constructed, before this instance is initialised,
	 * so it must not read any field of this class.
	 *
	 * @return the warning set
	 */
	@Override
	protected Set<String> buildWarnings() {
		Set<String> set = new LinkedHashSet<>();
		set.add(NAME + " applies device coefficients measured by this project; the benchmark is named as "
				+ "the calibration source of every artifact");
		set.add("the arithmetic is still the analytical roofline, so coefficients fitted to one model, one "
				+ "backend and one batch range are applied to every model and to larger batches and steps; "
				+ "the measured label covers only the fitted range");
		set.add("the memory bandwidth is an effective figure: it absorbs kernel inefficiency the roofline "
				+ "does not model, so it is not a hardware datasheet value");
		set.add("weights are charged once per step, which assumes a fused kernel; a backend that re-reads "
				+ "them per layer would be slower");
		set.add("the cache is charged as a full read per step; a paged or sliding-window layout moves fewer bytes");
		return set;
	}
}
