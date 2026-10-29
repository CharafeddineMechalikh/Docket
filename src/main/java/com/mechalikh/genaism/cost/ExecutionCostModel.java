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
package com.mechalikh.genaism.cost;

import java.util.Set;

import com.mechalikh.genaism.domain.GpuProfile;
import com.mechalikh.genaism.domain.ModelProfile;
import com.mechalikh.genaism.domain.Provenance;

/**
 * Predicts how long one execution step of a served model takes on one device.
 * <p>
 * Two implementations are expected: an analytical one, usable before any measurement exists,
 * and a calibrated one, fitted to a benchmark matrix of the same model, device, batch sizes
 * and context lengths. The interface makes the difference explicit rather than implicit:
 * every model reports its {@link #provenance()}, whether it was {@link #isCalibrated()}, and
 * the ranges it was built for, and each {@link CostEstimate} carries those flags with it.
 * <p>
 * A model must never silently substitute a plausible number when it lacks the inputs it needs.
 * Missing figures throw.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public interface ExecutionCostModel {

	/**
	 * Returns the short name written to the artifacts.
	 *
	 * @return the model name, for instance {@code roofline-analytical}
	 */
	String name();

	/**
	 * Predicts the duration of reading a prompt chunk.
	 *
	 * @param model             the served model
	 * @param device            the device executing the chunk
	 * @param promptTokens      number of prompt tokens processed by this step, at least one
	 * @param cachedPrefixTokens tokens of the prompt already present in the cache, zero when
	 *                           the whole prompt is read by this step
	 * @return the predicted duration and its evidence
	 * @throws IllegalArgumentException when a required figure is missing
	 */
	CostEstimate prefillStep(ModelProfile model, GpuProfile device, int promptTokens, int cachedPrefixTokens);

	/**
	 * Predicts the duration of one decode iteration for the given batch.
	 *
	 * @param model  the served model
	 * @param device the device executing the iteration
	 * @param batch  the sequences that generate a token in this iteration
	 * @return the predicted duration and its evidence
	 * @throws IllegalArgumentException when a required figure is missing
	 */
	CostEstimate decodeIteration(ModelProfile model, GpuProfile device, DecodeBatch batch);

	/**
	 * Returns where the input figures of this model come from.
	 *
	 * @return the provenance
	 */
	Provenance provenance();

	/**
	 * Returns whether the model was fitted to measurements.
	 * <p>
	 * An analytical model returns {@code false} even when its device and model figures come
	 * from the literature, because the formula itself was never checked against timings of
	 * this backend.
	 *
	 * @return {@code true} only for a fitted model
	 */
	boolean isCalibrated();

	/**
	 * Returns the measurements the model was fitted on.
	 *
	 * @return a description of the calibration source, or {@code "none"} for an analytical model
	 */
	String calibrationSource();

	/**
	 * Returns the caveats that apply to every estimate this model produces.
	 *
	 * @return an immutable set of warnings, never null
	 */
	Set<String> warnings();

	/**
	 * Checks whether a step stays inside the regime this model describes.
	 * <p>
	 * Used to raise an extrapolation warning rather than to refuse the estimate: a simulator
	 * that stops outside the fitted range cannot explore a new configuration, but the artifacts
	 * must show that the number was extrapolated.
	 *
	 * @param model       the served model
	 * @param device      the device
	 * @param decodeBatch the batch of the iteration, or {@code null} for a prefill step
	 * @param stepTokens  tokens processed by the step
	 * @return a warning message, or {@code null} when the step is inside the supported range
	 */
	String extrapolationWarning(ModelProfile model, GpuProfile device, DecodeBatch decodeBatch, int stepTokens);
}
