# sparkprofile agent reader

Zero-dependency Python reader for raw `.sparkprofile` files downloaded from
spark. It is a local debugging aid for agents; `spark.lucko.me` remains the
normal interactive viewer.

The parser targets `spark.SamplerData` from spark commit
`557c199e57fa2085d235bbc3d301ba7b0b6633e2`. The authoritative schemas are:

- `spark-common/src/main/proto/spark/spark.proto`
- `spark-common/src/main/proto/spark/spark_sampler.proto`

Useful commands from the repository root:

```bash
python3 tools/sparkprofile/sparkprofile.py summary profile.sparkprofile
python3 tools/sparkprofile/sparkprofile.py summary profile.sparkprofile \
  --match 'GameRenderer|FactoryPanel|Window.updateDisplay'
python3 tools/sparkprofile/sparkprofile.py compare before.sparkprofile after.sparkprofile
python3 tools/sparkprofile/sparkprofile.py tree profile.sparkprofile \
  'Minecraft.runTick' --depth 3 --min-share 0.1
```

All summary and comparison data can be emitted as JSON with `--json`.

Important interpretation details:

- A Java execution profile periodically obtains `ThreadInfo` stack traces and
  adds the configured interval to the thread root and every node in that stack.
  Times are therefore estimated wall-clock residency on a sampled call stack,
  not exact method durations, call counts, or GPU timings.
- Unless the profile was started with `--ignore-sleeping`, blocking/native waits
  are sampled too. The protobuf metadata does not record that command flag.
- Async-profiler execution exports use the same tree shape but their values are
  sampled CPU-event durations rather than Java `ThreadInfo` polling intervals.
- Nodes are exported as a post-order flat array. `children_refs` are indexes into
  that array; they are not protobuf object ids.
- Method summaries group overload descriptors by class and method, and normalize
  process-specific hidden-class `/0x...` suffixes. `inclusive` then ignores an
  occurrence when that grouped method is already in its ancestry. This prevents
  synthetic bridge frames from making one method appear to exceed 100% and
  removes JVM-launch noise from comparisons. `raw_inclusive` in JSON retains
  the literal sum of all grouped occurrences. The `tree` command preserves the
  original nodes.
- Percentages are relative to the selected thread's sampled value. They are not
  percentages of GPU frame time and cannot predict FPS without knowing the
  frame's CPU/GPU/presentation critical path.

Run the dependency-free tests with:

```bash
python3 -m unittest discover -s tools/sparkprofile -p 'test_*.py'
```
