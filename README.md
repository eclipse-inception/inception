<div align="center">
  <a href="https://eclipse-inception.github.io/">
    <img width="471" style="max-width:50%;" src="inception/inception-doc/src/main/resources/META-INF/asciidoc/inception-logo.png"  alt="INCEpTION Logo"/>
  </a>
  <p>
    Multi-layer text annotation with knowledge-base entity linking and machine-assisted suggestions.
  </p>
  <br/>
  <p>
    <a href="https://eclipse-inception.github.io/"><strong>Homepage</strong></a> ·
    <a href="https://eclipse-inception.github.io/documentation/latest/user-guide"><strong>Usage</strong></a> ·
    <a href="https://morbo.ukp.informatik.tu-darmstadt.de/demo" title="Demo server operated by TU Darmstadt"><strong>Demo</strong></a> (TU Darmstadt) ·
    <a href="https://eclipse-inception.github.io/documentation/latest/user-guide#sect_faq"><strong>FAQ</strong></a>
  </p>
  <p>

[![GitHub release (latest SemVer)](https://img.shields.io/github/v/release/eclipse-inception/inception)](https://github.com/eclipse-inception/inception/releases/latest)
[![GitHub license](https://img.shields.io/github/license/eclipse-inception/inception)](https://github.com/eclipse-inception/inception/blob/main/LICENSE.txt)
[![Join the chat at https://gitter.im/inception-project/Lobby](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/inception-project/Lobby?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

  </p>

</div>

---

# INCEpTION

Build annotated text corpora with your own annotation scheme, ground them in your own ontology, and
let a recommender that learns as you go do the repetitive part.

<div align="center" style="margin: 10px">
    <img src="https://eclipse-inception.github.io/images/screenshot-annotation.png" alt="INCEpTION Screenshot" />
</div>

- **Annotate against your ontology.** Load RDF, OWL, OBO, SKOS or Turtle, or query a remote SPARQL
  endpoint live. Profiles for Wikidata, SNOMED CT, the Gene Ontology, the Human Phenotype Ontology
  and GND.
- **Stack as many layers as your scheme needs.** Entities, relations, coreference, syntax, frames and
  document labels over the same text, with typed features and slots. Define it all in the browser.
- **Get suggestions that improve while you work.** Recommenders train on what you have annotated so
  far; active learning asks about the cases they are least sure of. Nothing is stored until you
  accept it.
- **Know your annotations are good.** Curate several annotators into a gold standard, measure
  inter-annotator agreement, and chart what you collected in the Explorer.
- **Runs where you need it to run.** A desktop installer for one person, or a server deployment for
  a whole institution — on your own hardware, with your own single sign-on.
- **Drive it from your own code.** REST API, webhooks, and your own models as
  [external recommenders](https://github.com/inception-project/inception-external-recommender).
- **Speaks your field's formats.** Imports plain text, PDF, HTML and TEI; exports UIMA CAS XMI/JSON
  with custom layers intact, or CoNLL-U.

More detail, screenshots and example projects are on the
**[INCEpTION website](https://eclipse-inception.github.io/)**.

## Getting started

The best way to get started is to
watch [our tutorial videos](https://www.youtube.com/watch?v=Ely8eBKqiSI&list=PL5Hz5pttaj96SlXHGRZf8KzlYvpVHIoL-),
working through
the [Getting Started Guide](https://eclipse-inception.github.io/documentation/latest/user-guide#sect_core_funct) and
playing with INCEpTION on the [demo server](https://morbo.ukp.informatik.tu-darmstadt.de/demo)
operated by TU Darmstadt.

## Documentation

- [User Guide](https://eclipse-inception.github.io/documentation/latest/user-guide#sect_core_funct)
  — using INCEpTION.
- [Admin Guide](https://eclipse-inception.github.io/documentation/latest/admin-guide)
  — installing and running it for a group of users.
- [Developer Guide](https://eclipse-inception.github.io/documentation/latest/developer-guide)
  — building and extending it.

[Example projects](https://eclipse-inception.github.io/example-projects/) and
[use cases](https://eclipse-inception.github.io/use-cases/) are on the website, along with
[Python scripts and Jupyter notebooks](https://eclipse-inception.github.io/example-projects/python/)
for preparing and post-processing annotations.

## Do you have questions or feedback?

INCEpTION is actively developed and maintained, and you are welcome to give us feedback and tell us your wishes and requirements.

- Ask on our Google group [inception-users](https://groups.google.com/forum/#!forum/inception-users).
- Open an issue on [GitHub](https://github.com/eclipse-inception/inception/issues).

## How to cite

Please use the following citation:

    @inproceedings{klie-etal-2018-inception,
        title = "The {INCE}p{TION} Platform: Machine-Assisted and Knowledge-Oriented Interactive Annotation",
        author = "Klie, Jan-Christoph and Bugert, Michael  and Boullosa, Beto and Eckart de Castilho, Richard and Gurevych, Iryna",
        booktitle = "Proceedings of the 27th International Conference on Computational Linguistics: System Demonstrations",
        year = "2018",
        address = "Santa Fe, New Mexico",
        url = "https://www.aclweb.org/anthology/C18-2002",
        pages = "5--9"
    }

## Contributing

Do you miss a feature? We very much appreciate your contribution! Please open an issue before sending a pull request.
See [CONTRIBUTING.md](CONTRIBUTING.md) for how to contribute.

1. Create a fork
2. Create your feature branch: `git checkout -b my-feature`
3. Commit your changes: `git commit -am 'Add some feature'`
4. Push to the branch: `git push origin my-new-feature`
5. Submit a pull request 🚀

## License

INCEpTION is provided as open source under the Apache License v2.0.

---

<div align="center">
<img height="50" style="margin-right: 15px" src="https://eclipse-inception.github.io/images/logos/tud_logo.gif">
<img height="50" style="margin-right: 15px" src="https://eclipse-inception.github.io/images/logos/ukp-lab.png">
<img height="50" style="margin-right: 15px" src="https://eclipse-inception.github.io/images/logos/dfg_logo_schriftzug_blau_foerderung.jpg">
</div>

