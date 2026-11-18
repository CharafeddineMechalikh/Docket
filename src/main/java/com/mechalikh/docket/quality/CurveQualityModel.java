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
package com.mechalikh.docket.quality;

import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.domain.Provenance;

/**
 * A configurable quality model: an analytic curve, or a measured curve read from a
 * file, optionally blended with the share of the requested output actually produced.
 * <p>
 * The analytic family is normalised so that {@code q(1) = 1}:
 * {@code linear = f}, {@code sqrt = sqrt(f)}, {@code quadratic = f^2},
 * {@code cubic = f^3}, {@code saturating = sin(pi f / 2)} and
 * {@code threshold = 1[f >= threshold]}. A measured file overrides the family.
 * <p>
 * {@code tokenWeight} blends the curve with {@code min(1, generated/requested)}.
 * At zero (the default) the score depends only on the budget that was granted; at one
 * it depends only on what the client received. A value in between is a modelling
 * choice and must be declared in the artifacts, not inferred by the reader.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 1.0
 */
public final class CurveQualityModel implements QualityModel {

	/** Names accepted by {@code quality.curve}. */
	public static final java.util.List<String> CURVES = java.util.List.of(
			"linear", "sqrt", "quadratic", "cubic", "saturating", "threshold");

	private final String curve;
	private final double threshold;
	private final double tokenWeight;
	private final MeasuredQualityCurve measured;
	private final Provenance provenance;

	/**
	 * Builds a quality model.
	 *
	 * @param curve       one of {@link #CURVES}, case insensitive
	 * @param threshold   jump point of the threshold curve, in {@code (0, 1]}
	 * @param tokenWeight weight of the delivered-token share, in {@code [0, 1]}
	 * @param measured    measured curve overriding the analytic family, or {@code null}
	 * @param provenance  provenance of the curve
	 */
	public CurveQualityModel(String curve, double threshold, double tokenWeight,
			MeasuredQualityCurve measured, Provenance provenance) {
		if (curve == null || !CURVES.contains(curve.toLowerCase(java.util.Locale.ROOT)))
			throw new IllegalArgumentException("Unknown quality curve \"" + curve + "\"; expected one of " + CURVES);
		if (threshold <= 0 || threshold > 1)
			throw new IllegalArgumentException("A quality threshold must lie in (0, 1], found " + threshold);
		if (tokenWeight < 0 || tokenWeight > 1)
			throw new IllegalArgumentException("A token weight must lie in [0, 1], found " + tokenWeight);
		this.curve = curve.toLowerCase(java.util.Locale.ROOT);
		this.threshold = threshold;
		this.tokenWeight = tokenWeight;
		this.measured = measured;
		this.provenance = provenance == null ? Provenance.SYNTHETIC : provenance;
	}

	@Override
	public double score(InferenceRequest request) {
		double fidelity = clamp(request.getFidelityLevel());
		double curveValue = measured == null
				? analytic(fidelity)
				: measured.value(fidelity, request.getServiceClass());
		double delivered = Math.min(1.0, (double) request.getGeneratedTokens()
				/ Math.max(1, request.getOutputTokens()));
		return clamp((1.0 - tokenWeight) * curveValue + tokenWeight * delivered);
	}

	/**
	 * Evaluates the configured analytic curve.
	 *
	 * @param fidelity granted fidelity in {@code [0, 1]}
	 * @return quality in {@code [0, 1]}
	 */
	public double analytic(double fidelity) {
		if ("linear".equals(curve))
			return fidelity;
		if ("sqrt".equals(curve))
			return Math.sqrt(fidelity);
		if ("quadratic".equals(curve))
			return fidelity * fidelity;
		if ("cubic".equals(curve))
			return fidelity * fidelity * fidelity;
		if ("saturating".equals(curve))
			return Math.sin(Math.PI * fidelity / 2.0);
		if ("threshold".equals(curve))
			return fidelity >= threshold ? 1.0 : 0.0;
		throw new IllegalStateException("Unreachable quality curve \"" + curve + "\"");
	}

	@Override
	public String describe() {
		String shape = measured == null
				? (curve.equals("threshold") ? "threshold@" + threshold : curve)
				: "measured[" + measured.source() + "]";
		return shape + " (token weight " + String.format(java.util.Locale.ROOT, "%.2f", tokenWeight)
				+ ", " + provenance.label() + ")";
	}

	@Override
	public Provenance provenance() {
		return provenance;
	}

	/** Returns the delivered-token share of the blend. */
	public double tokenWeight() {
		return tokenWeight;
	}

	/** Returns the measured curve, or {@code null} when the analytic family is used. */
	public MeasuredQualityCurve measured() {
		return measured;
	}

	private static double clamp(double value) {
		if (Double.isNaN(value))
			return 0.0;
		return Math.max(0.0, Math.min(1.0, value));
	}
}
