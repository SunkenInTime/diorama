---
name: boat-game-test
description: Run Diorama's client game tests on boat.dev VMs. The default for every game test that doesn't need this PC's GPU or desktop (does it pass, do the screenshots show the right thing), on one version or several.
---

# Game tests on boat.dev

A boat.dev VM has no GPU: Minecraft renders with OpenGL on the CPU, through Mesa. Tests pass the same way and screenshots show the same scenes, only slower than on Dara's PC. That makes it the place for functional checks and the wrong place for anything about the GPU:

- **boat.dev**: a feature works, a screenshot shows what it should, a fix holds on other versions.
- **This PC, under the Minecraft lock**: fps, frame time and bake-speed measurements, GPU bake (`DIORAMA_GPU_BAKE`), pixel-for-pixel comparisons against an RTX image, modded installs, and anything driven on the desktop. A `--shared` run here has to say why with `--needs-pc '<why>'`.

## Run

1. Pick the test sections your change touches: the names in `selectedTests()` in `src/gametest/java/dev/diorama/test/DioramaClientTest.java` (`tour`, `waypoints`, `multiplayer`, ...). Run the sections you need, not `all`.
2. Run from your checkout. It pushes your working tree, uncommitted changes included, to one VM per node, all at once:

   ```
   python .claude/skills/boat-game-test/boat_run.py <node[,node...]> --tests <section,section>
   ```

   `<node>` is a folder in `versions/`: `26.3`, `1.21.11`, `26.3-neoforge`, ... Pass game environment variables with `--env DIORAMA_GIF=1`. Run it in the foreground with a 2-hour timeout (`timeout: 7200000`): the tests stop at 90 min and the script's own work at 105, which leaves the rest for deleting the VMs. Every VM also has a 2-hour lifetime on boat.dev's side, which catches what a killed script leaves.
3. Open the screenshots it lists (`build/boat-runs/<time>-<node>/screenshots/`) and look at them. A pass with the wrong picture is still a failure. On a failure the script prints the assertion; `gradle.log` and `latest.log` in the same folder have the rest. The `time:` line says where the run's minutes went (VM, push, Gradle, game start, world, map warm-up, tests).
4. Read the end of the output. Every VM of the run is named with the prefix the script prints first (`diorama-test-<random>-`); at the end it deletes every VM with that prefix and says which are left. A `!!` line names one that needs you, with the command. `keeping VM` means the tests ran but their results couldn't be fetched, so the VM is stopped with them on it; fetch and delete it. Leave every VM that isn't yours alone: they belong to other agents.

**Reruns reuse a warm VM.** A run of up to 8 nodes leaves each VM running for 30 minutes (`stays warm` in the output), named `diorama-warm-<checkout>-<snapshot>-<node>`, where `<checkout>` stands for this worktree. Running again from the same worktree within those minutes reuses it: no start, no new VM, the build and the snapshot's files already there, so the first test begins about a minute sooner. When the time runs out boat.dev stops it, and the next run deletes it. `--warm-minutes N` changes the time, `--warm-minutes 0` deletes the VMs at the end as before; use 0 for your last run, so nothing idles on the account. Warm VMs bill as running VMs, but a start past the day's limit costs more than an idle half hour. Run your reruns from the same worktree, and pick up a warm VM rather than making a new checkout for each try.

Done when every node exits 0, you've seen the screenshots for the sections you changed, and `boat list --all` (plain `boat list` hides stopped VMs) shows none of your VMs, warm ones included.

## Size and cost

The plan (`box_20`) includes 555 VM-hours a month, counted at the size's rate: small 0.5x, default 1x, large 2x. Past them, runs are paid from a wallet that holds $0, so they stop. Measured on 2026-10-28 from the warm snapshot, one run each (times on boat.dev vary by a minute between runs):

| `screens` section | default (4 vCPU) | large (8 vCPU) |
|---|---|---|
| 26.3: game / whole run | 4.6 / 6.6 min | 3.0 / 5.0 min |
| 1.21.1: game / whole run | 3.1 / 6.0 min | 2.7 / 5.0 min |

Before the first section starts, a run spends time on the VM, the push, Gradle, starting Minecraft, making the world and the map's warm-up; the `time:` line shows each. Measured on 2026-10-30 for 26.3 on a default VM, with the world paused while the map settles (#196): the first test began 122–226 s after the script started on a fresh VM and 85–122 s on a warm one, where VM, push and Gradle take seconds. Large only speeds up the game part, so a short run saves a minute or so at twice the VM-hours. Use **default** for a few sections, and **`--type large`** for long runs (`all`, the multiplayer sections) where the game part dominates. 26.3's `all` doesn't fit in the 90-minute test budget even on large: split it into two runs.

## Limits

100 VMs at once; starts are limited to 12 a minute, 60 an hour and 200 a day, shared with every other agent on the account, and a refused start counts too. With paid extra starts on (`keepGoing` in `boat limits`), the day's limit stops being a wall: starts past it cost from $0.03 each. Before each start the script reads `boat limits` (free, counts nothing): it waits about 3 min at most for a start this minute, and stops starting when the hour's starts are gone, or the day's with extra starts off. Other agents can still take the last start between that check and this script's start, so a start can be refused anyway; a refusal stops the rest of this run's starts. Rerun the failed nodes later, not in a loop. If that means waiting, tell Dara what boat said and when it frees up (`startWindowFull.freesAt` in `boat limits`). Don't move the run to the PC's queue: use the PC only if the lock is free right now, with `--needs-pc 'boat refused: <error>'`. Reusing a warm VM costs no start. Before a fan-out of more than about 10 VMs, check `planIncluded.vmHoursRemaining` in `boat limits` too.

## Warm snapshot

VMs start from the newest saved snapshot named `diorama-gametest-<commit>`: JDK 25 (which runs every node, 1.20.1 included), Mesa, and Gradle, Loom and NeoForge caches with every node compiled, every node's Minecraft, libraries and assets downloaded, and every node booted once from that cache, warmed at `<commit>`. The script prints which one it used. A snapshot only holds the disk: a stopped, forked or resumed VM starts with no processes, so every run boots Minecraft afresh.

Since the VM's copy of the tree is the one at `<commit>`, the script sends only what your working tree changes from it (changed, new and deleted files, a few hundred KB), not the whole tree; a reused warm VM gets the whole tree, which takes a few seconds once the VM is warm. Gradle's configuration cache is on: a node the VM has already run skips configuring the build. The snapshot's boot pass stores those entries, so after the next refresh a fresh VM skips it too, as long as your change leaves the build scripts alone.

A new VM fetches each snapshot file from boat.dev's storage the first time it's read, and has them all after about 3 minutes. The snapshot also holds the list of files each node's boot opens (`~/prefetch-lists/<node>.list`), and the script reads those ahead in parallel while it pushes. How much that saves depends on how warm boat.dev's storage is: on a snapshot made minutes before, 26.3's Gradle start took 82–103 s cold and about 25 s read ahead; on one that has been used for a while, 28 s cold and 17 s read ahead.

**Refresh it** when runs start downloading again: after a Minecraft, Loom, NeoForge or Gradle bump, or a new node. From a clean checkout of origin/master:

```
python .claude/skills/boat-game-test/boat_snapshot.py
```

It took 64 min on 2026-10-28, most of it booting all 22 nodes one after another; run it in the background with the 4-hour budget it gives its VM. It saves nothing unless every node compiles and boots. It saves `diorama-gametest-<commit>`, lists the older ones and leaves them (remove one only if you made it or Dara says so), and the newest is what boat_run.py picks. Tell Dara the new name.

## Traps

- **boat.dev's API fails now and then**: 500s, timeouts, live output streams cut after a few minutes. The script starts the tests detached and polls for their exit code, and retries the steps that are safe to repeat. A run that still fails on an API error is worth one rerun, after step 4's check.
- **A killed script leaves its VM running** until its 2-hour lifetime ends. On Ctrl-C the script waits up to 6 min for each node to delete its VM, then deletes anything left with its prefix and the warm VMs it was using; a hard kill (a background command's timeout) skips all that. Either way, look for your prefix and `diorama-warm-<checkout>-` in `boat list --all`.
- **Warm VMs belong to a worktree**, and only idle ones carry the warm name: a run takes one by renaming it to its own `diorama-test-<random>-<node>`, so while it runs it's cleaned up like any VM of the run, and it gets the warm name back after a run that went well. Two runs from the same worktree at once can't take the same one; the second gets a VM of its own. Each run deletes warm VMs boat.dev has stopped, from any worktree (nothing would ever resume them); it never touches a running one that isn't its own.
- **`--keep`** stops the VM instead of deleting it, so you can look around with `boat resume <id>`, then `boat ssh <id>`. Delete it when done: its disk stays on the account for 14 days.
- **Other mods in a run (26.x only, unobfuscated):** put the jars in a folder inside the worktree and pass `--env "JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=40 -Dfabric.addMods=/home/user/diorama/<folder>"` (this replaces sandbox.sh's own `JAVA_TOOL_OPTIONS`, so keep the RAM cap). `boat push` skips git-ignored files, so leave the folder untracked rather than ignored.
- **The account is Dara's.** If `boat` says you're signed out or the plan is gone, stop and tell Dara.
