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
package com.mechalikh.docket.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The ordered levels a deployment is willing to serve a request at, expressed as shares of the
 * reasoning work the request itself asks for.
 * <p>
 * A ladder is the serving-side statement of "how far may this be degraded". Each level is a share of
 * the generated length the workload asked for, so the top of a ladder is always {@code 1.0}, full
 * fidelity, and the levels below it are shorter reasoning traces: a chain of thought cut to a half,
 * a quarter, a tenth. The share is not the cost and not the quality. It is the budget, and what the
 * budget is worth in seconds, in cache blocks and in joules is decided by the execution cost model of
 * the deployment, which is the difference between a ladder and a multiplier applied to an
 * instruction count.
 * <p>
 * The floor matters as much as the levels. A share of a short answer can fall below one token, and a
 * deployment that granted zero tokens would be refusing the request while recording it as served, so
 * every budget is rounded up to {@code minTokens} and then capped by the length the request asked
 * for: the last level of a ladder can still be a full answer, when the answer was already short.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 1.1
 */
public final class FidelityLadder {

	/** Shares of the asked length, from the most to the least work, {@code 1.0} first. */
	private final List<Double> levels;
	/** Smallest budget the ladder may grant, in tokens. */
	private final int minTokens;

	/**
	 * Describes a ladder.
	 *
	 * @param levels    the shares, from the highest fidelity down, each in {@code (0, 1]}, not empty
	 * @param minTokens the floor on any granted budget, at least one token
	 * @throws IllegalArgumentException when a share is out of range, the list is empty, or the floor
	 *                                  is below one token
	 */
	public FidelityLadder(List<Double> levels, int minTokens) {
		if (levels == null || levels.isEmpty())
			throw new IllegalArgumentException("A fidelity ladder needs at least one level");
		if (minTokens < 1)
			throw new IllegalArgumentException("A fidelity ladder grants at least one token, found a floor of "
					+ minTokens);
		List<Double> copy = new ArrayList<>(levels.size());
		for (Double level : levels) {
			if (level == null || Double.isNaN(level) || level <= 0 || level > 1)
				throw new IllegalArgumentException("A fidelity level is a share of the asked length in (0, 1], found "
						+ level);
			copy.add(level.doubleValue());
		}
		this.levels = Collections.unmodifiableList(copy);
		this.minTokens = minTokens;
	}

	/**
	 * Returns the levels, highest fidelity first.
	 *
	 * @return an unmodifiable list
	 */
	public List<Double> levels() {
		return levels;
	}

	/**
	 * Returns the floor on any granted budget.
	 *
	 * @return tokens, at least one
	 */
	public int minTokens() {
		return minTokens;
	}

	/**
	 * Returns the number of levels.
	 *
	 * @return levels in the ladder, at least one
	 */
	public int size() {
		return levels.size();
	}

	/**
	 * Returns the share of one level.
	 *
	 * @param index level position, counting from the top of the ladder
	 * @return the share
	 * @throws IndexOutOfBoundsException when the index is not a level of this ladder
	 */
	public double level(int index) {
		return levels.get(index);
	}

	/**
	 * Returns the reasoning budget one level grants a request of the asked length.
	 * <p>
	 * The share is applied to the length the workload asked for, rounded up so that a level never
	 * vanishes into zero tokens, lifted to the floor of the ladder, and finally capped by the ask: a
	 * level of one grants exactly what was requested, and a level below one can grant nothing more
	 * than the request could have produced anyway.
	 *
	 * @param index          level position, counting from the top of the ladder
	 * @param askedTokens    the length the workload asked the model to generate, at least one
	 * @return the budget in tokens, between one and {@code askedTokens}
	 */
	public int budgetAt(int index, int askedTokens) {
		if (askedTokens < 1)
			throw new IllegalArgumentException("A ladder budgets a request of at least one token, asked for "
					+ askedTokens);
		double share = levels.get(index);
		double scaled = share * askedTokens;
		// Rounding up is what keeps a level like 0.1 of a 12-token answer from becoming a 1-token
		// answer: the floor is the deployment's own words for "no shorter than this".
		long grown = (long) Math.ceil(scaled);
		long budget = Math.max(minTokens, grown);
		return (int) Math.min(budget, askedTokens);
	}

	/**
	 * Returns the index of the most demanding level, the one that grants everything.
	 *
	 * @return zero, the top of the ladder
	 */
	public int fullFidelityIndex() {
		return 0;
	}

	/**
	 * Returns the levels as the text a scenario would have written.
	 *
	 * @return comma-separated shares
	 */
	public String describeLevels() {
		StringBuilder out = new StringBuilder();
		for (double level : levels)
			out.append(out.length() == 0 ? "" : ",").append(String.format(Locale.ROOT, "%g", level));
		return out.toString();
	}

	/**
	 * Returns a one-line description naming the levels and the floor.
	 *
	 * @return the description
	 */
	@Override
	public String toString() {
		return "FidelityLadder[" + describeLevels() + ", floor " + minTokens + " token(s)]";
	}
}
