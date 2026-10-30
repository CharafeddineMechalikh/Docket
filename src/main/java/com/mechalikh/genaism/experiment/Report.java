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
package com.mechalikh.genaism.experiment;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import com.mechalikh.genaism.config.Scenario;
import com.mechalikh.genaism.metrics.LatencyStats;

/**
 * Presents an experiment: a console block that states what was measured and how far it can be
 * trusted, then the machine-readable artifacts.
 * <p>
 * Two summaries of the same metric are reported side by side because they answer different
 * questions. The pooled summary collects every row of every repetition and answers "what did this
 * deployment do to a request"; its percentiles are the ones a capacity planner reads. The
 * repetition summary collects the per-repetition means and answers "how much does the answer move
 * when the seed changes"; a mean whose standard error is the size of the effect being claimed is
 * not an effect. Reporting only one of the two is how a single lucky run becomes a result.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public class Report {

	/** Experiment the results come from. */
	protected final Experiment experiment;
	/** Scenario the experiment ran. */
	protected final Scenario scenario;
	/** Results of the repetitions. */
	protected final List<RunResult> runs;
	/** Pooled summaries, keyed by metric. */
	protected final Map<String, LatencyStats> pooled = new LinkedHashMap<>();
	/** Summaries of the per-repetition means, keyed by metric. */
	protected final Map<String, LatencyStats> acrossRepetitions = new LinkedHashMap<>();

	/**
	 * Builds the report of a finished experiment.
	 *
	 * @param experiment the experiment, with at least one finished repetition
	 * @throws IllegalArgumentException when no repetition finished
	 */
	public Report(Experiment experiment) {
		if (experiment == null)
			throw new IllegalArgumentException("A report needs an experiment");
		if (experiment.getRuns().isEmpty())
			throw new IllegalArgumentException("The experiment \"" + experiment.getScenario().getName()
					+ "\" has no finished repetition to report");
		this.experiment = experiment;
		this.scenario = experiment.getScenario();
		this.runs = new ArrayList<>(experiment.getRuns());
		for (String metric : RunResult.METRICS) {
			List<Double> all = new ArrayList<>();
			List<Double> means = new ArrayList<>(runs.size());
			for (RunResult run : runs) {
				LatencyStats stat = run.stat(metric);
				means.add(stat.mean());
				all.addAll(stat.values());
			}
			pooled.put(metric, new LatencyStats(all, runs.size()));
			acrossRepetitions.put(metric, new LatencyStats(means, runs.size()));
		}
	}

	/**
	 * Writes the console block.
	 *
	 * @param out the stream to write to, for instance {@code System.out}
	 */
	public void printConsole(PrintStream out) {
		out.println("GenAIInferenceSim " + version() + " | experiment \"" + scenario.getName() + "\"");
		out.println(repeat('-', 78));
		out.println("simulated duration   : " + fmt(runs.get(0).getDurationSeconds(), 3) + " s per repetition");
		out.println("repetitions          : " + runs.size() + ", seed " + scenario.getSeed());
		out.println("model                : " + scenario.getModel().getId() + " ("
				+ fmt(scenario.getModel().getParameterCount() / 1e6, 1) + "M parameters, "
				+ scenario.getModel().getQuantization() + "), provenance "
				+ scenario.getModel().getProvenance().label());
		out.println("device               : " + scenario.getDevice().getId() + ", provenance "
				+ scenario.getDevice().getProvenance().label());
		out.println("replicas             : " + runs.get(0).getReplicaStats().size());
		out.println("cost model           : " + scenario.getCost().name() + ", " + scenario.getCost().provenance()
				.label() + (scenario.getCost().isCalibrated() ? ", calibrated on "
						+ scenario.getCost().calibrationSource() : ", NOT calibrated"));
		out.println("overall provenance   : " + scenario.overallProvenance());
		out.println("policies             : admission=" + scenario.getAdmission().describe() + ", routing="
				+ scenario.getRouting().name() + ", scheduling=" + scenario.getScheduling().name());
		out.println("workload             : " + scenario.getWorkload().getPattern() + ", model "
				+ scenario.getWorkload().getModelId());
		out.println(repeat('-', 78));
		for (String warning : runs.get(0).getWarnings())
			out.println("WARNING " + warning);

		out.println();
		out.println(String.format(Locale.ROOT, "%-6s %9s %9s %8s %9s %9s %9s %10s %8s %9s %8s", "rep", "offered",
				"completed", "success", "ttft p50", "ttft p99", "e2e p99", "tpot p99", "goodput", "tokens/s",
				"energy J"));
		for (RunResult run : runs)
			out.println(String.format(Locale.ROOT, "%-6d %9d %9d %8.4f %9.4f %9.4f %9.4f %9.5f %10.4f %8.1f %9.2f",
					run.getRepetition(), run.getArrivedRequests(), run.getCompletedRequests(),
					run.offeredSuccessRatio(), run.stat("ttft_s").percentile(50), run.stat("ttft_s").percentile(99),
					run.stat("e2e_s").percentile(99), run.stat("tpot_s").percentile(99), run.goodput(),
					run.tokensPerSecond(), run.getDeviceEnergyJoule()));

		out.println();
		out.println("Pooled over every row of every repetition, and over the per-repetition means:");
		out.println(String.format(Locale.ROOT, "%-11s %9s %9s %9s %9s %9s %9s %11s", "metric", "n", "mean", "p50",
				"p90", "p95", "p99", "max", "mean +- SE"));
		for (String metric : RunResult.METRICS) {
			LatencyStats pool = pooled.get(metric);
			LatencyStats spread = acrossRepetitions.get(metric);
			out.println(String.format(Locale.ROOT, "%-11s %9d %9s %9s %9s %9s %9s %9s %11s", metric, pool.count(),
					num(pool.mean(), 4), num(pool.percentile(50), 4), num(pool.percentile(90), 4),
					num(pool.percentile(95), 4), num(pool.percentile(99), 4), num(pool.max(), 4),
					spread.count() < 2 ? "n/a" : fmt(spread.mean(), 4) + " +- " + fmt(spread.standardError(), 4)));
		}
		out.println();
		out.println(String.format(Locale.ROOT,
				"mean over %d repetition(s): completed %.1f +- %.1f, goodput %.4f +- %.4f, tokens/s %.1f +- %.1f, "
						+ "utilisation %.3f +- %.3f",
				runs.size(), meanOf(RunResult::getCompletedRequests), spread(RunResult::getCompletedRequests),
				meanOf(RunResult::goodput), spread(RunResult::goodput), meanOf(RunResult::tokensPerSecond),
				spread(RunResult::tokensPerSecond), meanOf(RunResult::getUtilisation),
				spread(RunResult::getUtilisation)));

		out.println();
		out.println("Service level objectives, over the requests that stated the objective:");
		Map<String, int[]> slo = pooledServiceClass();
		out.println(String.format(Locale.ROOT, "%-14s %9s %9s %9s %9s %9s", "class", "offered", "ttft", "e2e",
				"tpot", "all"));
		for (Map.Entry<String, int[]> entry : slo.entrySet()) {
			int[] counters = entry.getValue();
			out.println(String.format(Locale.ROOT, "%-14s %9d %9s %9s %9s %9s", entry.getKey(), counters[0],
					attain(counters[3], counters[2]), attain(counters[5], counters[4]),
					attain(counters[7], counters[6]), attain(counters[9], counters[8])));
		}

		out.println();
		for (RunResult.ReplicaStats replica : runs.get(0).getReplicaStats())
			out.println(replica.describe());
		out.println();
		out.println("Artifacts written under " + scenario.getOutputDir() + "/" + scenario.getName());
	}

	/**
	 * Writes {@code summary.csv}, {@code slo.csv}, {@code config.txt} and the per-repetition rows.
	 *
	 * @throws IOException when an artifact cannot be written
	 */
	public void writeArtifacts() throws IOException {
		Path root = scenario.getOutputDir().resolve(scenario.getName());
		Files.createDirectories(root);
		writeLines(root.resolve("summary.csv"), summaryCsv());
		writeLines(root.resolve("slo.csv"), sloCsv());
		writeLines(root.resolve("config.txt"), configEcho());
		for (RunResult run : runs) {
			Path directory = root.resolve(String.format(Locale.ROOT, "rep%02d", run.getRepetition()));
			Files.createDirectories(directory);
			run.getRecorder().write(directory.resolve("requests.csv"), directory.resolve("requests.jsonl"),
					directory.resolve("samples.csv"));
		}
	}

	/**
	 * Returns the rows of {@code summary.csv}, header first.
	 *
	 * @return the lines
	 */
	public List<String> summaryCsv() {
		List<String> lines = new ArrayList<>();
		lines.add(String.join(",", RunResult.summaryHeader()));
		for (RunResult run : runs)
			lines.add(String.join(",", run.summaryRow(scenario.getName(), scenario.getCost().name())));
		return lines;
	}

	/**
	 * Returns the rows of {@code slo.csv}, one per service class and objective.
	 *
	 * @return the lines
	 */
	public List<String> sloCsv() {
		List<String> lines = new ArrayList<>();
		lines.add("experiment,repetition,service_class,objective,offered,completed,applicable,met,attainment");
		for (RunResult run : runs)
			for (RunResult.ServiceClassSlo slo : run.getSloByServiceClass().values())
				for (String objective : new String[] { "ttft", "e2e", "tpot", "all" })
					lines.add(String.join(",", scenario.getName(), Integer.toString(run.getRepetition()),
							slo.serviceClass, objective, Integer.toString(slo.offered), Integer.toString(slo.completed),
							Integer.toString(slo.applicable(objective)), Integer.toString(slo.met(objective)),
						 Double.toString(slo.attainment(objective))));
		return lines;
	}

	/**
	 * Returns the lines of {@code config.txt}: the scenario as resolved, plus the caveats, and the
	 * trace description when the workload replays a recorded file.
	 *
	 * @return the lines
	 */
	public List<String> configEcho() {
		List<String> lines = new ArrayList<>(scenario.describe());
		lines.add("");
		lines.add("# Provenance of the weakest input: " + scenario.overallProvenance());
		lines.add("# Cost model: " + scenario.getCost().name() + ", provenance "
				+ scenario.getCost().provenance().label() + ", calibrated="
				+ (scenario.getCost().isCalibrated() ? "yes" : "no") + ", source="
				+ scenario.getCost().calibrationSource());
		for (String warning : runs.get(0).getWarnings())
			lines.add("# WARNING " + warning);
		// The loader's trace line is the only place the source file and the arrival scaling appear.
		String trace = scenario.getSettings().raw("workload.traceDescription");
		if (trace != null)
			lines.add("# Trace: " + trace);
		for (Map.Entry<String, LatencyStats> entry : pooled.entrySet())
			lines.add("# Pooled " + entry.getKey() + ": n=" + entry.getValue().count() + " mean="
					+ fmt(entry.getValue().mean(), 6) + " p99=" + fmt(entry.getValue().percentile(99), 6));
		return lines;
	}

	/**
	 * Writes lines as UTF-8 with a trailing newline, which is what the readers of these artifacts
	 * expect.
	 *
	 * @param path  destination
	 * @param lines content
	 * @throws IOException when writing fails
	 */
	protected static void writeLines(Path path, List<String> lines) throws IOException {
		Files.write(path, lines, StandardCharsets.UTF_8);
	}

	/**
	 * Merges the service level counters of every repetition, per service class.
	 *
	 * @return class name to the counters {@code offered, completed, ttftApplicable, ttftMet,
	 *         e2eApplicable, e2eMet, tpotApplicable, tpotMet, allApplicable, allMet}
	 */
	protected Map<String, int[]> pooledServiceClass() {
		Map<String, int[]> merged = new LinkedHashMap<>();
		for (RunResult run : runs)
			for (RunResult.ServiceClassSlo slo : run.getSloByServiceClass().values()) {
				int[] counters = merged.computeIfAbsent(slo.serviceClass, key -> new int[10]);
				counters[0] += slo.offered;
				counters[1] += slo.completed;
				int[] applicable = { slo.applicable("ttft"), slo.applicable("e2e"), slo.applicable("tpot"),
						slo.applicable("all") };
				int[] met = { slo.met("ttft"), slo.met("e2e"), slo.met("tpot"), slo.met("all") };
				for (int i = 0; i < 4; i++) {
					counters[2 + i * 2] += applicable[i];
					counters[3 + i * 2] += met[i];
				}
			}
		return merged;
	}

	/**
	 * Formats an attainment the way the console shows a class that stated no objective.
	 *
	 * @param met        requests that met it
	 * @param applicable requests that stated it
	 * @return the ratio or a dash
	 */
	protected static String attain(int met, int applicable) {
		if (applicable == 0)
			return "-";
		return String.format(Locale.ROOT, "%.4f", (double) met / applicable);
	}

	/**
	 * Returns the mean of one aggregate over the repetitions.
	 *
	 * @param getter the aggregate
	 * @return the mean
	 */
	protected double meanOf(ToDoubleFunction<RunResult> getter) {
		double total = 0;
		for (RunResult run : runs)
			total += getter.applyAsDouble(run);
		return total / runs.size();
	}

	/**
	 * Returns the standard error of the mean of one aggregate over the repetitions.
	 * <p>
	 * Zero when a single repetition ran: there is no spread to estimate from one observation, and
	 * printing it as though the number were exact is the honest reading of a single run.
	 *
	 * @param getter the aggregate
	 * @return the standard error
	 */
	protected double spread(ToDoubleFunction<RunResult> getter) {
		double[] values = new double[runs.size()];
		for (int i = 0; i < runs.size(); i++)
			values[i] = getter.applyAsDouble(runs.get(i));
		return standardError(values);
	}

	/**
	 * Returns the standard error of the mean of a small sample: the deviation of the observations
	 * divided by the square root of their count.
	 * <p>
	 * With one observation there is no spread to estimate and the answer is zero, which the
	 * console prints as though the measurement were exact. That is the honest reading of a single
	 * repetition, and the reason the artifacts record how many ran.
	 *
	 * @param values the observations
	 * @return the standard error
	 */
	protected static double standardError(double[] values) {
		if (values.length < 2)
			return 0;
		double mean = 0;
		for (double value : values)
			mean += value;
		mean /= values.length;
		double sum = 0;
		for (double value : values)
			sum += (value - mean) * (value - mean);
		return Math.sqrt(sum / (values.length - 1)) / Math.sqrt(values.length);
	}

	/**
	 * Formats a number, or a dash when nothing was observed.
	 * <p>
	 * A metric with no observation has no percentile: a run in which every request was refused
	 * states no time to first token. Printing the {@code NaN} of such a run is accurate but reads
	 * as a broken table, so the console shows the absence instead.
	 *
	 * @param value     the number, possibly {@code NaN}
	 * @param precision decimals
	 * @return the text
	 */
	protected static String num(double value, int precision) {
		return Double.isNaN(value) ? "-" : fmt(value, precision);
	}

	/**
	 * Formats a number with a fixed number of decimals, in a stable locale.
	 *
	 * @param value     the number
	 * @param precision decimals
	 * @return the text
	 */
	protected static String fmt(double value, int precision) {
		return String.format(Locale.ROOT, "%." + precision + "f", value);
	}

	/**
	 * Returns a string of {@code count} copies of {@code c}, for the rules drawn in the console.
	 *
	 * @param c     the character
	 * @param count how many
	 * @return the text
	 */
	protected static String repeat(char c, int count) {
		char[] out = new char[count];
		Arrays.fill(out, c);
		return new String(out);
	}

	/**
	 * Returns the version stamped on the artifacts, read from the package so that it cannot drift
	 * from the jar that produced them.
	 *
	 * @return the version, or {@code "dev"} when running from a directory of classes
	 */
	protected static String version() {
		String version = Report.class.getPackage().getImplementationVersion();
		return version == null ? "development build" : version;
	}

	/**
	 * Returns the pooled summary of a metric.
	 *
	 * @param metric the metric name
	 * @return the summary over every row of every repetition
	 */
	public LatencyStats pooled(String metric) {
		return pooled.get(metric);
	}

	/**
	 * Returns the summary of the per-repetition means of a metric.
	 *
	 * @param metric the metric name
	 * @return the summary over the repetition means
	 */
	public LatencyStats acrossRepetitions(String metric) {
		return acrossRepetitions.get(metric);
	}

	/**
	 * Returns the experiment whose repetitions this report presents.
	 *
	 * @return the experiment
	 */
	public Experiment getExperiment() {
		return experiment;
	}
}
