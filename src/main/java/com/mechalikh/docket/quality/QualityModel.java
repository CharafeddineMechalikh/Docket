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

import com.mechalikh.docket.config.Settings;
import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.domain.Provenance;

/**
 * Maps the service a request actually received to a quality score in {@code [0, 1]}.
 * <p>
 * Docket has always recorded how much reasoning a request was granted and how many
 * tokens it produced. Neither number is an answer-accuracy measurement: the first is
 * a budget the orchestrator gave away, and the second is a length. A quality model
 * makes that distinction explicit and configurable. It can be an analytic family of
 * curves when no evaluation data exists, a measured curve read from a calibration
 * file, or a blend of the granted share with the delivered-token share.
 * <p>
 * Every score carries the provenance of its curve, which the run artifacts inherit:
 * an invented curve can never make a run read as measured.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 1.0
 */
public interface QualityModel {

	/**
	 * Scores one terminal request. The result is clamped to {@code [0, 1]}.
	 *
	 * @param request the terminal request to score
	 * @return retained quality, zero to one
	 */
	double score(InferenceRequest request);

	/**
	 * Returns a human-readable definition, written into the artifacts.
	 *
	 * @return a description of the curve and its provenance
	 */
	String describe();

	/**
	 * Returns where the curve came from.
	 *
	 * @return the curve provenance
	 */
	Provenance provenance();

	/**
	 * Builds the quality model named by {@code quality.*} settings.
	 * <p>
	 * <ul>
	 * <li>{@code quality.curve}: {@code linear} (default), {@code sqrt},
	 * {@code quadratic}, {@code cubic}, {@code saturating} or {@code threshold};</li>
	 * <li>{@code quality.threshold}: fidelity at which a threshold curve jumps, default 0.5;</li>
	 * <li>{@code quality.tokenWeight}: weight of the delivered-token share, default 0;</li>
	 * <li>{@code quality.calibration.file}: optional CSV of {@code fidelity,quality} points,
	 * which overrides the analytic curve;</li>
	 * <li>{@code quality.calibration.provenance}: {@code synthetic}, {@code literature}
	 * or {@code measured}, default {@code literature}.</li>
	 * </ul>
	 *
	 * @param settings the loaded scenario settings
	 * @return the quality model, never {@code null}
	 */
	static QualityModel from(Settings settings) {
		String curve = settings.text("quality.curve", "linear");
		double threshold = settings.number("quality.threshold", 0.5);
		double tokenWeight = settings.number("quality.tokenWeight", 0.0);
		if (threshold <= 0 || threshold > 1)
			throw new IllegalArgumentException("\"quality.threshold\" must lie in (0, 1], found " + threshold);
		if (tokenWeight < 0 || tokenWeight > 1)
			throw new IllegalArgumentException("\"quality.tokenWeight\" must lie in [0, 1], found " + tokenWeight);
		String file = settings.text("quality.calibration.file", "");
		Provenance provenance = Provenance.parse(
				settings.text("quality.calibration.provenance", "literature"), "quality.calibration.provenance");
		MeasuredQualityCurve measured = null;
		if (!file.isEmpty())
			measured = MeasuredQualityCurve.from(java.nio.file.Path.of(file));
		return new CurveQualityModel(curve, threshold, tokenWeight, measured, provenance);
	}
}
