# Contributing to Eclipse INCEpTION

Thanks for your interest in this project.

## Project description

Eclipse INCEpTION is a semantic annotation platform offering intelligent annotation assistance
and knowledge management.

* Eclipse project site: https://projects.eclipse.org/projects/technology.inception
* Website: https://eclipse-inception.github.io
* Documentation: https://eclipse-inception.github.io/documentation

## Terms of Use

This repository is subject to the Terms of Use of the Eclipse Foundation

* https://www.eclipse.org/legal/termsofuse.php

## Developer resources

Information regarding source code management, builds, coding standards, and
more.

* Developer guide: https://eclipse-inception.github.io/documentation/latest/developer-guide
* Source code: https://github.com/eclipse-inception/inception
* Issue tracker: https://github.com/eclipse-inception/inception/issues

### Building

You need a recent JDK and Apache Maven. Then build from the repository root:

    mvn clean install

Add `-DskipTests` for a faster build. The build formats the Java code (Spotless), so run it
before committing. The developer guide describes how to set up Eclipse or IntelliJ IDEA and how
to run the application from the IDE.

### Branches

* `main`: the next feature release.
* `release/<major>.x` (e.g. `release/42.x`): bug fixes for the current release line. Fixes made
  there are merged up into `main`.

## How to contribute

1. **Start from an issue.** Every change is tracked by an issue on GitHub. If there is none yet,
   open one first and describe what you want to change. For larger changes, wait for feedback
   from the committers before you invest a lot of work.
2. **Create a branch** from the branch you want to contribute to, named after the issue type,
   number and title, e.g. `bugfix/1234-Short-issue-title`, `feature/1234-...` or
   `refactoring/1234-...`. Fixes for the current release go on a branch off `release/<major>.x`,
   everything else on a branch off `main`. If you are unsure, ask in the issue.
3. **Commit** with messages whose first line is `Issue #1234: Issue title`, followed by an empty
   line and a short list of the changes.
4. **Open a pull request** against the branch you started from. You can open it early, as a
   draft, to discuss your approach. Fill in the pull request template, add tests where possible,
   and update the documentation when you change behaviour.
5. **Review.** A committer reviews every pull request before it is merged. The build and the
   Eclipse Contributor Agreement check must pass.

### Attribution and third-party content

* Contributors are credited through the version history, not through `@author` tags in the code.
* Do not remove copyright notices, `@author` tags or other attribution from code that comes from
  third parties, and never change its licence.
* Declare third-party content that you add to a module (copied or modified code, icons, fonts,
  data) in two places in that module:
  * `src/main/resources/META-INF/DEPENDENCIES-EMBEDDED.spdx.json`: one SPDX package entry per
    origin, with name, version, originator, download location, declared licence and the files
    it covers (`hasFiles`). These entries feed the dependency list on the application's about
    page and the generated legal documentation.
  * the module's `NOTICE.txt`: the files, their origin, copyright and licence. The build copies
    it into the jar.

  Third-party dependencies pulled in through Maven or npm need no such entry.
* Every module needs a `LICENSE.txt` (a copy of the one in the repository root). Without it, the
  build puts neither `LICENSE.txt` nor `NOTICE.txt` into the module's jar.
* Contributions from non-committers that are larger than 1,000 lines, contain content copied from
  elsewhere, are under a licence other than the project licence, or contain cryptography must be
  reviewed by the Eclipse IP Team before they can be merged. Mention any of these in your pull
  request so that we can request the review.

### Use of generative AI

You may use AI assistants, following the Eclipse Foundation's
[Generative Artificial Intelligence Usage Guidelines](https://www.eclipse.org/projects/handbook/#contributing-genai).
In short:

* You are responsible for every contribution you submit. Review, understand and test
  AI-generated content as you would your own, including for intellectual property and security
  issues.
* Disclose the assistance with an `Assisted-by` trailer in the commit message, naming the
  provider, the model and its version as far as known, for example:

      Issue #1234: Issue title

      - Change 1
      - Change 2

      Assisted-by: Anthropic Claude (claude-opus-5-5)

* For files that are largely or entirely AI-generated, also add the AI disclosure to the file
  header as described in the guidelines.

## Help Wanted

Issues that are suitable for getting started are labelled "Good first issue":

* https://github.com/eclipse-inception/inception/labels/%F0%9F%90%BE%20Good%20first%20issue

## Project License

Apache License 2.0

* https://www.apache.org/licenses/LICENSE-2.0

Contributions are received under the terms of the project licence.

## Eclipse Foundation Development Process

This Eclipse Foundation open project is governed by the Eclipse Foundation
Development Process and operates under the terms of the Eclipse IP Policy.

* https://eclipse.org/projects/dev_process
* https://www.eclipse.org/org/documents/Eclipse_IP_Policy.pdf

## Eclipse Contributor Agreement

In order to be able to contribute to Eclipse Foundation projects you must
electronically sign the Eclipse Contributor Agreement (ECA).

* https://www.eclipse.org/legal/ECA.php

The ECA provides the Eclipse Foundation with a permanent record that you agree
that each of your contributions will comply with the commitments documented in
the Developer Certificate of Origin (DCO). Having an ECA on file associated with
the email address matching the "Author" field of your contribution's Git commits
fulfils the DCO's requirement that you sign-off on your contributions.

For more information, please see the Eclipse Committer Handbook:
https://www.eclipse.org/projects/handbook/#resources-commit

## Contact

Contact the project developers via the project's "dev" list.

* https://accounts.eclipse.org/mailing-list/inception-dev

Questions about using INCEpTION are best asked on the users group or in GitHub Discussions.

* https://groups.google.com/g/inception-users
* https://github.com/eclipse-inception/inception/discussions

Please report security vulnerabilities as described in [SECURITY.md](SECURITY.md), not through
any of these channels.
