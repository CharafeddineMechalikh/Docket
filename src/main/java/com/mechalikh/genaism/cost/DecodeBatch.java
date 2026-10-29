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

	public int sequences() {
		return sequences;
	}

	public long totalContextTokens() {
		return totalContextTokens;
	}

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

	@Override
	public String toString() {
		return "DecodeBatch[" + sequences + " sequences, " + totalContextTokens + " cached tokens, max "
				+ maxContextTokens + "]";
	}
}
