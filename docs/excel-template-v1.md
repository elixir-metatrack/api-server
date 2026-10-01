# Excel template version 1

Use **Download Excel template** in the Sample or Experiment toolbar. The existing CSV template download
remains available. XLSX templates are generated from the existing bundled CSV column definitions:

| GET endpoint | Download |
| --- | --- |
| `/api/templates/excel/sample` | `sample-v1.xlsx` |
| `/api/templates/excel/sample_extended` | `sample_extended-v1.xlsx` |
| `/api/templates/excel/sample_virus` | `sample_virus-v1.xlsx` |
| `/api/templates/excel/experiment` | `experiment-v1.xlsx` |

These downloads contain no project data and are public, like the CSV templates. Templates are shared across
projects; add any project-specific custom attribute columns yourself using their field keys.

## First worksheet: data

The first worksheet contains Sample or Experiment rows. Worksheet names are descriptive; their order determines
their purpose. Downloaded templates contain:

1. `TEMPLATE VERSION:` in A1 and `1` in B1.
2. Requirements, examples and descriptions, which are instructions and are not imported.
3. A `METADATA FIELDS:` row containing the column headers, starting in column B.
4. Data rows below the headers, starting in column B. Column A contains instructions only.

Keep these header rows when filling the template. Version 1 is checked during import; unsupported versions
are rejected. Header-first workbooks without a version marker are also accepted, with headers and data starting
in column A. Unversioned workbooks using the existing `METADATA FIELDS:` layout are accepted as well.

The column names, required values and validations follow the existing CSV importer. For Samples, add custom
attribute columns using keys from the project's custom attributes. These attributes must already exist.
Experiment rows reference samples already linked to the selected assay.

Blank data rows are ignored. Duplicate headers, values without a header, formula cells and Excel error cells
are rejected with their worksheet position. The data sheet must contain at least one data row and the required
`Sample Name`/`name` or `Sample` column. Unknown columns are rejected instead of being silently ignored; Sample
columns may use a built-in header or an active custom attribute key (an unambiguous custom label is also accepted).
Paste formula results as values before importing. Excel dates are read as ISO dates (`YYYY-MM-DD`); boolean cells
become `true`/`false`. Other cells use their displayed value, including number formats with leading zeros. Store
identifiers as text to avoid Excel changing them.

## Optional second worksheet: Sample vocabularies

Sample templates include an empty `Vocabularies` worksheet. Its header must contain exactly `field_key` and
`value`. Each row defines one allowed value, with the field key repeated for additional values:

| field_key | value |
| --- | --- |
| treatment_group | control |
| treatment_group | treated |

Before importing this example, create a custom TEXT attribute with key `treatment_group` in the project
and add a column with that key to the first worksheet.

- Only existing, active custom Sample TEXT attributes are eligible. The canonical field key must appear
  in the data worksheet. The importer does not create attributes.
- Built-in attributes cannot be configured here. Their application-managed vocabulary rules still apply
  when validating imported Sample values.
- New vocabulary definitions require project ADMIN or OWNER rights. Editors may import data and reuse
  identical existing definitions.
- An existing vocabulary must have exactly the same set of terms. Conflicting definitions are rejected;
  change a vocabulary separately through the project's vocabulary API.
- Terms are trimmed, case-sensitive and unique. Blank field keys or values are rejected.
- Omit the second sheet or leave only its headers when no definitions are needed.
- Experiment workbooks do not support vocabularies. Additional nonempty worksheets are rejected.
  Sample workbooks likewise reject nonempty worksheets beyond the second sheet.

The complete workbook structure, vocabulary definitions and required permissions are checked before any
definitions or rows are saved. Afterwards, the existing partial-import behavior applies: invalid data rows
are skipped and reported, while valid rows and new vocabulary definitions are saved. A response with row
errors does not roll back valid rows, and definitions can be saved even if every data row is invalid.
Check the imported data before retrying an upload.

## File support and limits

Both `.xls` and `.xlsx` uploads use this worksheet layout. Downloads use `.xlsx` only. Workbooks must be
unencrypted; `.xlsm` and `.xlsb` uploads are not supported. Excel files are limited to 20 MiB and each worksheet
to 100,000 rows and 512 columns. For safe processing, the expanded contents of an XLSX package are limited to
256 MiB. The deployment's request-size limit may be lower.

CSV/TSV parsing, templates and permissions remain unchanged; they do not import vocabulary definitions.
