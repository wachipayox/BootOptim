# BootOptim — agent entry point

BootOptim belongs to **wbombo**, the private hub for the Wachiland Elite
startup/optimization projects: https://github.com/wachipayox/wbombo
(local workspace: this repo lives in `wbombo/repos/BootOptim`, so the hub is
`../../`). Read wbombo `AGENTS.md`, `PROJECTS.md` and `proyectos/bootoptim.md`
first. Subagent coordination lives in wbombo `coordination/`; do not use a local
`.agent-coordination/` directory.

Before proposing, profiling or changing code, read
[`docs/agent-guide.md`](docs/agent-guide.md) — the full BootOptim rules
(decision policy, validation hierarchy, measurement-origin invariants, hosted
exact-pack CI, historical traps). It is mandatory.

Non-negotiables, in short:

- `agent/integration-current` is the authority; never modify `main` (it is the
  empty MDK template). Work branches start from the latest integration HEAD and
  target it with a PR.
- Check open **and closed** PRs and `docs/research/` for the subsystem before
  starting; the integration source tree decides what is actually shipped.
- Every bounded investigation ends in integration or a documented rejection on
  integration (evidence + reopening criteria).
- State measurement origin, start marker and endpoint for every timing; never
  compare different origins or sum overlapping listener durations.
- The distributable JAR is `bootstrap/build/libs/`, not root `build/libs`.
- This repository is public: nothing private (server addresses, tokens,
  relay prompts) goes here.
