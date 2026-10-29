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
package com.mechalikh.genaism.metrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.mechalikh.genaism.domain.InferenceRequest;

/**
 * Collects the per-request rows and the resource samples of one run, and writes them as CSV,
 * JSONL and a time-series file.
 * <p>
 * Recording is guarded: a request may be recorded once, and only when it reached a terminal
 * state. Both rules are the invariants the test suite checks, enforced where the rows are built
 * rather than only observed afterwards, because a duplicated completion or a row for a request
 * that is still running would silently corrupt every percentile of the run.
 * <p>
 * The run metadata, which names the configuration, the seed, the repetition and the provenance
 * of the cost model, is repeated on every row. A row read on its own has to say what produced
 * it.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public class RequestRecorder {

	/** Column names, in the order they are written. */
	public static final List<String> HEADER = Collections.unmodifiableList(Arrays.asList("experiment", "seed",
			"repetition", "cost_model", "cost_provenance", "cost_calibrated", "model_provenance", "device_provenance",
			"request_id", "model_id", "replica_id", "tenant_id", "service_class", "priority", "session_id",
			"arrival_time_s", "admitted_time_s", "queue_entry_time_s", "prefill_start_time_s", "first_token_time_s",
			"last_token_time_s", "terminal_time_s", "final_state", "outcome_reason", "input_tokens",
			"output_tokens_requested", "output_tokens_generated", "predicted_output_tokens", "cached_tokens",
			"allocated_blocks", "ttft_s", "tpot_s", "mean_itl_s", "max_itl_s", "e2e_s", "queueing_s", "prefill_s",
			"decode_s", "energy_j", "energy_wh", "preemptions", "ttft_target_s", "e2e_target_s", "tpot_target_s",
			"cancellation_time_s", "ttft_met", "e2e_met", "tpot_met", "all_slo_met", "decode_iterations_expected"));

	/** Columns whose value is text, and is therefore quoted in the JSON lines. */
	protected static final Set<String> TEXT_COLUMNS = Collections.unmodifiableSet(new HashSet<>(
			Arrays.asList("experiment", "cost_model", "cost_provenance", "cost_calibrated", "model_provenance",
					"device_provenance", "model_id", "tenant_id", "service_class", "session_id", "final_state",
					"outcome_reason")));

	/** Metadata values, in the order of the leading metadata columns. */
	protected final String[] metadata;
	/** Terminal requests, in completion order. */
	protected final List<InferenceRequest> requests = new ArrayList<>();
	/** Identifiers already recorded, to refuse a duplicate completion. */
	protected final Set<Long> recorded = new HashSet<>();
	/** Resource samples, in the order they were taken. */
	protected final List<ResourceSample> samples = new ArrayList<>();

	/**
	 * Creates a recorder for one run.
	 *
	 * @param metadata ordered metadata, for instance the experiment name, the seed, the
	 *                 repetition and the cost model; copied, and never null
	 */
	public RequestRecorder(Map<String, String> metadata) {
		if (metadata == null)
			throw new IllegalArgumentException("A recorder needs the metadata of the run it documents");
		List<String> values = new ArrayList<>(metadata.size());
		metadata.forEach((key, value) -> values.add(value));
		this.metadata = values.toArray(new String[0]);
		int declared = 8;
		if (this.metadata.length != declared)
			throw new IllegalArgumentException("The metadata block holds " + this.metadata.length + " values but the "
					+ "first " + declared + " columns of the header expect exactly that many: " + HEADER.subList(0,
							declared));
	}

	/**
	 * Records a request that reached a terminal state.
	 *
	 * @param request the finished request
	 * @throws IllegalStateException    when the request was already recorded or is not terminal
	 * @throws IllegalArgumentException when the request is null
	 */
	public void record(InferenceRequest request) {
		if (request == null)
			throw new IllegalArgumentException("A null request cannot be recorded");
		if (!request.getState().isTerminal())
			throw new IllegalStateException(
					request.label() + " is " + request.getState() + ", only a terminal request is recorded");
		if (!recorded.add(request.getRequestId()))
			throw new IllegalStateException(request.label() + " was already recorded; a duplicate completion would "
					+ "corrupt every percentile of the run");
		requests.add(request);
	}

	/**
	 * Adds one point of the resource time series.
	 *
	 * @param sample the snapshot
	 */
	public void addSample(ResourceSample sample) {
		if (sample == null)
			throw new IllegalArgumentException("A null sample cannot be recorded");
		samples.add(sample);
	}

	/**
	 * Returns the recorded requests, in completion order.
	 *
	 * @return an immutable view
	 */
	public List<InferenceRequest> requests() {
		return Collections.unmodifiableList(requests);
	}

	/**
	 * Returns the recorded samples.
	 *
	 * @return an immutable view
	 */
	public List<ResourceSample> samples() {
		return Collections.unmodifiableList(samples);
	}

	/**
	 * Returns how many requests were recorded.
	 *
	 * @return the row count
	 */
	public int size() {
		return requests.size();
	}

	/**
	 * Formats one row of the artifacts.
	 *
	 * @param request the recorded request
	 * @return the comma-separated values, in {@link #HEADER} order
	 */
	public String toCsvRow(InferenceRequest request) {
		return String.join(",", rowValues(request));
	}

	/**
	 * Returns every field of a row, metadata first, in {@link #HEADER} order.
	 *
	 * @param request the recorded request
	 * @return the fields as strings
	 */
	public List<String> rowValues(InferenceRequest request) {
		List<String> values = new ArrayList<>(HEADER.size());
		for (String datum : metadata)
			values.add(csv(datum));
		values.add(Long.toString(request.getRequestId()));
		values.add(csv(request.getModelId()));
		values.add(Integer.toString(request.getReplicaId()));
		values.add(csv(request.getTenantId()));
		values.add(csv(request.getServiceClass()));
		values.add(Integer.toString(request.getPriority()));
		values.add(csv(request.getSessionId()));
		values.add(seconds(request.getArrivalTime()));
		values.add(seconds(request.getAdmittedTime()));
		values.add(seconds(request.getQueueEntryTime()));
		values.add(seconds(request.getPrefillStartTime()));
		values.add(seconds(request.getFirstTokenTime()));
		values.add(seconds(request.getLastTokenTime()));
		values.add(seconds(request.getTerminalTime()));
		values.add(request.getState().name());
		values.add(csv(request.getOutcomeReason()));
		values.add(Integer.toString(request.getInputTokens()));
		values.add(Integer.toString(request.getOutputTokens()));
		values.add(Integer.toString(request.getGeneratedTokens()));
		values.add(Integer.toString(request.getPredictedOutputTokens()));
		values.add(Integer.toString(request.getCachedTokens()));
		values.add(Integer.toString(request.getAllocatedBlocks()));
		values.add(seconds(request.ttftSeconds()));
		values.add(seconds(request.tpotSeconds()));
		values.add(seconds(request.meanInterTokenLatency()));
		values.add(seconds(request.getMaxInterTokenLatency()));
		values.add(seconds(request.endToEndSeconds()));
		values.add(seconds(request.getQueueingSeconds()));
		values.add(seconds(request.getPrefillSeconds()));
		values.add(seconds(request.getDecodeSeconds()));
		values.add(sci(request.getEnergyJoule()));
		values.add(sci(request.getEnergyJoule() / 3600.0));
		values.add(Integer.toString(request.getPreemptionCount()));
		values.add(seconds(relative(request.getTtftDeadline(), request.getArrivalTime())));
		values.add(seconds(relative(request.getEndToEndDeadline(), request.getArrivalTime())));
		values.add(seconds(request.getTpotTarget()));
		values.add(seconds(request.getCancellationTime()));
		values.add(SloChecks.field(SloChecks.ttftMet(request)));
		values.add(SloChecks.field(SloChecks.endToEndMet(request)));
		values.add(SloChecks.field(SloChecks.tpotMet(request)));
		values.add(SloChecks.field(SloChecks.allMet(request)));
		values.add(Integer.toString(Math.max(0, request.getGeneratedTokens() - 1)));
		return values;
	}

	/**
	 * Writes the artifacts of the run.
	 *
	 * @param requestsCsvPath  target file for the rows, or {@code null} to skip
	 * @param requestsJsonPath target file for the JSON lines, or {@code null} to skip
	 * @param samplesCsvPath   target file for the resource time series, or {@code null} to skip
	 * @throws IOException when a file cannot be written
	 */
	public void write(Path requestsCsvPath, Path requestsJsonPath, Path samplesCsvPath) throws IOException {
		if (requestsCsvPath != null) {
			List<String> lines = new ArrayList<>(requests.size() + 1);
			lines.add(String.join(",", HEADER));
			for (InferenceRequest request : requests)
				lines.add(toCsvRow(request));
			writeLines(requestsCsvPath, lines);
		}
		if (requestsJsonPath != null) {
			List<String> lines = new ArrayList<>(requests.size());
			for (InferenceRequest request : requests)
				lines.add(toJsonRow(request));
			writeLines(requestsJsonPath, lines);
		}
		if (samplesCsvPath != null) {
			List<String> lines = new ArrayList<>(samples.size() + 1);
			lines.add("time_s,replica_id,running_sequences,waiting_requests,used_blocks,total_blocks,"
					+ "reserved_tokens,cache_occupancy,energy_j,executing");
			for (ResourceSample sample : samples)
				lines.add(String.format(Locale.ROOT, "%.6f,%d,%d,%d,%d,%d,%d,%.4f,%.6f,%s", sample.time(),
						sample.replicaId(), sample.runningSequences(), sample.waitingRequests(), sample.usedBlocks(),
						sample.totalBlocks(), sample.reservedTokens(), sample.cacheOccupancy(), sample.energyJoule(),
						sample.isExecuting()));
			writeLines(samplesCsvPath, lines);
		}
	}

	/**
	 * Creates the JSON line of a request, with the same field names as the CSV header.
	 *
	 * @param request the recorded request
	 * @return one JSON object, on a single line
	 */
	public String toJsonRow(InferenceRequest request) {
		List<String> values = rowValues(request);
		StringBuilder json = new StringBuilder(1024);
		json.append('{');
		for (int i = 0; i < HEADER.size(); i++) {
			if (i > 0)
				json.append(", ");
			String name = HEADER.get(i);
			String value = values.get(i);
			json.append('"').append(name).append("\": ");
			if (TEXT_COLUMNS.contains(name))
				json.append('"').append(jsonEscape(value)).append('"');
			else
				json.append(value.isEmpty() ? "null" : value);
		}
		return json.append('}').toString();
	}

	/**
	 * Writes lines to a file, creating the parent directories.
	 *
	 * @param path  the target
	 * @param lines the content
	 * @throws IOException when the file cannot be written
	 */
	protected static void writeLines(Path path, List<String> lines) throws IOException {
		Path parent = path.toAbsolutePath().getParent();
		if (parent != null)
			Files.createDirectories(parent);
		Files.write(path, lines, StandardCharsets.UTF_8);
	}

	/**
	 * Formats a duration, leaving the field empty when it was never measured.
	 *
	 * @param seconds the value
	 * @return the formatted number, or an empty string
	 */
	protected static String seconds(double seconds) {
		if (Double.isNaN(seconds) || Double.isInfinite(seconds))
			return "";
		return String.format(Locale.ROOT, "%.9f", seconds);
	}

	/**
	 * Formats an energy with enough digits for a small per-request share.
	 *
	 * @param value the value
	 * @return the formatted number
	 */
	protected static String sci(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value))
			return "";
		return String.format(Locale.ROOT, "%.9g", value);
	}

	/**
	 * Returns the target of a deadline, measured from the arrival, or an empty field when the
	 * request states none.
	 *
	 * @param deadline    absolute deadline, zero for none
	 * @param arrivalTime arrival of the request
	 * @return the relative target, or NaN when there is none
	 */
	protected static double relative(double deadline, double arrivalTime) {
		return deadline <= 0 ? Double.NaN : deadline - arrivalTime;
	}

	/**
	 * Escapes a value for the CSV format.
	 *
	 * @param value the raw value
	 * @return the value, quoted when it holds a comma or a quote
	 */
	protected static String csv(String value) {
		if (value == null)
			return "";
		if (value.indexOf(',') < 0 && value.indexOf('"') < 0)
			return value;
		return '"' + value.replace("\"", "\"\"") + '"';
	}

	/**
	 * Escapes a value for a JSON string.
	 *
	 * @param value the raw value
	 * @return the escaped content, without the surrounding quotes
	 */
	protected static String jsonEscape(String value) {
		if (value == null)
			return "";
		StringBuilder out = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
			case '"':
				out.append("\\\"");
				break;
			case '\\':
				out.append("\\\\");
				break;
			case '\n':
				out.append("\\n");
				break;
			case '\r':
				out.append("\\r");
				break;
			case '\t':
				out.append("\\t");
				break;
			default:
				if (c < 0x20)
					out.append(String.format("\\u%04x", (int) c));
				else
					out.append(c);
			}
		}
		return out.toString();
	}

	@Override
	public String toString() {
		return "RequestRecorder[" + requests.size() + " requests, " + samples.size() + " samples]";
	}
}
