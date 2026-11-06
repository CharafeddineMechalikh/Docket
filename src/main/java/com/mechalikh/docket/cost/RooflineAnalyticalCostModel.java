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
import java.util.Set;

import com.mechalikh.docket.domain.GpuProfile;
import com.mechalikh.docket.domain.ModelProfile;
import com.mechalikh.docket.domain.Provenance;

/**
 * A roofline estimate of prefill and decode durations, built from the model arithmetic and the
 * declared device capacity.
 * <p>
 * This model is <strong>analytical and uncalibrated</strong>. It turns the counts below into a
 * duration with
 * <pre>
 * seconds = max(flops / achievedFlopsPerSecond, bytes / memoryBandwidthBytesPerSecond)
 *           + kernelLaunchOverheadSeconds
 * </pre>
 * and nothing more. It is what makes the simulator runnable before any benchmark matrix
 * exists; it is not evidence about a real backend, and every {@link CostEstimate} it returns
 * says so.
 * <p>
 * The arithmetic follows the standard dense-transformer accounting:
 * <ul>
 * <li>prefill of {@code t} tokens with {@code p} already cached:
 * {@code 2 * activeParameters * t} for the projections, plus the causal attention term
 * {@code 4 * layers * kvDimension * (t * p + t * (t + 1) / 2)};</li>
 * <li>decode of a batch of {@code n} sequences with cached contexts {@code c_i}:
 * {@code 2 * activeParameters * n} plus {@code 4 * layers * kvDimension * sum(c_i)}.</li>
 * </ul>
 * The traffic assumes the weights are streamed once per step, which is the behaviour of a
 * fused kernel, and that the whole key-value cache is read once per step, which is why decode
 * turns memory-bound as the contexts grow.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 0.1
 */
public class RooflineAnalyticalCostModel implements ExecutionCostModel {

	/** Identifier written to the artifacts. */
	public static final String NAME = "roofline-analytical";

	/** Batch sizes above this value are reported as extrapolated. */
	protected int supportedMaxBatchSize = 512;
	/** Steps processing more tokens than this are reported as extrapolated. */
	protected int supportedMaxStepTokens = 16384;

	/** Caveats that apply to every estimate, computed once. */
	protected final Set<String> warnings = buildWarnings();

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
	 * The whole prompt is charged against the weights of the model, so a chunked prefill of
	 * several small chunks is slower than one large chunk. That is the behaviour of a real
	 * backend, which re-reads the weights per chunk.
	 */
	@Override
	public CostEstimate prefillStep(ModelProfile model, GpuProfile device, int promptTokens, int cachedPrefixTokens) {
		validatePair(model, device);
		if (promptTokens < 1)
			throw new IllegalArgumentException("A prefill step processes at least one token, found " + promptTokens);
		if (cachedPrefixTokens < 0)
			throw new IllegalArgumentException("The cached prefix cannot be negative, found " + cachedPrefixTokens);

		double flops = prefillFlops(model, promptTokens, cachedPrefixTokens);
		double bytes = prefillBytes(model, promptTokens, cachedPrefixTokens);
		return score(model, device, flops, bytes, promptTokens, null);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public CostEstimate decodeIteration(ModelProfile model, GpuProfile device, DecodeBatch batch) {
		validatePair(model, device);
		if (batch == null)
			throw new IllegalArgumentException("A decode iteration needs the batch it processes");

		double flops = decodeFlops(model, batch);
		double bytes = decodeBytes(model, batch);
		return score(model, device, flops, bytes, batch.sequences(), batch);
	}

	/**
	 * Predicts the duration of one speculative-decoding verification step, in which every sequence in
	 * the batch proposes {@code draftTokens} tokens and the target model checks them in parallel.
	 * <p>
	 * A verification step is not a plain decode step: each sequence advances {@code v = draftTokens +
	 * 1} candidate positions at once, which is the same arithmetic as a small prefill over the running
	 * context. The projection term therefore scales with {@code sequences * v}, and the attention term
	 * reads each sequence's cached context for every one of the {@code v} positions plus the quadratic
	 * self-attention among them. The cache write is charged for all {@code v} positions, since the
	 * target model materialises a key and a value for each candidate before discarding the rejected
	 * ones.
	 * <p>
	 * This method does not decide how many candidates are kept: that is the acceptance rate, a
	 * property of the draft model the scheduler reads, not of the hardware. It reports the cost of the
	 * step that verifies them.
	 *
	 * @param model      the served target model
	 * @param device     the device executing the step
	 * @param batch      the sequences taking part, carrying their cached contexts
	 * @param draftTokens candidate tokens proposed per sequence beyond the one guaranteed, at least one
	 * @return the predicted duration and its evidence
	 */
	@Override
	public CostEstimate speculativeIteration(ModelProfile model, GpuProfile device, DecodeBatch batch, int draftTokens) {
		validatePair(model, device);
		if (batch == null)
			throw new IllegalArgumentException("A speculative step needs the batch it processes");
		if (draftTokens < 1)
			throw new IllegalArgumentException("A speculative step verifies at least two positions, found " + draftTokens
					+ " draft tokens");
		int v = draftTokens + 1;

		double projections = 2.0 * model.activeParameters() * batch.sequences() * v;
		double attention = 4.0 * model.getLayers() * model.kvDimension()
				* (v * (double) batch.totalContextTokens() + batch.sequences() * v * (v + 1.0) / 2.0);
		double weights = model.weightBytes();
		double cacheRead = model.kvBytesPerToken() * v * (double) batch.totalContextTokens();
		double cacheWrite = model.kvBytesPerToken() * batch.sequences() * v;
		double bytes = weights + cacheRead + cacheWrite + model.getActivationBytesPerSequence() * batch.sequences();
		int stepTokens = batch.sequences() * v;
		return score(model, device, projections + attention, bytes, stepTokens, batch);
	}

	/**
	 * Returns the operations a prefill step performs.
	 *
	 * @param model              the served model
	 * @param tokens             tokens processed by the step
	 * @param cachedPrefixTokens tokens already cached before the step
	 * @return floating-point operations
	 */
	public double prefillFlops(ModelProfile model, int tokens, int cachedPrefixTokens) {
		double projections = 2.0 * model.activeParameters() * tokens;
		double attention = 4.0 * model.getLayers() * model.kvDimension()
				* (tokens * (double) cachedPrefixTokens + tokens * (tokens + 1.0) / 2.0);
		return projections + attention;
	}

	/**
	 * Returns the bytes a prefill step moves through the high-bandwidth memory.
	 *
	 * @param model              the served model
	 * @param tokens             tokens processed by the step
	 * @param cachedPrefixTokens tokens already cached before the step
	 * @return bytes
	 */
	public double prefillBytes(ModelProfile model, int tokens, int cachedPrefixTokens) {
		double weights = model.weightBytes();
		double cache = model.kvBytesPerToken() * (cachedPrefixTokens + tokens);
		return weights + cache + model.getActivationBytesPerSequence();
	}

	/**
	 * Returns the operations one decode iteration performs.
	 *
	 * @param model the served model
	 * @param batch the batch processed by the iteration
	 * @return floating-point operations
	 */
	public double decodeFlops(ModelProfile model, DecodeBatch batch) {
		double projections = 2.0 * model.activeParameters() * batch.sequences();
		double attention = 4.0 * model.getLayers() * model.kvDimension() * batch.totalContextTokens();
		return projections + attention;
	}

	/**
	 * Returns the bytes one decode iteration moves.
	 *
	 * @param model the served model
	 * @param batch the batch processed by the iteration
	 * @return bytes
	 */
	public double decodeBytes(ModelProfile model, DecodeBatch batch) {
		double weights = model.weightBytes();
		double cacheRead = model.kvBytesPerToken() * batch.totalContextTokens();
		double cacheWrite = model.kvBytesPerToken() * batch.sequences();
		return weights + cacheRead + cacheWrite + model.getActivationBytesPerSequence() * batch.sequences();
	}

	/**
	 * Turns the counts into a duration and attaches the evidence to it.
	 *
	 * @param model      the served model
	 * @param device     the executing device
	 * @param flops      operations of the step
	 * @param bytes      bytes of the step
	 * @param stepTokens tokens the step processes, used for the extrapolation check
	 * @param batch      the decode batch, {@code null} for a prefill step
	 * @return the estimate
	 */
	protected CostEstimate score(ModelProfile model, GpuProfile device, double flops, double bytes, int stepTokens,
			DecodeBatch batch) {
		// Quantized-kernel arithmetic, added only when the model opts into it. With the mechanism off
		// the overhead and expansion are zero and the compute precision equals the storage precision,
		// so the two lines reduce to the plain roofline this method computed before the mechanism.
		double chargedFlops = flops + model.quantizationOverheadFlops();
		double chargedBytes = bytes + model.quantizationExpansionBytes();
		double computeSeconds = chargedFlops / device.flopsPerSecond(model.effectiveComputePrecision());
		double memorySeconds = chargedBytes / device.getMemoryBandwidthBytesPerSecond();
		double overhead = device.getKernelLaunchOverheadSeconds();
		double seconds = Math.max(computeSeconds, memorySeconds) + overhead;
		if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds <= 0)
			throw new IllegalStateException("The roofline produced a duration of " + seconds
					+ " s for model \"" + model.getId() + "\" on device \"" + device.getId()
					+ "\"; check the throughput and bandwidth figures");

		CostEstimate.LimitingTerm term = CostEstimate.LimitingTerm.COMPUTE;
		if (memorySeconds > computeSeconds && memorySeconds >= overhead)
			term = CostEstimate.LimitingTerm.MEMORY;
		else if (overhead > computeSeconds && overhead > memorySeconds)
			term = CostEstimate.LimitingTerm.OVERHEAD;

		Set<String> estimateWarnings = new LinkedHashSet<>(warnings);
		if (model.isQuantizedKernel()) {
			if (!model.effectiveComputePrecision().equals(model.getPrecision()))
				estimateWarnings.add("the quantized kernel stores weights at " + model.getPrecision()
						+ " but computes at " + model.effectiveComputePrecision()
						+ "; the duration divides by the device throughput of the compute format, which the "
						+ "scenario must declare for that precision");
			if (model.quantizationOverheadFlops() > 0 || model.quantizationExpansionBytes() > 0)
				estimateWarnings.add("the quantized kernel charges a dequantization cost of "
						+ String.format("%.3e", model.quantizationOverheadFlops())
						+ " flops and " + String.format("%.3e", model.quantizationExpansionBytes())
						+ " bytes per step; these model the unpacking a fused kernel performs and are not a "
						+ "measured figure unless the calibration source says so");
		}
		String extrapolation = extrapolationWarning(model, device, batch, stepTokens);
		if (extrapolation != null)
			estimateWarnings.add(extrapolation);
		if (term == CostEstimate.LimitingTerm.OVERHEAD)
			estimateWarnings.add("the launch overhead of device \"" + device.getId() + "\" (" + overhead
					+ " s) dominates a step that needs " + String.format("%.6f", Math.max(computeSeconds, memorySeconds))
					+ " s of work; the duration is set by the overhead");

		Provenance provenance = combineProvenance(model, device);
		return new CostEstimate(seconds, chargedFlops, chargedBytes, term, provenance, isCalibrated(), estimateWarnings);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String extrapolationWarning(ModelProfile model, GpuProfile device, DecodeBatch decodeBatch,
			int stepTokens) {
		if (decodeBatch != null && decodeBatch.sequences() > supportedMaxBatchSize)
			return "batch of " + decodeBatch.sequences() + " exceeds the " + supportedMaxBatchSize
					+ " sequences this analytical form was sanity-checked against";
		if (stepTokens > supportedMaxStepTokens)
			return "a step of " + stepTokens + " tokens exceeds the " + supportedMaxStepTokens
					+ " tokens this analytical form was sanity-checked against";
		if (model.getKvBytesPerScalar() < model.getBytesPerParameter())
			return "the cache is quantized more aggressively (" + model.getKvDataType() + ", "
					+ model.getKvBytesPerScalar() + " B/scalar) than the weights (" + model.getPrecision() + ", "
					+ model.getBytesPerParameter()
					+ " B/scalar); the arithmetic term is unchanged by cache quantization but a real backend differs";
		return null;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Provenance provenance() {
		return Provenance.SYNTHETIC;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean isCalibrated() {
		return false;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String calibrationSource() {
		return "none";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Set<String> warnings() {
		return Collections.unmodifiableSet(warnings);
	}

	/**
	 * Builds the caveats attached to every estimate of this model.
	 *
	 * @return the warning set
	 */
	protected Set<String> buildWarnings() {
		Set<String> set = new LinkedHashSet<>();
		set.add(NAME + " is analytical and uncalibrated: no measured timing of this model, device or "
				+ "backend was used, so durations are estimates of the hardware limits, not of a serving engine");
		set.add("the achieved throughput of the device is used, never its published peak");
		set.add("weights are charged once per step, which assumes a fused kernel; a backend that re-reads "
				+ "them per layer would be slower");
		set.add("the cache is charged as a full read per step; a paged or sliding-window layout moves fewer bytes");
		return set;
	}

	/**
	 * Returns the weakest provenance of the two inputs.
	 *
	 * @param model  the served model
	 * @param device the executing device
	 * @return synthetic when either input is synthetic, otherwise literature
	 */
	protected Provenance combineProvenance(ModelProfile model, GpuProfile device) {
		if (model.getProvenance() == Provenance.SYNTHETIC || device.getProvenance() == Provenance.SYNTHETIC)
			return Provenance.SYNTHETIC;
		if (model.getProvenance() == Provenance.MEASURED && device.getProvenance() == Provenance.MEASURED)
			return Provenance.MEASURED;
		return Provenance.LITERATURE;
	}

	/**
	 * Checks that the pair can be scored.
	 *
	 * @param model  the served model
	 * @param device the executing device
	 * @throws IllegalArgumentException when a profile is invalid or the precision is unknown
	 */
	protected void validatePair(ModelProfile model, GpuProfile device) {
		if (model == null)
			throw new IllegalArgumentException("A cost estimate needs a model profile");
		if (device == null)
			throw new IllegalArgumentException("A cost estimate needs a device profile");
		model.validate();
		device.validate();
		device.flopsPerSecond(model.effectiveComputePrecision());
	}

	/**
	 * Returns the batch size above which an estimate is labelled as extrapolated.
	 * <p>
	 * The analytical form was sanity-checked up to this many concurrent sequences. A larger batch
	 * is still scored, but the estimate carries a warning so that a reader can tell a measured
	 * operating point from one the model was never checked against.
	 *
	 * @return the supported batch size in sequences, positive
	 */
	public int getSupportedMaxBatchSize() {
		return supportedMaxBatchSize;
	}

	/**
	 * Sets the batch size above which estimates are labelled as extrapolated.
	 *
	 * @param supportedMaxBatchSize sequences, positive
	 * @return this model
	 */
	public RooflineAnalyticalCostModel setSupportedMaxBatchSize(int supportedMaxBatchSize) {
		if (supportedMaxBatchSize < 1)
			throw new IllegalArgumentException("The supported batch size must be positive");
		this.supportedMaxBatchSize = supportedMaxBatchSize;
		return this;
	}

	/**
	 * Returns the step size above which an estimate is labelled as extrapolated.
	 * <p>
	 * A prefill of more tokens than this, or a decode step whose batch carries more, is still
	 * scored, but the estimate carries a warning because the arithmetic was checked only up to
	 * this point.
	 *
	 * @return the supported tokens per step, positive
	 */
	public int getSupportedMaxStepTokens() {
		return supportedMaxStepTokens;
	}

	/**
	 * Sets the step size above which estimates are labelled as extrapolated.
	 *
	 * @param supportedMaxStepTokens tokens, positive
	 * @return this model
	 */
	public RooflineAnalyticalCostModel setSupportedMaxStepTokens(int supportedMaxStepTokens) {
		if (supportedMaxStepTokens < 1)
			throw new IllegalArgumentException("The supported step size must be positive");
		this.supportedMaxStepTokens = supportedMaxStepTokens;
		return this;
	}

	/**
	 * Returns a one-line description of the model and its two sanity-check limits.
	 *
	 * @return the model name, the supported batch size and the supported step size
	 */
	@Override
	public String toString() {
		return NAME + "[max batch " + supportedMaxBatchSize + ", max step " + supportedMaxStepTokens + " tokens]";
	}
}
