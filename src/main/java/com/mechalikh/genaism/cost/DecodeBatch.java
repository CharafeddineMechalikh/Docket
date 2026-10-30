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

/**
 * The shape of the decode batch one iteration will process.
 * <p>
 * The execution cost of a decode step depends on the batch size and on the total context the
 * key-value caches hold, so the server passes both as a value object instead of exposing its
 * internal request list to the cost model.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public final class DecodeBatch {

	/** Number of sequences that generate a token in the iteration. */
	private final int sequences;
	/** Sum of the context lengths of those sequences, in tokens. */
	private final long totalContextTokens;
	/** Largest context length inside the batch, in tokens. */
	private final int maxContextTokens;

	/**
	 * Describes a batch from its size and its aggregated context.
	 *
	 * @param sequences          number of running sequences, at least one
	 * @param totalContextTokens sum of their context lengths, at least {@code sequences}
	 * @param maxContextTokens   largest single context, at least one
	 * @throws IllegalArgumentException when a value is out of range or inconsistent
	 */
	public DecodeBatch(int sequences, long totalContextTokens, int maxContextTokens) {
		if (sequences < 1)
			throw new IllegalArgumentException("A decode batch needs at least one sequence, found " + sequences);
		if (totalContextTokens < sequences)
			throw new IllegalArgumentException("A batch of " + sequences + " sequences cannot hold a total context of "
					+ totalContextTokens + " tokens, every sequence caches at least its first token");
		if (maxContextTokens < 1)
			throw new IllegalArgumentException("The largest context of a batch must be at least one token");
		if (maxContextTokens > totalContextTokens)
			throw new IllegalArgumentException("The largest context (" + maxContextTokens
					+ " tokens) cannot exceed the total context (" + totalContextTokens + " tokens)");
		this.sequences = sequences;
		this.totalContextTokens = totalContextTokens;
		this.maxContextTokens = maxContextTokens;
	}

	/**
	 * Creates a batch where every sequence holds the same context length.
	 *
	 * @param sequences     number of running sequences, at least one
	 * @param contextEach   context length of each sequence, in tokens
	 * @return the batch description
	 */
	public static DecodeBatch uniform(int sequences, int contextEach) {
		return new DecodeBatch(sequences, (long) sequences * contextEach, contextEach);
	}

	/**
	 * Returns the number of sequences that generate a token in this step.
	 * <p>
	 * This is the batch width of the iteration: every sequence contributes one token and therefore
	 * one cache write, which is why the batch size drives the projection term of the decode cost.
	 *
	 * @return sequences in the batch, at least one
	 */
	public int sequences() {
		return sequences;
	}

	/**
	 * Returns the sum of the context lengths of the sequences in this step.
	 * <p>
	 * Attention reads the whole key-value cache of the batch, so this total is what the memory
	 * term of a decode step charges, and it is what makes decode turn memory-bound as the
	 * contexts grow.
	 *
	 * @return the total cached tokens, at least {@link #sequences()}
	 */
	public long totalContextTokens() {
		return totalContextTokens;
	}

	/**
	 * Returns the context length of the longest sequence in this step.
	 * <p>
	 * A step finishes only when its slowest sequence finishes, so this figure bounds the latency
	 * of the iteration even though the arithmetic is charged on the total: a batch that carries
	 * one long conversation pays for that conversation in the step that advances it.
	 *
	 * @return the largest context in tokens, at least one and at most
	 *         {@link #totalContextTokens()}
	 */
	public int maxContextTokens() {
		return maxContextTokens;
	}

	/**
	 * Returns the mean context length of the sequences in the batch.
	 *
	 * @return tokens per sequence
	 */
	public double meanContextTokens() {
		return (double) totalContextTokens / sequences;
	}

	/**
	 * Returns a one-line description of the batch.
	 *
	 * @return the batch size, the total cached tokens and the largest context
	 */
	@Override
	public String toString() {
		return "DecodeBatch[" + sequences + " sequences, " + totalContextTokens + " cached tokens, max "
				+ maxContextTokens + "]";
	}
}
