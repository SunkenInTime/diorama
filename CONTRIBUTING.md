# Contributing to Diorama

Pull requests are welcome. This page says what one needs before it gets reviewed.

The short version: show the change working in the running game, keep the pull request to one thing, and say which AI model wrote it if one did. A pull request that skips these is hard to judge from a diff, so it waits until they're added.

[Building and testing](docs/development.md) covers how to build every version and run the game tests.

## Before you write code

- **A bug fix** can go straight to a pull request. Make the bug happen first, in the game, and keep the screenshot or log. If you can't make it happen, say what you tried in the issue instead.
- **A new feature, a new setting or a changed default** starts as an issue. Say what problem a player has, then wait for an answer before building. Many requests turn out to be covered by something Diorama already has, and a setting nobody needed is hard to remove later.
- **Check what Xaero's Minimap and World Map do.** They are the standard Diorama follows for behaviour, settings, defaults and names, with JourneyMap second. Match them unless the 3D map calls for something else, and say why if you depart.

## One pull request, one reason

A pull request does one thing. Claiming chunks from the map, a new claim style and a translation are three pull requests, each reviewed and tested by itself. A fix you noticed on the way gets its own.

Fix the cause, with the smallest change that does it. If the fix keeps growing with each review round, the problem lives somewhere else.

## Show it in the running game

Tests and a clean compile are the minimum. A change counts as working once it has been seen in the game, the way a player would see it. What to show depends on what a player would notice:

| The change | What the pull request shows |
| --- | --- |
| Anything that looks different | A before and an after screenshot from the running game, same world and same spot, with only your change switched. Crop to the part that changed. |
| Something that moves, or that you click or drag | A GIF of 8 to 15 seconds as well, trimmed to the moment. GitHub doesn't play linked MP4 files. |
| Speed, memory or disk | A table of before and after, one row per setting and machine, with how it was measured and how many runs. Say how much two runs of the same build differ, so the gain can be told apart from noise. |
| Nothing a player can see | The real flow run once in the game, with its output. |
| Something between two players, or a server | A real dedicated server and real clients, with a picture from each side that matters. |
| Another mod | That mod installed, with its name and version. |

Say where you ran it: the Minecraft version, the loader, and singleplayer or server. Diorama ships for Fabric, NeoForge and Forge from 1.20.1 to 26.3, so say which of those you didn't run as well.

Before opening the pull request, run `./gradlew --continue compileJava`. It compiles every version, and most changes touch code they all share.

A new test should fail without your change and pass with it. Tests that only repeat the code, or check that a setting exists, aren't needed.

## Say if AI wrote it

AI-written pull requests are fine. They need two things said plainly in the description:

- **Which model and tool**, for example "Claude Opus in Claude Code" or "GPT in Codex".
- **What you checked yourself**, as opposed to what the model told you it did. "The model says it compiles" isn't a check.

Code that has never been compiled or run in the game isn't ready for review, whoever wrote it. Open it as a draft until it has.

Leave AI co-author lines out of the commits. The disclosure belongs in the description.

## The description

People skim. Put things in this order:

1. The picture, GIF or table. If the change has nothing to show, one line saying why.
2. Why this change exists, from the player's side, in a sentence or two. Link the issue.
3. What it does and why you did it this way.
4. What you ran, and what you didn't.

Write it in plain words, as if the reader has never opened the code. The pull request template has these headings ready.

## After you open it

A reviewer may ask for changes or for a picture that's missing. Push fixes for one round of review together. If a comment is wrong, say why; it doesn't need a code change.

Merging publishes nothing. Releases are cut separately.
