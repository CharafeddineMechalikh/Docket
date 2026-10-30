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
package com.mechalikh.docket.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The distribution of a metric measured per request.
 * <p>
 * A mean latency hides the tail, and the tail is what an SLO is written against, so every
 * duration metric of a run is reported through this summary: count, mean, the standard
 * deviations, P50, P90, P95, P99, the maximum, and a normal-approximation interval for the mean.
 * <p>
 * Percentiles use linear interpolation between the two closest ranks, the definition the common
 * numerical tools apply, and the interval is labelled as a normal approximation rather than a
 * bootstrap so that a reader does not take it for a resampling result.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 0.1
 */
public final class LatencyStats {

	/** Sorted values the summary is built from. */
	private final List<Double> values;
	/** Number of repetitions the experiment ran, reported next to the interval. */
	private final int repetitions;

	/**
	 * Builds a summary from raw values.
	 *
	 * @param rawValues   the observed values, in any order; NaN and infinite values are refused
	 * @param repetitions number of experiment repetitions behind the values, at least one
	 * @throws IllegalArgumentException when a value is not finite or the repetition count is
	 *                                  not positive
	 */
	public LatencyStats(List<Double> rawValues, int repetitions) {
		if (repetitions < 1)
			throw new IllegalArgumentException("A summary needs at least one repetition, found " + repetitions);
		List<Double> copy = new ArrayList<>(rawValues.size());
		for (Double value : rawValues) {
			if (value == null)
				throw new IllegalArgumentException("A summary cannot hold a missing value");
			if (Double.isNaN(value) || Double.isInfinite(value))
				throw new IllegalArgumentException("A summary cannot hold " + value
						+ "; a request that never produced the measurement is counted separately");
			copy.add(value);
		}
		Collections.sort(copy);
		this.values = Collections.unmodifiableList(copy);
		this.repetitions = repetitions;
	}

	/**
	 * Builds a summary from a single list of values, seen as one repetition.
	 *
	 * @param rawValues the observed values
	 * @return the summary
	 */
	public static LatencyStats of(List<Double> rawValues) {
		return new LatencyStats(rawValues, 1);
	}

	/**
	 * Builds a summary for a run with no observation.
	 *
	 * @param repetitions number of repetitions that produced nothing
	 * @return an empty summary
	 */
	public static LatencyStats empty(int repetitions) {
		return new LatencyStats(Collections.emptyList(), repetitions);
	}

	/**
	 * Returns how many values the summary holds.
	 *
	 * @return the count
	 */
	public int count() {
		return values.size();
	}

	/**
	 * Returns the sorted observations the summary was built from.
	 * <p>
	 * Exposed so that the summaries of several repetitions can be pooled into one: percentiles are
	 * not combinable, so averaging the per-repetition percentiles would be wrong, while collecting
	 * the rows underneath them is exact.
	 *
	 * @return the values, in ascending order, never modified
	 */
	public List<Double> values() {
		return values;
	}

	/**
	 * Returns whether nothing was observed.
	 *
	 * @return {@code true} when the summary is empty
	 */
	public boolean isEmpty() {
		return values.isEmpty();
	}

	/**
	 * Returns the sum of the values.
	 *
	 * @return the sum, zero when empty
	 */
	public double sum() {
		return values.stream().mapToDouble(Double::doubleValue).sum();
	}

	/**
	 * Returns the arithmetic mean.
	 *
	 * @return the mean, or {@code Double.NaN} when empty
	 */
	public double mean() {
		return values.isEmpty() ? Double.NaN : sum() / values.size();
	}

	/**
	 * Returns the sample standard deviation.
	 *
	 * @return the deviation, or {@code Double.NaN} when fewer than two values were observed
	 */
	public double stdDev() {
		int n = values.size();
		if (n < 2)
			return Double.NaN;
		double mean = mean();
		double acc = 0;
		for (double v : values)
			acc += (v - mean) * (v - mean);
		return Math.sqrt(acc / (n - 1));
	}

	/**
	 * Returns the standard error of the mean.
	 *
	 * @return the error, or {@code Double.NaN} when fewer than two values were observed
	 */
	public double standardError() {
		int n = values.size();
		return n < 2 ? Double.NaN : stdDev() / Math.sqrt(n);
	}

	/**
	 * Returns the value at the given quantile, interpolating between ranks.
	 *
	 * @param percentile a percentage between 0 and 100
	 * @return the quantile value, or {@code Double.NaN} when empty
	 * @throws IllegalArgumentException when the percentage is out of range
	 */
	public double percentile(double percentile) {
		if (percentile < 0 || percentile > 100)
			throw new IllegalArgumentException("A percentile is between 0 and 100, found " + percentile);
		int n = values.size();
		if (n == 0)
			return Double.NaN;
		if (n == 1)
			return values.get(0);
		double rank = percentile / 100.0 * (n - 1);
		int lower = (int) Math.floor(rank);
		int upper = Math.min(lower + 1, n - 1);
		double fraction = rank - lower;
		return values.get(lower) + fraction * (values.get(upper) - values.get(lower));
	}

	/**
	 * Returns the smallest observation.
	 * <p>
	 * The sentinel for an empty sample set is {@code Double.NaN}, the same convention the mean and
	 * the percentiles follow, so an artifact writer can detect a metric that was never measured
	 * without a separate count check.
	 *
	 * @return the minimum, or {@code Double.NaN} when empty
	 */
	public double min() {
		return values.isEmpty() ? Double.NaN : values.get(0);
	}

	/**
	 * Returns the largest observation.
	 * <p>
	 * As for the other statistics, an empty sample set yields {@code Double.NaN} rather than zero,
	 * so the absence of a measurement cannot be mistaken for a fast request.
	 *
	 * @return the maximum, or {@code Double.NaN} when empty
	 */
	public double max() {
		return values.isEmpty() ? Double.NaN : values.get(values.size() - 1);
	}

	/**
	 * Returns the repetitions behind the values.
	 *
	 * @return the repetition count
	 */
	public int repetitions() {
		return repetitions;
	}

	/**
	 * Returns the bounds of a ninety-five percent normal-approximation interval for the mean.
	 * <p>
	 * The interval is computed over the observed values, not over the means of the repetitions,
	 * so it reports the spread of the metric rather than the stability of the run. With a
	 * distribution as skewed as a decode latency, the interval of the mean is wide: that is the
	 * honest reading, not an artefact.
	 *
	 * @return a two-element array with the lower and upper bound, both NaN when the summary is
	 *         too small to support it
	 */
	public double[] meanConfidenceInterval95() {
		double se = standardError();
		if (Double.isNaN(se))
			return new double[] { Double.NaN, Double.NaN };
		double mean = mean();
		return new double[] { mean - 1.96 * se, mean + 1.96 * se };
	}

	/**
	 * Returns the summary as ordered name-value pairs, for the writer of the artifacts.
	 *
	 * @return a map with the count, mean, deviations, quartiles, percentiles and bounds
	 */
	public Map<String, Double> asMap() {
		Map<String, Double> map = new LinkedHashMap<>();
		map.put("count", (double) count());
		map.put("mean", mean());
		map.put("stddev", stdDev());
		map.put("min", min());
		map.put("p50", percentile(50));
		map.put("p90", percentile(90));
		map.put("p95", percentile(95));
		map.put("p99", percentile(99));
		map.put("max", max());
		return map;
	}

	/**
	 * Formats the summary for a log line.
	 *
	 * @param unit suffix appended to the numbers, for instance {@code s} or {@code tokens/s}
	 * @return a one-line description
	 */
	public String format(String unit) {
		if (values.isEmpty())
			return "n/a (no observation)";
		return String.format(Locale.ROOT, "n=%d mean=%.4f%s sd=%.4f p50=%.4f p90=%.4f p95=%.4f p99=%.4f max=%.4f",
				count(), mean(), unit, stdDev(), percentile(50), percentile(90), percentile(95), percentile(99), max());
	}

	/**
	 * Returns a one-line description of the summary.
	 *
	 * @return the count, the mean and P99, with the mean and P99 reported as NaN when empty
	 */
	@Override
	public String toString() {
		return "LatencyStats[n=" + count() + ", mean=" + mean() + ", p99=" + percentile(99) + "]";
	}
}
