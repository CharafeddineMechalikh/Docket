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
package com.mechalikh.docket.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mechalikh.docket.domain.TraceRecord;

/**
 * Reads a recorded workload trace from a CSV file so that a run can replay it.
 * <p>
 * The file is addressed by its header, not by column position: a trace exported by one tool often
 * has the columns in another order, and reading them by position would silently swap the prompt
 * length and the generated length, which is the one mistake a replay can hide. Four columns are
 * required, {@code arrival_time_s}, {@code model_id}, {@code input_tokens} and
 * {@code output_tokens}; the rest are optional and default to what a single-tenant, unstressed
 * request looks like.
 * <p>
 * Two transformations may be applied while reading, because a recorded production trace rarely
 * fits an experiment as is: a time scale, which compresses or stretches the arrival process to a
 * chosen intensity, and a row limit, which stops the replay after a number of requests. The
 * transformations are echoed into {@link ReadTrace#describe()} so that the artifact states what
 * was replayed rather than only what was read.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 0.1
 */
public final class TraceLoader {

	/** Column holding the arrival instant, in seconds from the start of the replay. */
	public static final String COL_ARRIVAL = "arrival_time_s";
	/** Column holding the model the request targeted. */
	public static final String COL_MODEL = "model_id";
	/** Column holding the prompt length. */
	public static final String COL_INPUT = "input_tokens";
	/** Column holding the observed generated length. */
	public static final String COL_OUTPUT = "output_tokens";
	/** Column holding the priority. */
	public static final String COL_PRIORITY = "priority";
	/** Column holding the service class. */
	public static final String COL_SERVICE_CLASS = "service_class";
	/** Column holding the session identifier. */
	public static final String COL_SESSION = "session_id";
	/** Column holding the time-to-first-token target, in seconds from the arrival. */
	public static final String COL_TTFT_TARGET = "ttft_target_s";
	/** Column holding the end-to-end target, in seconds from the arrival. */
	public static final String COL_E2E_TARGET = "e2e_target_s";
	/** Column holding the per-token target, in seconds. */
	public static final String COL_TPOT_TARGET = "tpot_target_s";
	/** Column holding the client abandonment delay, in seconds from the arrival. */
	public static final String COL_CANCELLATION = "cancellation_delay_s";

	/** Not instantiable: the loader is a set of static reads. */
	private TraceLoader() {
	}

	/**
	 * The rows read from a file, with the transformations that were applied.
	 */
	public static final class ReadTrace {

		/** Rows in arrival order, after the transformations. */
		private final List<TraceRecord> rows;
		/** Rows present in the file before the row limit was applied. */
		private final int fileRows;
		/** Factor the arrival instants were multiplied by. */
		private final double timeScale;
		/** Where the rows came from, for the artifacts. */
		private final String source;

		ReadTrace(List<TraceRecord> rows, int fileRows, double timeScale, String source) {
			this.rows = Collections.unmodifiableList(rows);
			this.fileRows = fileRows;
			this.timeScale = timeScale;
			this.source = source;
		}

		/**
		 * Returns the replayable rows.
		 *
		 * @return rows in arrival order
		 */
		public List<TraceRecord> rows() {
			return rows;
		}

		/**
		 * Returns a one-line description of what was read, naming the transformations.
		 *
		 * @return the description for the run header and the artifacts
		 */
		public String describe() {
			StringBuilder out = new StringBuilder(source).append(", ").append(fileRows).append(" rows read, ")
					.append(rows.size()).append(" replayed");
			if (timeScale != 1)
				out.append(String.format(Locale.ROOT, ", arrival instants scaled by %.4g", timeScale));
			return out.toString();
		}

		/**
		 * Returns the description of the read trace, so that a trace printed in a log states the same
		 * transformations as the run header.
		 *
		 * @return the description, identical to {@link #describe()}
		 */
		@Override
		public String toString() {
			return describe();
		}
	}

	/**
	 * Reads a trace file, keeping every row it holds.
	 *
	 * @param path      the CSV file, with a header line
	 * @param timeScale factor applied to every arrival instant, positive
	 * @return the rows, in arrival order
	 * @throws IOException              when the file cannot be read
	 * @throws IllegalArgumentException when the header lacks a required column or a row is invalid
	 */
	public static ReadTrace read(Path path, double timeScale) throws IOException {
		return read(path, timeScale, Integer.MAX_VALUE);
	}

	/**
	 * Reads a trace file and stops after a number of rows.
	 *
	 * @param path      the CSV file, with a header line
	 * @param timeScale factor applied to every arrival instant, positive
	 * @param maxRows   largest number of rows to replay, counting from the first
	 * @return the rows, in arrival order
	 * @throws IOException              when the file cannot be read
	 * @throws IllegalArgumentException when the header lacks a required column, a row is invalid,
	 *                                  or the scale is not positive
	 */
	public static ReadTrace read(Path path, double timeScale, int maxRows) throws IOException {
		if (timeScale <= 0 || Double.isInfinite(timeScale))
			throw new IllegalArgumentException("The trace time scale multiplies the arrival instants, so it must be "
					+ "positive and finite, found " + timeScale);
		List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		if (lines.isEmpty())
			throw new IllegalArgumentException("The trace file \"" + path + "\" is empty, it needs a header line");
		Map<String, Integer> columns = header(lines.get(0), path);
		for (String required : Arrays.asList(COL_ARRIVAL, COL_MODEL, COL_INPUT, COL_OUTPUT))
			if (!columns.containsKey(required))
				throw new IllegalArgumentException("The trace file \"" + path + "\" has no \"" + required
						+ "\" column; its header holds " + columns.keySet());

		List<TraceRecord> rows = new ArrayList<>();
		int fileRows = 0;
		for (int line = 1; line < lines.size(); line++) {
			String text = lines.get(line);
			if (text.trim().isEmpty() || text.startsWith("#"))
				continue;
			fileRows++;
			if (rows.size() >= maxRows)
				continue;
			List<String> fields = split(text);
			try {
				rows.add(toRecord(fields, columns, timeScale));
			} catch (RuntimeException e) {
				throw new IllegalArgumentException("Trace file \"" + path + "\", line " + (line + 1) + ": "
						+ e.getMessage(), e);
			}
		}
		if (rows.isEmpty())
			throw new IllegalArgumentException("The trace file \"" + path + "\" holds " + fileRows
					+ " data lines but none of them is replayable");
		return new ReadTrace(rows, fileRows, timeScale, "trace " + path.getFileName());
	}

	/**
	 * Builds rows from memory, for the tests and for the scenarios that embed a short trace.
	 *
	 * @param rows the rows, in any order
	 * @return the read trace
	 */
	public static ReadTrace of(List<TraceRecord> rows) {
		return new ReadTrace(rows, rows.size(), 1, "in-memory trace");
	}

	/**
	 * Turns one header line into a column name to index map, lower-cased and trimmed.
	 *
	 * @param header the first line of the file
	 * @param path   the file, for the error message
	 * @return the column positions
	 */
	private static Map<String, Integer> header(String header, Path path) {
		Map<String, Integer> columns = new LinkedHashMap<>();
		List<String> names = split(header);
		for (int i = 0; i < names.size(); i++) {
			String name = names.get(i).toLowerCase(Locale.ROOT);
			if (columns.containsKey(name))
				throw new IllegalArgumentException("The trace file \"" + path + "\" repeats the column \"" + name + "\"");
			columns.put(name, i);
		}
		return columns;
	}

	/**
	 * Builds one row from the fields of a line.
	 *
	 * @param fields    the split line
	 * @param columns   the header positions
	 * @param timeScale factor applied to the arrival instant
	 * @return the row
	 */
	private static TraceRecord toRecord(List<String> fields, Map<String, Integer> columns, double timeScale) {
		double arrival = Double.parseDouble(field(fields, columns, COL_ARRIVAL, "0")) * timeScale;
		String model = field(fields, columns, COL_MODEL, "default");
		int input = (int) Math.round(Double.parseDouble(field(fields, columns, COL_INPUT, "1")));
		int output = (int) Math.round(Double.parseDouble(field(fields, columns, COL_OUTPUT, "1")));
		int priority = (int) Math.round(Double.parseDouble(field(fields, columns, COL_PRIORITY, "0")));
		String serviceClass = field(fields, columns, COL_SERVICE_CLASS, "default");
		String session = field(fields, columns, COL_SESSION, "");
		double ttft = Double.parseDouble(field(fields, columns, COL_TTFT_TARGET, "0"));
		double e2e = Double.parseDouble(field(fields, columns, COL_E2E_TARGET, "0"));
		double tpot = Double.parseDouble(field(fields, columns, COL_TPOT_TARGET, "0"));
		double cancellation = Double.parseDouble(field(fields, columns, COL_CANCELLATION, "0"));
		return new TraceRecord(arrival, model, input, output, priority, serviceClass, session, ttft, e2e, tpot,
				cancellation);
	}

	/**
	 * Reads one field of a line, falling back to a default when the column is absent from the
	 * header or the cell is empty.
	 *
	 * @param fields     the split line
	 * @param columns    the header positions
	 * @param column     the column to read
	 * @param defaultValue what to return when the cell is missing
	 * @return the trimmed value
	 */
	private static String field(List<String> fields, Map<String, Integer> columns, String column, String defaultValue) {
		Integer index = columns.get(column);
		if (index == null || index >= fields.size())
			return defaultValue;
		String value = fields.get(index).trim();
		return value.isEmpty() ? defaultValue : value;
	}

	/**
	 * Splits one CSV line, honouring quoted fields so that a service class holding a comma survives
	 * the round trip through {@link com.mechalikh.docket.metrics.RequestRecorder}.
	 *
	 * @param line the raw line
	 * @return the fields, unquoted and in order
	 */
	private static List<String> split(String line) {
		List<String> fields = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (quoted) {
				if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
					current.append('"');
					i++;
				} else if (c == '"') {
					quoted = false;
				} else {
					current.append(c);
				}
			} else if (c == '"') {
				quoted = true;
			} else if (c == ',') {
				fields.add(current.toString());
				current.setLength(0);
			} else {
				current.append(c);
			}
		}
		fields.add(current.toString());
		return fields;
	}
}
