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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import com.mechalikh.docket.domain.Provenance;

/**
 * The duration an execution model predicts for one step, together with the evidence behind it.
 * <p>
 * A duration on its own is not usable in a paper: the reader has to know whether it was
 * measured, taken from the literature, or produced by an analytical formula, and whether the
 * inputs that produced it fell inside the range the model was fitted on. Every estimate
 * therefore carries its {@link Provenance}, a {@link #isCalibrated()} flag, and the warnings
 * that were raised when it was computed. The simulator writes these fields into the artifacts
 * instead of only printing them, so an uncalibrated number cannot be mistaken for a measured
 * one after the run.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 0.1
 */
public final class CostEstimate {

	/** Which part of the roofline determined the predicted duration. */
	public enum LimitingTerm {
		/** The floating-point capacity of the device limits the step. */
		COMPUTE("compute-bound"),
		/** The memory bandwidth limits the step. */
		MEMORY("memory-bound"),
		/** The launch overhead is larger than both, which means a degenerate configuration. */
		OVERHEAD("overhead-bound");

		/** Label written to the artifacts. */
		private final String label;

		LimitingTerm(String label) {
			this.label = label;
		}

		/**
		 * Returns the label used in the artifacts and in the logs.
		 *
		 * @return the human-readable term name
		 */
		public String label() {
			return label;
		}

		/**
		 * Returns the label of this term, so that a log line or an artifact names the term that bound
		 * the predicted duration rather than its enumeration constant.
		 *
		 * @return the human-readable term name
		 */
		@Override
		public String toString() {
			return label;
		}
	}

	/** Predicted duration of the step, in seconds. Always strictly positive. */
	private final double seconds;
	/** Floating-point operations the step is assumed to perform. */
	private final double flops;
	/** Bytes the step is assumed to move through the high-bandwidth memory. */
	private final double bytes;
	/** Which of the two above decided the duration. */
	private final LimitingTerm limitingTerm;
	/** Ratio of the compute time to the memory time, reported to show how close to the knee a step is. */
	private final double arithmeticIntensity;
	/** Where the device and model figures come from. */
	private final Provenance provenance;
	/** Whether the model was fitted to measurements of this device and model pair. */
	private final boolean calibrated;
	/** Warnings raised while computing the estimate, in insertion order, deduplicated. */
	private final Set<String> warnings;

	/**
	 * Creates an estimate.
	 *
	 * @param seconds      predicted duration in seconds, must be positive and finite
	 * @param flops        operations of the step
	 * @param bytes        bytes moved by the step
	 * @param limitingTerm which term decided the duration
	 * @param provenance   origin of the input figures
	 * @param calibrated   whether the model is fitted to measurements
	 * @param warnings     warnings raised for this estimate, possibly empty
	 * @throws IllegalArgumentException when the duration is not positive or finite
	 */
	public CostEstimate(double seconds, double flops, double bytes, LimitingTerm limitingTerm,
			Provenance provenance, boolean calibrated, Set<String> warnings) {
		if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds <= 0)
			throw new IllegalArgumentException("A predicted duration must be a positive finite number of seconds, found "
					+ seconds);
		this.seconds = seconds;
		this.flops = flops;
		this.bytes = bytes;
		this.limitingTerm = limitingTerm;
		this.provenance = provenance;
		this.calibrated = calibrated;
		this.warnings = warnings == null ? Collections.emptySet()
				: Collections.unmodifiableSet(new LinkedHashSet<>(warnings));
		this.arithmeticIntensity = bytes <= 0 ? Double.POSITIVE_INFINITY : flops / bytes;
	}

	/**
	 * Returns the predicted duration.
	 *
	 * @return seconds
	 */
	public double seconds() {
		return seconds;
	}

	/**
	 * Returns the number of floating-point operations the step was charged with.
	 *
	 * @return operations
	 */
	public double flops() {
		return flops;
	}

	/**
	 * Returns the number of bytes the step was charged with.
	 *
	 * @return bytes
	 */
	public double bytes() {
		return bytes;
	}

	/**
	 * Returns the term that decided the duration.
	 *
	 * @return the limiting term
	 */
	public LimitingTerm limitingTerm() {
		return limitingTerm;
	}

	/**
	 * Returns the operations per byte the step performs.
	 * <p>
	 * The roofline switches from memory-bound to compute-bound when this value passes the
	 * ratio of the device peak flops to its bandwidth, so the intensity identifies the regime
	 * of a step without reading its duration.
	 *
	 * @return flops per byte, or positive infinity when the step moves no bytes
	 */
	public double arithmeticIntensity() {
		return arithmeticIntensity;
	}

	/**
	 * Returns the origin of the figures used by the estimate.
	 *
	 * @return the provenance
	 */
	public Provenance provenance() {
		return provenance;
	}

	/**
	 * Returns whether the estimate comes from a model fitted to measurements.
	 *
	 * @return {@code true} when calibrated against measured data
	 */
	public boolean isCalibrated() {
		return calibrated;
	}

	/**
	 * Returns the warnings raised for this estimate.
	 *
	 * @return an immutable set, empty when nothing was raised
	 */
	public Set<String> warnings() {
		return warnings;
	}

	/**
	 * Returns whether the estimate should be treated as provisional.
	 *
	 * @return {@code true} when the model is not calibrated or a warning was raised
	 */
	public boolean isProvisional() {
		return !calibrated || !warnings.isEmpty();
	}

	/**
	 * Formats the estimate for a log line.
	 *
	 * @return a one-line description with the duration, the regime and the intensity
	 */
	public String describe() {
		return String.format(Locale.ROOT, "%.6f s (%s, %.3e flops, %.3e bytes, intensity %.1f flops/byte)", seconds,
				limitingTerm, flops, bytes, arithmeticIntensity == Double.POSITIVE_INFINITY ? 0 : arithmeticIntensity);
	}

	/**
	 * Returns a one-line description that keeps the estimated duration next to its limiting term
	 * and its provenance, so an estimate is not read as an observation.
	 *
	 * @return the duration, the regime, the intensity and the provenance of the estimate
	 */
	@Override
	public String toString() {
		return "CostEstimate[" + describe() + ", " + provenance.label() + ", calibrated=" + calibrated + "]";
	}
}
