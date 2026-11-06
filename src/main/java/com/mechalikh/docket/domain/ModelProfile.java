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

/**
 * The architecture and memory footprint of one served model.
 * <p>
 * The dimensions describe a dense decoder-only transformer, which is the only family the
 * analytical execution model scores. A mixture-of-experts or a non-transformer architecture
 * needs its own execution model, so {@link #validate()} rejects dimensions that do not form
 * a consistent dense attention block.
 * <p>
 * The key-value cache size per token follows the conventional estimate
 * <pre>
 * M(kv/token) = 2 * layers * kvHeads * headDimension * bytesPerScalar
 * </pre>
 * where the factor two accounts for keys and values. Block allocation, quantized caches and
 * sliding-window attention are accounted for by
 * {@link com.mechalikh.docket.memory.KvCachePool}, not by changing this estimate.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 0.1
 */
public class ModelProfile {

	/** Identifier used by the workload to select the model. */
	protected String id = "";
	/** Human readable name. */
	protected String name = "";
	/** Model version, free text; the simulator does not interpret it. */
	protected String version = "";
	/** Architecture family, {@code dense-transformer} is the only scored one. */
	protected String architecture = "dense-transformer";
	/** Total number of parameters, including embeddings. */
	protected long parameterCount;
	/** Number of transformer blocks. */
	protected int layers;
	/** Residual width of the model, in scalars. */
	protected int hiddenDimension;
	/** Number of query and key heads. */
	protected int attentionHeads;
	/** Number of key-value heads, equal to {@link #attentionHeads} for multi-head attention. */
	protected int kvHeads;
	/** Width of one attention head, in scalars. Derived from the hidden width when unset. */
	protected int headDimension;
	/** Inner width of the feed-forward block, in scalars. */
	protected int feedForwardDimension;
	/** Size of the token vocabulary. */
	protected int vocabularySize;
	/** Largest prompt plus output length the model accepts. */
	protected int maxContextTokens;
	/** Name of the weight precision, for instance {@code fp16}. */
	protected String precision = "fp16";
	/** Bytes occupied by one weight scalar at {@link #precision}. */
	protected double bytesPerParameter = 2;
	/** Storage type of the cached keys and values, for instance {@code fp16}. */
	protected String kvDataType = "fp16";
	/** Bytes occupied by one cached scalar. */
	protected double kvBytesPerScalar = 2;
	/** Quantization applied to the weights, {@code none} by default. */
	protected String quantization = "none";
	/**
	 * Whether the quantized-kernel arithmetic is modelled beyond the scalar byte width. Off by
	 * default, which keeps the roofline charging the weights at {@link #precision} and adding no
	 * dequantization cost, exactly as before the mechanism existed.
	 */
	protected boolean quantizedKernel = false;
	/**
	 * Precision at which the matmul arithmetic runs when {@link #quantizedKernel} is on, which may
	 * differ from the {@link #precision} the weights are stored at (int4 weights, bf16 compute).
	 * Empty means the storage precision.
	 */
	protected String computePrecision = "";
	/**
	 * Operations the kernel spends unpacking one weight scalar from its storage format into the
	 * compute format, charged once per weight per step. Zero adds no dequantization cost.
	 */
	protected double dequantFlopsPerScalar = 0;
	/**
	 * Bytes occupied by one weight scalar in the compute format, used for the dequantization write
	 * traffic. Zero means the arithmetic charges no expansion beyond the storage bytes.
	 */
	protected double computeBytesPerScalar = 0;
	/**
	 * Bytes reserved per running sequence for activations and intermediate buffers, on top of
	 * the key-value cache.
	 */
	protected double activationBytesPerSequence = 0;
	/** Fraction of the weights that participate in the computation of a token. */
	protected double activeParameterFraction = 1.0;
	/** Where the numbers of this profile come from. */
	protected Provenance provenance = Provenance.SYNTHETIC;

	/**
	 * Returns the bytes occupied by the weights of the model.
	 *
	 * @return weight size, in bytes
	 */
	public double weightBytes() {
		return parameterCount * bytesPerParameter;
	}

	/**
	 * Returns the bytes the cache needs for one token, keys and values together.
	 *
	 * @return key-value bytes per token
	 */
	public double kvBytesPerToken() {
		return 2.0 * layers * kvHeads * headDimension * kvBytesPerScalar;
	}

	/**
	 * Returns the cache footprint of a given number of cached tokens.
	 *
	 * @param tokens cached tokens, at least zero
	 * @return bytes needed to keep those tokens
	 */
	public double kvCacheBytes(int tokens) {
		return Math.max(0, tokens) * kvBytesPerToken();
	}

	/**
	 * Returns the number of parameters a token passes through.
	 * <p>
	 * For a dense model every parameter is active, so this is {@link #parameterCount}. The
	 * fraction is exposed so a mixture-of-experts profile can declare a smaller active size
	 * once an execution model exists for it.
	 *
	 * @return active parameters for one token
	 */
	public double activeParameters() {
		return parameterCount * activeParameterFraction;
	}

	/**
	 * Returns the width of the cached key and value tensors.
	 *
	 * @return {@code kvHeads * headDimension}, in scalars
	 */
	public int kvDimension() {
		return kvHeads * headDimension;
	}

	/**
	 * Checks that the dimensions describe a usable dense transformer.
	 *
	 * @throws IllegalArgumentException when a dimension is missing, non-positive, or when the
	 *                                  head geometry is inconsistent
	 */
	public void validate() {
		require(id != null && !id.isEmpty(), "the model needs an id");
		require(parameterCount > 0, "parameterCount must be positive");
		require(layers > 0, "layers must be positive");
		require(hiddenDimension > 0, "hiddenDimension must be positive");
		require(attentionHeads > 0, "attentionHeads must be positive");
		require(kvHeads > 0, "kvHeads must be positive");
		require(headDimension > 0, "headDimension must be positive");
		require(maxContextTokens > 0, "maxContextTokens must be positive");
		require(bytesPerParameter > 0, "bytesPerParameter must be positive");
		require(kvBytesPerScalar > 0, "kvBytesPerScalar must be positive");
		require(architecture.equalsIgnoreCase("dense-transformer"),
				"the analytical execution model only scores the dense-transformer architecture, found \""
						+ architecture + "\"");
		require(activeParameterFraction > 0 && activeParameterFraction <= 1,
				"activeParameterFraction must be in (0, 1]");
		require(attentionHeads <= hiddenDimension, "attentionHeads cannot exceed hiddenDimension");
		if (kvHeads > attentionHeads)
			require(false, "kvHeads (" + kvHeads + ") cannot exceed attentionHeads (" + attentionHeads + ")");
		require(attentionHeads * headDimension == hiddenDimension,
				"the attention heads do not fill the hidden width: " + attentionHeads + " heads of "
						+ headDimension + " scalars against a hidden width of " + hiddenDimension);
	}

	/**
	 * Fills the dimensions that a configuration may leave out.
	 * <p>
	 * The key-value head count defaults to the query head count, which is multi-head attention,
	 * and the head width defaults to the hidden width divided by the number of heads.
	 */
	public void applyDefaults() {
		if (kvHeads <= 0)
			kvHeads = attentionHeads;
		if (headDimension <= 0 && attentionHeads > 0)
			headDimension = hiddenDimension / attentionHeads;
	}

	/**
	 * Throws when a condition fails, naming the model the condition belongs to.
	 *
	 * @param condition    the expectation
	 * @param errorMessage what went wrong
	 */
	protected void require(boolean condition, String errorMessage) {
		if (!condition)
			throw new IllegalArgumentException("Model \"" + id + "\": " + errorMessage);
	}

	/**
	 * Returns the identifier the workload uses to select this model among the configured ones.
	 *
	 * @return the model id, empty when the profile was not loaded from a configuration
	 */
	public String getId() {
		return id;
	}

	/**
	 * Sets the identifier a workload names when it asks for this model.
	 *
	 * @param id the model id, non-empty for {@link #validate()} to pass
	 * @return this profile
	 */
	public ModelProfile setId(String id) {
		this.id = id;
		return this;
	}

	/**
	 * Returns the human readable name, which labels the model in reports and logs and never
	 * enters a timing or memory computation.
	 *
	 * @return the display name, empty when unset
	 */
	public String getName() {
		return name;
	}

	/**
	 * Sets the display name shown in the artifacts.
	 *
	 * @param name the human readable model name
	 * @return this profile
	 */
	public ModelProfile setName(String name) {
		this.name = name;
		return this;
	}

	/**
	 * Returns the version of the weights as free text, which the simulator carries into the
	 * artifacts and never parses.
	 *
	 * @return the version label, empty when unset
	 */
	public String getVersion() {
		return version;
	}

	/**
	 * Sets the version label of the weights.
	 *
	 * @param version the free-text version
	 * @return this profile
	 */
	public ModelProfile setVersion(String version) {
		this.version = version;
		return this;
	}

	/**
	 * Returns the architecture family. The analytical execution model scores only
	 * {@code dense-transformer}, and {@link #validate()} rejects any other value.
	 *
	 * @return the family name, {@code dense-transformer} by default
	 */
	public String getArchitecture() {
		return architecture;
	}

	/**
	 * Sets the architecture family, which decides whether the dense execution model is allowed
	 * to score this profile at all.
	 *
	 * @param architecture the family name, currently only {@code dense-transformer} is scored
	 * @return this profile
	 */
	public ModelProfile setArchitecture(String architecture) {
		this.architecture = architecture;
		return this;
	}

	/**
	 * Returns the total parameter count, embeddings included. Multiplied by the width of a
	 * weight scalar it gives the resident weight footprint of the model.
	 *
	 * @return parameters in the whole model, positive
	 */
	public long getParameterCount() {
		return parameterCount;
	}

	/**
	 * Sets the total parameter count, including the embedding matrices.
	 *
	 * @param parameterCount parameters in the whole model, positive
	 * @return this profile
	 */
	public ModelProfile setParameterCount(long parameterCount) {
		this.parameterCount = parameterCount;
		return this;
	}

	/**
	 * Returns the number of transformer blocks. Every layer holds one key and one value tensor
	 * per cached token, so the layer count scales the cache linearly.
	 *
	 * @return the layer count, positive
	 */
	public int getLayers() {
		return layers;
	}

	/**
	 * Sets how many transformer blocks are stacked in the model.
	 *
	 * @param layers transformer blocks, positive
	 * @return this profile
	 */
	public ModelProfile setLayers(int layers) {
		this.layers = layers;
		return this;
	}

	/**
	 * Returns the residual stream width, in scalars, so the hidden width of a token vector. The
	 * attention heads must fill it exactly: {@code attentionHeads * headDimension} equals it.
	 *
	 * @return hidden width in scalars, positive
	 */
	public int getHiddenDimension() {
		return hiddenDimension;
	}

	/**
	 * Sets the residual stream width that the attention heads and the feed-forward block
	 * expand from.
	 *
	 * @param hiddenDimension width in scalars, positive
	 * @return this profile
	 */
	public ModelProfile setHiddenDimension(int hiddenDimension) {
		this.hiddenDimension = hiddenDimension;
		return this;
	}

	/**
	 * Returns the number of query heads in each attention block. The query projection produces
	 * this many heads of {@link #getHeadDimension()} scalars, so the product must fill the hidden
	 * width.
	 *
	 * @return the query head count, positive
	 */
	public int getAttentionHeads() {
		return attentionHeads;
	}

	/**
	 * Sets the query head count. The head width and the hidden width must stay consistent with
	 * it: {@code attentionHeads * headDimension == hiddenDimension}.
	 *
	 * @param attentionHeads query heads per attention block, positive
	 * @return this profile
	 */
	public ModelProfile setAttentionHeads(int attentionHeads) {
		this.attentionHeads = attentionHeads;
		return this;
	}

	/**
	 * Returns the number of key/value heads, smaller than the query head count when the model
	 * uses grouped-query attention. The cache estimate scales with this count, so sharing keys
	 * and values across query heads shrinks the footprint.
	 *
	 * @return the key/value head count, {@code 0} or less while still unset
	 */
	public int getKvHeads() {
		return kvHeads;
	}

	/**
	 * Sets how many key and value heads the cache stores, the lever a grouped-query attention
	 * model uses to shrink its footprint.
	 * <p>
	 * A value of {@code 0} or less leaves the field unset and asks {@link #applyDefaults()} to
	 * copy the query head count, which is ordinary multi-head attention.
	 *
	 * @param kvHeads key/value heads, at most the query head count once defaults are applied
	 * @return this profile
	 */
	public ModelProfile setKvHeads(int kvHeads) {
		this.kvHeads = kvHeads;
		return this;
	}

	/**
	 * Returns the width of one attention head, in scalars. It multiplies with the key/value head
	 * count to give the width of the cached key and value vectors.
	 *
	 * @return scalars per head, {@code 0} or less while still unset
	 */
	public int getHeadDimension() {
		return headDimension;
	}

	/**
	 * Sets the width of one attention head, which together with the head counts must reproduce
	 * the hidden width.
	 * <p>
	 * A value of {@code 0} or less leaves the field unset and asks {@link #applyDefaults()} to
	 * derive it as {@code hiddenDimension / attentionHeads}.
	 *
	 * @param headDimension scalars per head, positive once defaults are applied
	 * @return this profile
	 */
	public ModelProfile setHeadDimension(int headDimension) {
		this.headDimension = headDimension;
		return this;
	}

	/**
	 * Returns the inner width of the feed-forward block, which expands a token from the hidden
	 * width and contracts it back. The roofline reads the parameter count directly, so this
	 * value documents the block rather than resizing it, and {@link #validate()} does not
	 * require it.
	 *
	 * @return inner width in scalars, {@code 0} when the profile leaves it out
	 */
	public int getFeedForwardDimension() {
		return feedForwardDimension;
	}

	/**
	 * Sets the inner width of the feed-forward block, for documentation and consistency checks.
	 *
	 * @param feedForwardDimension inner width in scalars, usually a small multiple of the hidden
	 *                             width
	 * @return this profile
	 */
	public ModelProfile setFeedForwardDimension(int feedForwardDimension) {
		this.feedForwardDimension = feedForwardDimension;
		return this;
	}

	/**
	 * Returns the number of distinct tokens the model can emit, which bounds the output
	 * projection. It is recorded for reference and not validated, because the cost model counts
	 * the projection through the parameter total.
	 *
	 * @return vocabulary size in tokens, {@code 0} when unset
	 */
	public int getVocabularySize() {
		return vocabularySize;
	}

	/**
	 * Sets the vocabulary size, that is the number of rows of the input and output embedding
	 * matrices.
	 *
	 * @param vocabularySize token types the tokenizer can produce, non-negative
	 * @return this profile
	 */
	public ModelProfile setVocabularySize(int vocabularySize) {
		this.vocabularySize = vocabularySize;
		return this;
	}

	/**
	 * Returns the largest prompt plus output length, in tokens, that the model accepts. A request
	 * that would exceed it has to be rejected or truncated before it reaches the cache.
	 *
	 * @return the context limit in tokens, positive
	 */
	public int getMaxContextTokens() {
		return maxContextTokens;
	}

	/**
	 * Sets the context window that the admission and memory checks enforce.
	 *
	 * @param maxContextTokens prompt plus output limit in tokens, positive
	 * @return this profile
	 */
	public ModelProfile setMaxContextTokens(int maxContextTokens) {
		this.maxContextTokens = maxContextTokens;
		return this;
	}

	/**
	 * Returns the name of the weight precision, for instance {@code fp16}. The device profile
	 * uses it to look up the achieved throughput, so a precision the device does not declare
	 * stops the scenario with an error.
	 *
	 * @return the precision label, {@code fp16} by default
	 */
	public String getPrecision() {
		return precision;
	}

	/**
	 * Sets the precision label used to select the device throughput and to label the artifacts.
	 * It does not by itself change {@link #getBytesPerParameter()}.
	 *
	 * @param precision a label such as {@code fp16} or {@code int8}
	 * @return this profile
	 */
	public ModelProfile setPrecision(String precision) {
		this.precision = precision;
		return this;
	}

	/**
	 * Returns the bytes occupied by one weight scalar, which converts the parameter count into
	 * the resident weight footprint (see {@link #weightBytes()}).
	 *
	 * @return bytes per weight scalar, positive
	 */
	public double getBytesPerParameter() {
		return bytesPerParameter;
	}

	/**
	 * Sets the width of one weight scalar. The configuration loader derives it from
	 * {@link #getPrecision()} unless the scenario overrides it.
	 *
	 * @param bytesPerParameter bytes per weight scalar, positive
	 * @return this profile
	 */
	public ModelProfile setBytesPerParameter(double bytesPerParameter) {
		this.bytesPerParameter = bytesPerParameter;
		return this;
	}

	/**
	 * Returns the storage type of the cached keys and values, for instance {@code fp16} or
	 * {@code int8}. It labels a quantized cache; the size arithmetic uses
	 * {@link #getKvBytesPerScalar()} instead.
	 *
	 * @return the cache data type label, {@code fp16} by default
	 */
	public String getKvDataType() {
		return kvDataType;
	}

	/**
	 * Sets the storage type of the cache, from which the loader derives the cached scalar width
	 * unless the scenario overrides it.
	 *
	 * @param kvDataType a label such as {@code fp16} or {@code int8}
	 * @return this profile
	 */
	public ModelProfile setKvDataType(String kvDataType) {
		this.kvDataType = kvDataType;
		return this;
	}

	/**
	 * Returns the bytes occupied by one cached key or value scalar, which lets a quantized cache
	 * be narrower than the weights. The cache estimate scales linearly with it.
	 *
	 * @return bytes per cached scalar, positive
	 */
	public double getKvBytesPerScalar() {
		return kvBytesPerScalar;
	}

	/**
	 * Sets the width of one cached scalar, independently of the weight width, so that a cache
	 * stored at a lower precision can be modelled.
	 *
	 * @param kvBytesPerScalar bytes per cached scalar, positive
	 * @return this profile
	 */
	public ModelProfile setKvBytesPerScalar(double kvBytesPerScalar) {
		this.kvBytesPerScalar = kvBytesPerScalar;
		return this;
	}

	/**
	 * Returns the quantization scheme applied to the weights, {@code none} when they are stored
	 * at the declared precision unchanged. The label is reported only; the effective widths are
	 * the byte fields.
	 *
	 * @return the scheme label, {@code none} by default
	 */
	public String getQuantization() {
		return quantization;
	}

	/**
	 * Sets the quantization label recorded in the artifacts.
	 *
	 * @param quantization a scheme label such as {@code none} or {@code int8}
	 * @return this profile
	 */
	public ModelProfile setQuantization(String quantization) {
		this.quantization = quantization;
		return this;
	}

	/**
	 * Returns whether the quantized-kernel arithmetic is modelled beyond the scalar byte width.
	 *
	 * @return {@code true} when the kernel runs at a compute precision and adds dequantization cost
	 */
	public boolean isQuantizedKernel() {
		return quantizedKernel;
	}

	/**
	 * Turns the quantized-kernel arithmetic on or off. When off, every figure this flag would use
	 * is ignored and the roofline behaves exactly as it did before the mechanism existed.
	 *
	 * @param quantizedKernel {@code true} to model a quantized kernel beyond byte width
	 * @return this profile
	 */
	public ModelProfile setQuantizedKernel(boolean quantizedKernel) {
		this.quantizedKernel = quantizedKernel;
		return this;
	}

	/**
	 * Returns the precision at which the matmul arithmetic runs, falling back to the storage
	 * {@link #precision} when none was declared or the kernel is off. The roofline divides by the
	 * device throughput at this precision, so a quantized model that computes at a wider format
	 * is charged the throughput of that format rather than of the stored one.
	 *
	 * @return the compute precision label
	 */
	public String effectiveComputePrecision() {
		if (!quantizedKernel || computePrecision == null || computePrecision.isEmpty())
			return precision;
		return computePrecision;
	}

	/**
	 * Returns the declared compute precision, which may be empty to mean the storage precision.
	 *
	 * @return the compute precision label, {@code ""} when unset
	 */
	public String getComputePrecision() {
		return computePrecision;
	}

	/**
	 * Sets the precision the quantized kernel computes at.
	 *
	 * @param computePrecision a label such as {@code bf16}, or empty to use the storage precision
	 * @return this profile
	 */
	public ModelProfile setComputePrecision(String computePrecision) {
		this.computePrecision = computePrecision == null ? "" : computePrecision.toLowerCase(java.util.Locale.ROOT);
		return this;
	}

	/**
	 * Returns the operations spent unpacking one weight scalar, charged once per weight per step.
	 *
	 * @return flops per scalar, {@code 0} when no dequantization is modelled
	 */
	public double getDequantFlopsPerScalar() {
		return dequantFlopsPerScalar;
	}

	/**
	 * Sets the dequantization arithmetic cost per weight scalar.
	 *
	 * @param dequantFlopsPerScalar operations per scalar, zero or more
	 * @return this profile
	 */
	public ModelProfile setDequantFlopsPerScalar(double dequantFlopsPerScalar) {
		this.dequantFlopsPerScalar = Math.max(0, dequantFlopsPerScalar);
		return this;
	}

	/**
	 * Returns the bytes one weight scalar occupies in the compute format.
	 *
	 * @return bytes per compute scalar, {@code 0} when no expansion is modelled
	 */
	public double getComputeBytesPerScalar() {
		return computeBytesPerScalar;
	}

	/**
	 * Sets the compute-format width of one weight scalar, used for the dequantization write traffic.
	 *
	 * @param computeBytesPerScalar bytes per compute scalar, zero or more
	 * @return this profile
	 */
	public ModelProfile setComputeBytesPerScalar(double computeBytesPerScalar) {
		this.computeBytesPerScalar = Math.max(0, computeBytesPerScalar);
		return this;
	}

	/**
	 * Returns the dequantization operations a step pays to unpack every weight, or zero when the
	 * quantized kernel is off. A step streams the whole weight tensor once, so it unpacks all of it.
	 *
	 * @return operations added to the step
	 */
	public double quantizationOverheadFlops() {
		return quantizedKernel ? parameterCount * dequantFlopsPerScalar : 0;
	}

	/**
	 * Returns the extra bytes a step moves to expand quantized weights into the compute format, or
	 * zero when the kernel is off or the compute width does not exceed the storage width.
	 *
	 * @return bytes added to the step's memory traffic
	 */
	public double quantizationExpansionBytes() {
		if (!quantizedKernel || computeBytesPerScalar <= bytesPerParameter)
			return 0;
		return parameterCount * (computeBytesPerScalar - bytesPerParameter);
	}

	/**
	 * Returns the bytes reserved per running sequence for activations and intermediate buffers,
	 * charged on top of the key-value cache. Zero means the memory model counts weights and cache
	 * only.
	 * <p>
	 * A prefill step is charged one such block and a decode step one per sequence in the batch.
	 *
	 * @return bytes per in-flight sequence, {@code 0} when activations are not modelled
	 */
	public double getActivationBytesPerSequence() {
		return activationBytesPerSequence;
	}

	/**
	 * Sets the activation reservation that the memory manager holds back for each sequence slot.
	 *
	 * @param activationBytesPerSequence bytes per running sequence, {@code 0} to ignore
	 *                                  activations
	 * @return this profile
	 */
	public ModelProfile setActivationBytesPerSequence(double activationBytesPerSequence) {
		this.activationBytesPerSequence = activationBytesPerSequence;
		return this;
	}

	/**
	 * Returns the share of the parameters that take part in the computation of one token, a
	 * dimensionless fraction in {@code (0, 1]}. A dense model keeps {@code 1.0}; a sparsely
	 * activated model would declare the share it uses, and {@link #activeParameters()} scales
	 * the arithmetic term by it.
	 *
	 * @return the active fraction, {@code 1.0} by default
	 */
	public double getActiveParameterFraction() {
		return activeParameterFraction;
	}

	/**
	 * Sets the share of the parameters a token activates, which scales the arithmetic term of
	 * the roofline.
	 *
	 * @param activeParameterFraction dimensionless fraction in {@code (0, 1]}
	 * @return this profile
	 */
	public ModelProfile setActiveParameterFraction(double activeParameterFraction) {
		this.activeParameterFraction = activeParameterFraction;
		return this;
	}

	/**
	 * Returns where the numbers of this profile come from. The reporting layer keeps the weakest
	 * provenance among the profiles a result used, so one synthetic dimension is enough to label
	 * the whole run synthetic.
	 *
	 * @return the provenance tag, {@link Provenance#SYNTHETIC} by default
	 */
	public Provenance getProvenance() {
		return provenance;
	}

	/**
	 * Sets the provenance tag that travels with every result computed from this profile.
	 *
	 * @param provenance where the numbers come from
	 * @return this profile
	 */
	public ModelProfile setProvenance(Provenance provenance) {
		this.provenance = provenance;
		return this;
	}

	/**
	 * Renders a one line summary with the identifier, parameter count, layer count, hidden width
	 * and precision, for logs and exception text.
	 *
	 * @return the summary line
	 */
	@Override
	public String toString() {
		return "ModelProfile[" + id + ", " + parameterCount + " params, " + layers + " layers, "
				+ hiddenDimension + " hidden, " + precision + "]";
	}
}
