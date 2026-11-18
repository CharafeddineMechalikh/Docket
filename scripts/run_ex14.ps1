# Re-runs the Example 14 fidelity/placement ablation (20 cells, 5 seeds each).
#
# Usage, from anywhere:
#   powershell -ExecutionPolicy Bypass -File scripts/run_ex14.ps1
#
# The example's policy classes are compiled into target/ex14-classes because the
# jar alone does not carry them; `mvn package` builds the core simulator.

$ErrorActionPreference = "Continue"
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo
$cp = "target\docket-1.0.1.jar;target\ex14-classes"
$arms = @("ex14-naive", "ex14-fidelity", "ex14-static-edge", "ex14-edge-nofid", "ex14-joint-nofid", "ex14-joint")
$levels = @("1.0", "1.5", "2.0", "3.0")
$reps = 20
$sw = [Diagnostics.Stopwatch]::StartNew()
foreach ($level in $levels) {
    foreach ($arm in $arms) {
        $name = "$arm-ov$level"
        $out = "target\ex14sweep\$name"
        $trace = "../data/agentic-overhead-$level.csv"
        & java -cp $cp com.mechalikh.docket.cli.Main -q -r $reps -o $out `
            -D "experiment.name=$name" -D "workload.traceFile=$trace" `
            "examples/14-fidelity-aware-edge-routing/scenarios/$arm.properties" 2>$null
        if ($LASTEXITCODE -ne 0) { Write-Output "FAILED $name" }
    }
}
Write-Output "DONE t=$([math]::Round($sw.Elapsed.TotalMinutes,2))min"
