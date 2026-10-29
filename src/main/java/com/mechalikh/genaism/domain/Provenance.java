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

/**
 * Where the numbers of a profile come from.
 * <p>
 * Every reported result is labelled with the weakest provenance among the profiles it used,
 * so that a run built on synthetic dimensions can never be read as a measured prediction.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public enum Provenance {
	/** Hand-written values that illustrate the simulator; they describe no real device. */
	SYNTHETIC("synthetic"),
	/** Values read from a published specification, not verified by this project. */
	LITERATURE("literature"),
	/** Values measured by this project on real hardware, with the benchmark recorded. */
	MEASURED("measured");

	/** Label written into the result artifacts. */
	private final String label;

	Provenance(String label) {
		this.label = label;
	}

	/**
	 * Returns the label used in CSV and JSON outputs.
	 *
	 * @return the lower-case provenance label
	 */
	public String label() {
		return label;
	}

	/**
	 * Parses a provenance attribute, rejecting unknown values instead of guessing.
	 *
	 * @param value the raw attribute, case insensitive
	 * @return the matching provenance
	 * @throws IllegalArgumentException when the value names no known provenance
	 */
	public static Provenance parse(String value) {
		if (value == null || value.trim().isEmpty())
			return SYNTHETIC;
		for (Provenance provenance : values())
			if (provenance.label.equalsIgnoreCase(value.trim()))
				return provenance;
		throw new IllegalArgumentException("Unknown provenance \"" + value + "\", expected one of: "
				+ SYNTHETIC.label + ", " + LITERATURE.label + ", " + MEASURED.label);
	}
}
