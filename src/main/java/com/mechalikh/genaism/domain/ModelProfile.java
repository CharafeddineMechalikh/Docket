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
 * {@link com.mechalikh.genaism.memory.KvCachePool}, not by changing this estimate.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
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

	public String getId() {
		return id;
	}

	public ModelProfile setId(String id) {
		this.id = id;
		return this;
	}

	public String getName() {
		return name;
	}

	public ModelProfile setName(String name) {
		this.name = name;
		return this;
	}

	public String getVersion() {
		return version;
	}

	public ModelProfile setVersion(String version) {
		this.version = version;
		return this;
	}

	public String getArchitecture() {
		return architecture;
	}

	public ModelProfile setArchitecture(String architecture) {
		this.architecture = architecture;
		return this;
	}

	public long getParameterCount() {
		return parameterCount;
	}

	public ModelProfile setParameterCount(long parameterCount) {
		this.parameterCount = parameterCount;
		return this;
	}

	public int getLayers() {
		return layers;
	}

	public ModelProfile setLayers(int layers) {
		this.layers = layers;
		return this;
	}

	public int getHiddenDimension() {
		return hiddenDimension;
	}

	public ModelProfile setHiddenDimension(int hiddenDimension) {
		this.hiddenDimension = hiddenDimension;
		return this;
	}

	public int getAttentionHeads() {
		return attentionHeads;
	}

	public ModelProfile setAttentionHeads(int attentionHeads) {
		this.attentionHeads = attentionHeads;
		return this;
	}

	public int getKvHeads() {
		return kvHeads;
	}

	public ModelProfile setKvHeads(int kvHeads) {
		this.kvHeads = kvHeads;
		return this;
	}

	public int getHeadDimension() {
		return headDimension;
	}

	public ModelProfile setHeadDimension(int headDimension) {
		this.headDimension = headDimension;
		return this;
	}

	public int getFeedForwardDimension() {
		return feedForwardDimension;
	}

	public ModelProfile setFeedForwardDimension(int feedForwardDimension) {
		this.feedForwardDimension = feedForwardDimension;
		return this;
	}

	public int getVocabularySize() {
		return vocabularySize;
	}

	public ModelProfile setVocabularySize(int vocabularySize) {
		this.vocabularySize = vocabularySize;
		return this;
	}

	public int getMaxContextTokens() {
		return maxContextTokens;
	}

	public ModelProfile setMaxContextTokens(int maxContextTokens) {
		this.maxContextTokens = maxContextTokens;
		return this;
	}

	public String getPrecision() {
		return precision;
	}

	public ModelProfile setPrecision(String precision) {
		this.precision = precision;
		return this;
	}

	public double getBytesPerParameter() {
		return bytesPerParameter;
	}

	public ModelProfile setBytesPerParameter(double bytesPerParameter) {
		this.bytesPerParameter = bytesPerParameter;
		return this;
	}

	public String getKvDataType() {
		return kvDataType;
	}

	public ModelProfile setKvDataType(String kvDataType) {
		this.kvDataType = kvDataType;
		return this;
	}

	public double getKvBytesPerScalar() {
		return kvBytesPerScalar;
	}

	public ModelProfile setKvBytesPerScalar(double kvBytesPerScalar) {
		this.kvBytesPerScalar = kvBytesPerScalar;
		return this;
	}

	public String getQuantization() {
		return quantization;
	}

	public ModelProfile setQuantization(String quantization) {
		this.quantization = quantization;
		return this;
	}

	public double getActivationBytesPerSequence() {
		return activationBytesPerSequence;
	}

	public ModelProfile setActivationBytesPerSequence(double activationBytesPerSequence) {
		this.activationBytesPerSequence = activationBytesPerSequence;
		return this;
	}

	public double getActiveParameterFraction() {
		return activeParameterFraction;
	}

	public ModelProfile setActiveParameterFraction(double activeParameterFraction) {
		this.activeParameterFraction = activeParameterFraction;
		return this;
	}

	public Provenance getProvenance() {
		return provenance;
	}

	public ModelProfile setProvenance(Provenance provenance) {
		this.provenance = provenance;
		return this;
	}

	@Override
	public String toString() {
		return "ModelProfile[" + id + ", " + parameterCount + " params, " + layers + " layers, "
				+ hiddenDimension + " hidden, " + precision + "]";
	}
}
