# Contributing

## Reporting a bug

Before opening an issue, search [the issue tracker][issues]; the problem may already be known.
A good report says which server it happened on, what you did, and what you expected.

## Proposing a change

For larger changes, such as a new server, a new set of mods or a change to permissions, open an
issue first so the change can be discussed before you spend time on it. Bug fixes, small mod
updates and new maps can go straight to a pull request.

## Making a pull request

1. Set up the tools from the [README](README.md#setup).
2. Create a branch from `dev`, named `<type>/<short-name>` with a conventional commit type
   (`feat`, `fix`, `test`, `docs`, `ci`, `refactor`, `chore`):
   ```
   git checkout -b feat/plot-limit dev
   ```
3. Make the change and try it on the local stack ([README](README.md#running-locally)).
4. Commit with a [conventional commit](https://www.conventionalcommits.org) message, such as
   `feat: more plots for builders` or `fix: hub spawn after restart`. The subject is in the imperative
   and short; a body is only needed for what the diff does not show.
5. Push the branch and open a pull request into `dev`. Its title is a conventional commit subject,
   such as `feat: more plots for builders`, and its description says what changed and how you tested
   it.

Once a pull request into `prod` is green, a maintainer promotes `dev` with `git push origin dev:prod`.

### CI checks

CI runs the checks and end-to-end tests on every pull request. If one fails, you can run it
locally; see [Testing](docs/testing.md).

### Addressing review feedback

Make the changes in a fixup commit and push it:

```
git commit --all --fixup HEAD
git push
```

## Using generative AI

AI tools are allowed, with these rules:

- You are the author. You are responsible for what you submit and must be able to explain and
  change it without the tool.
- Interaction with other people is done by you. Issues, pull requests, comments and review replies
  are written and sent by you, not by an agent. Pull requests or comments made by an agent on its
  own are closed.
- Keep pull requests small, coherent and tested. Split large changes, and say where you are unsure.
- Do not copy configs or code from sources whose license does not allow it.

[issues]: https://github.com/ETHG/propwash-community-server/issues
