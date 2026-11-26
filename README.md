# Diorama's GitHub Pages

This branch is served at https://sunkenintime.github.io/diorama/.

- `maven/` is the Maven repository for Diorama's addon API, `dev.diorama:diorama-api`. It is written only by `.github/workflows/publish-api.yml` on `master`, when an `api-v<version>` tag is pushed. A published version never changes. See `docs/addon-api.md` on `master`.

Add commits to this branch; never force-push it or rewrite its history.
