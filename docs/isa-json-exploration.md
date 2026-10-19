# ISA-JSON export exploration

First step: export existing project metadata and inspect the result using synthetic seed data.
Import, repository submission and service-to-service authentication are follow-up work.

## Try it locally

Start the existing local infrastructure and API (`make infra`, `make api` from the workspace root).
The local Keycloak setup must already contain the `metatrack-admin` service account.
From `api-server`:

```sh
python3 scripts/seed-isa-demo.py
```

This adds `isa-json-demo` and `isa-json-demo-subset`. Existing projects and user passwords
are preserved. Re-running the script adds missing demo records and leaves edited records
alone. It grants the existing `testuser` account access to both projects and `testuser2`
access to the subset. It does not create missing accounts.

The demo contains two samples, TEXT/NUMBER/BOOLEAN/DATE custom fields, an assay and four
pending FASTQ file records. No file contents are uploaded. The script downloads the
export to `target/isa-demo.json` and prints the project URLs.

In the webapp, open a project and click **Export ISA-JSON** below its details. This exports
the entire visible project scope, independent of table selection and filtering.

The endpoint is `GET /api/projects/{projectId}/exports/isa-json`, authenticated with the
existing bearer token and requiring project VIEWER access. It returns an Investigation
JSON document as an attachment with `Cache-Control: private, no-store`.

## Mapping used in this prototype

| Metatrack | ISA-JSON | Decision / limitation |
| --- | --- | --- |
| Project | Investigation with one Study | Both carry the project name and description; there is no separate Study entity yet. |
| Sub-project | A separate scoped export | Linked sample subset only. A permission boundary is not automatically an ISA Study. Parent-only metadata is not copied. |
| Sample ID and name | Study `materials.samples` | Stable `urn:metatrack:sample:UUID` identifiers; references use `@id`. |
| Built-in sample metadata | `characteristics` | Category names use the current field names in snake case. No unverified ontology equivalence is assumed. |
| Active custom sample fields | `characteristicCategories` and `characteristics` | Each definition has a distinct ID even if labels coincide. `metatrack:fieldKey`, `metatrack:fieldType` and tooltip comments are attached to the category's ontology annotation. |
| NUMBER | JSON number | Decimal precision is preserved by the API; the webapp downloads bytes without parsing them. |
| BOOLEAN / DATE | String | ISA characteristic values exclude JSON booleans; dates use ISO dates. Custom type comments retain the original type. |
| Vocabulary value | Characteristic value | Selected values are included. Allowed-value lists, global vocabulary configuration and ontology accessions are not exported. |
| Assay | Study `assays` | Name and existing study accession are comments; instrument model becomes `technologyPlatform`. |
| Assay library/instrument fields | Protocol parameters and process parameter values | Existing values only; no inferred measurement/technology ontology terms. |
| Sample/assay/file membership | One process per sample and assay | A simplified association graph, explicitly described as such. It does not reconstruct library preparation, pooling or laboratory steps. |
| File metadata | Assay `dataFiles` and process outputs | FASTQ files are raw data; other file types remain unspecified. Names, checksums, read roles and upload status are retained. Storage keys, signed URLs and uploader identities are excluded. |

Archived custom fields, audit timestamps, members and owner identities are excluded.
The profile comment `metatrack:exportProfile=exploration-v1` identifies these mapping choices;
namespaced comments are Metatrack conventions, not standardized ISA fields.
Project IDs are local to an installation; deployment-qualified IDs need a decision before
exchanging exports from multiple Metatrack installations.

## What the validation proves

The seeded export has been checked against both schemas and for resolvable internal `@id`
references. To repeat with pinned upstream checkouts:

```sh
git clone https://github.com/ISA-tools/isa-specs.git /tmp/isa-specs
git -C /tmp/isa-specs checkout ed80675acb5ff98249e9821067eff83740d809d6
git clone https://github.com/elixir-europe/MARS.git /tmp/mars
git -C /tmp/mars checkout 8cc6fca592ff1695bd795144d7816bef984712df
uv run --with jsonschema==4.25.1 python scripts/validate-isa-json.py target/isa-demo.json \
  --isa-schema-dir /tmp/isa-specs/source/_static/isajson \
  --mars-schema /tmp/mars/schemas/isa_json.schema.json
```

MARS's published schema has an `investigation` wrapper, while the ISA specification uses
the Investigation itself as the document root. The validator adds the wrapper only for
the MARS schema check; the downloaded document remains standard ISA-JSON.

Schema validity does **not** establish submission readiness. The current MARS ENA adapter
walks backwards from data files through sequencing processes and library materials. Our
current model has no equivalent library/material lineage. MARS also has additional model
validators (including target-repository annotations) that are not enforced by its JSON
schema. The export intentionally does not select a target repository.

Before claiming MARS interoperability, test the generated document with a pinned MARS
parser and ENA adapter using a complete library/process fixture. Then address missing
BioSamples accessions, organism ontology mapping, repository file locations and checksums.
ENA checklist validation remains a separate follow-up.

## Next exploration: machine-to-machine access

The export endpoint already uses the API's bearer authentication, but that alone is not a
complete S2S design. ProjectRoleCheck currently expects the authenticated identity to be
a project member. Decide how a Keycloak client-credentials identity receives explicit,
read-only project access, how access is revoked/audited, and which export profile/version
is requested. Preserve those same project and sub-project boundaries for machine clients.

For import, first inspect third-party fixtures for multiple Studies, ontology terms, units,
factors, derived materials and branching/pooling processes. Preview unsupported information
and mapping conflicts before creating database records. Flat sample attributes cannot
represent an arbitrary ISA graph losslessly.

## Sources inspected

- [ISA abstract model](https://isa-specs.readthedocs.io/en/latest/isamodel.html)
- [ISA-JSON specification](https://isa-specs.readthedocs.io/en/latest/isajson.html)
- [Pinned ISA schemas](https://github.com/ISA-tools/isa-specs/tree/ed80675acb5ff98249e9821067eff83740d809d6/source/_static/isajson)
- [Pinned MARS schema](https://github.com/elixir-europe/MARS/blob/8cc6fca592ff1695bd795144d7816bef984712df/schemas/isa_json.schema.json)
- [MARS ENA adapter](https://github.com/elixir-europe/MARS/tree/8cc6fca592ff1695bd795144d7816bef984712df/repository-services/isajson-ena)
- [MARS repository API](https://github.com/elixir-europe/MARS/blob/8cc6fca592ff1695bd795144d7816bef984712df/docs/src/pages/repository-services/repository-api.md)
