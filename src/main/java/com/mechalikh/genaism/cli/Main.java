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
package com.mechalikh.genaism.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.mechalikh.genaism.config.Scenario;
import com.mechalikh.genaism.config.Settings;
import com.mechalikh.genaism.experiment.Experiment;
import com.mechalikh.genaism.experiment.Report;

/**
 * Command line entry point: one scenario file in, one experiment and its artifacts out.
 * <p>
 * The loader of the scenario is strict, so a misspelled key is an error rather than a silently
 * ignored line; this class reports the unused keys before the run starts, because a scenario that
 * sets {@code replica.maxBatchSize} but is ignored is a scenario whose result means something else
 * than what its author intended.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public final class Main {

	/** Exit code of a successful run. */
	public static final int EXIT_OK = 0;
	/** Exit code of a usage error. */
	public static final int EXIT_USAGE = 2;
	/** Exit code of a scenario that does not load. */
	public static final int EXIT_SCENARIO = 3;
	/** Exit code of a run that failed or broke an invariant. */
	public static final int EXIT_RUN = 4;

	/** Stream the console report is written to. */
	protected final PrintStream out;
	/** Stream the diagnostics are written to. */
	protected final PrintStream err;

	/**
	 * Creates a runner writing to the given streams, so that the tests capture the output instead
	 * of reading it from the terminal.
	 *
	 * @param out  console report stream
	 * @param err  diagnostics stream
	 */
	public Main(PrintStream out, PrintStream err) {
		this.out = out;
		this.err = err;
	}

	/**
	 * Runs the simulator.
	 *
	 * @param args command line arguments
	 */
	public static void main(String[] args) {
		System.exit(new Main(System.out, System.err).run(args));
	}

	/**
	 * Parses the arguments, loads the scenario, runs the repetitions and writes the artifacts.
	 *
	 * @param args command line arguments
	 * @return the exit code, {@link #EXIT_OK} when the experiment finished and its artifacts were
	 *         written
	 */
	public int run(String[] args) {
		List<String> overrides = new ArrayList<>();
		String scenarioFile = null;
		Path outputDirectory = null;
		Long seed = null;
		Integer repetitions = null;
		boolean quiet = false;

		for (int i = 0; i < args.length; i++) {
			String argument = args[i];
			switch (argument) {
			case "-h":
			case "--help":
				printUsage(out);
				return EXIT_OK;
			case "--version":
				out.println("GenAIInferenceSim " + version());
				return EXIT_OK;
			case "-q":
			case "--quiet":
				quiet = true;
				break;
			case "-c":
			case "--scenario":
				if (++i >= args.length)
					return usage(argument + " needs a file");
				scenarioFile = args[i];
				break;
			case "-o":
			case "--output":
				if (++i >= args.length)
					return usage(argument + " needs a directory");
				outputDirectory = Paths.get(args[i]);
				break;
			case "-s":
			case "--seed":
				if (++i >= args.length)
					return usage(argument + " needs a number");
				try {
					seed = Long.parseLong(args[i].trim());
				} catch (NumberFormatException e) {
					return usage(argument + " expects an integer seed, found \"" + args[i] + "\"");
				}
				break;
			case "-r":
			case "--repetitions":
				if (++i >= args.length)
					return usage(argument + " needs a number");
				try {
					repetitions = Integer.parseInt(args[i].trim());
				} catch (NumberFormatException e) {
					return usage(argument + " expects an integer, found \"" + args[i] + "\"");
				}
				break;
			case "-D":
				if (++i >= args.length)
					return usage("-D needs a key=value pair");
				overrides.add(args[i]);
				break;
			default:
				if (argument.startsWith("-D") && argument.length() > 2) {
					overrides.add(argument.substring(2));
					break;
				}
				if (argument.startsWith("-"))
					return usage("unknown option \"" + argument + "\"");
				if (scenarioFile != null)
					return usage("two scenario files given: \"" + scenarioFile + "\" and \"" + argument + "\"");
				scenarioFile = argument;
				break;
			}
		}

		if (scenarioFile == null)
			return usage("no scenario file given");
		Path scenarioPath;
		try {
			scenarioPath = Paths.get(scenarioFile);
		} catch (InvalidPathException e) {
			return usage("\"" + scenarioFile + "\" is not a valid path");
		}
		if (!Files.isReadable(scenarioPath)) {
			err.println("error: cannot read the scenario file \"" + scenarioPath.toAbsolutePath() + "\"");
			return EXIT_USAGE;
		}
		if (seed != null)
			overrides.add("experiment.seed=" + seed);
		if (repetitions != null) {
			if (repetitions < 1)
				return usage("at least one repetition, found " + repetitions);
			overrides.add("experiment.repetitions=" + repetitions);
		}
		if (outputDirectory != null)
			// Applied as an override rather than a derived value: a scenario may already set
			// experiment.outputDir, and -o is documented to override it, not to collide with it.
			overrides.add("experiment.outputDir=" + outputDirectory);

		Scenario scenario;
		try {
			Settings settings = Settings.fromFile(scenarioPath, scenarioPath.getFileName().toString(), overrides);
			scenario = Scenario.load(settings, scenarioPath.toAbsolutePath().getParent());
			// Checked only after the scenario is built: a key counts as read when the loader touched
			// it, and asking before the load would report every key as unused.
			for (String unused : settings.unusedKeys())
				err.println("warning: the scenario sets \"" + unused + "\" but nothing reads it");
		} catch (IllegalArgumentException e) {
			err.println("error: the scenario does not load: " + e.getMessage());
			return EXIT_SCENARIO;
		} catch (IOException e) {
			err.println("error: the scenario cannot be read: " + e);
			return EXIT_SCENARIO;
		}

		Experiment experiment = new Experiment(scenario);
		try {
			experiment.run();
			Report report = new Report(experiment);
			report.writeArtifacts();
			if (!quiet)
				report.printConsole(out);
		} catch (IllegalArgumentException | IllegalStateException e) {
			err.println("error: the run failed: " + e.getMessage());
			return EXIT_RUN;
		} catch (IOException e) {
			err.println("error: the artifacts cannot be written: " + e);
			return EXIT_RUN;
		}
		return EXIT_OK;
	}

	/**
	 * Reports a usage error.
	 *
	 * @param message what is wrong
	 * @return {@link #EXIT_USAGE}
	 */
	protected int usage(String message) {
		err.println("error: " + message);
		printUsage(err);
		return EXIT_USAGE;
	}

	/**
	 * Writes the usage block.
	 *
	 * @param stream destination
	 */
	protected static void printUsage(PrintStream stream) {
		stream.println("usage: genaism [options] <scenario.properties>");
		stream.println();
		stream.println("Runs one scenario of the discrete-event inference simulator and writes");
		stream.println("summary.csv, slo.csv, config.txt and, per repetition, requests.csv,");
		stream.println("requests.jsonl and samples.csv under the experiment output directory.");
		stream.println();
		stream.println("  -c, --scenario FILE     the scenario to run");
		stream.println("  -o, --output DIR        override experiment.outputDir");
		stream.println("  -s, --seed N            override experiment.seed");
		stream.println("  -r, --repetitions N     override experiment.repetitions");
		stream.println("  -D key=value            set or override any scenario property");
		stream.println("  -q, --quiet             write the artifacts without the console report");
		stream.println("  -h, --help              show this message");
		stream.println("  --version               show the version");
		stream.println();
		stream.println("Example: genaism -r 5 scenarios/single-gpu-poisson.properties");
	}

	/**
	 * Returns the version stamped on the jar, or a marker when running from loose classes.
	 *
	 * @return the version text
	 */
	protected static String version() {
		String version = Main.class.getPackage().getImplementationVersion();
		return version == null ? "development build" : version;
	}
}
