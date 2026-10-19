#!/usr/bin/env python3
"""Add synthetic ISA export data to the local dev setup without deleting existing projects."""
import argparse
import hashlib
import json
import os
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode, urlparse
from urllib.request import Request, urlopen
from uuid import uuid4


def request(url, method="GET", body=None, token=None, content_type="application/json"):
    headers = {"Content-Type": content_type}
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None and not isinstance(body, bytes):
        body = json.dumps(body).encode()
    try:
        with urlopen(Request(url, data=body, headers=headers, method=method), timeout=60) as response:
            data = response.read()
            return json.loads(data) if data else None
    except HTTPError as error:
        raise SystemExit(f"{method} {url}: HTTP {error.code}: {error.read().decode()}") from error


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("target/isa-demo.json"))
    args = parser.parse_args()
    api = os.environ.get("API_URL", "http://localhost:1234/api").rstrip("/")
    realm = os.environ.get("KC_REALM_URL", "http://localhost:8180/realms/metatrack").rstrip("/")
    for url in (api, realm):
        if urlparse(url).hostname not in ("localhost", "127.0.0.1", "::1"):
            raise SystemExit("This demo seed only runs against localhost.")
    token = request(realm + "/protocol/openid-connect/token", "POST", urlencode({
        "grant_type": "client_credentials", "client_id": "metatrack-admin",
        "client_secret": os.environ.get("KEYCLOAK_ADMIN_SECRET", "local-admin-secret"),
    }).encode(), content_type="application/x-www-form-urlencoded")["access_token"]

    def api_request(path, method="GET", body=None):
        return request(api + path, method, body, token)

    description = "Synthetic ISA-JSON exploration data; file records are pending placeholders."
    projects = api_request("/projects")
    project = next((p for p in projects if p["name"] == "isa-json-demo"), None)
    if project is None:
        project = api_request("/projects", "POST", {"name": "isa-json-demo", "description": description})
    elif project.get("description") != description:
        raise SystemExit("A different project named isa-json-demo exists; leaving it unchanged.")
    base = f'/projects/{project["id"]}'
    fields = api_request(base + "/sample-metadata-fields?includeArchived=true")
    definitions = [
        ("treatment_group", "Treatment group", "TEXT"),
        ("concentration", "Concentration", "NUMBER"),
        ("quality_checked", "Quality checked", "BOOLEAN"),
        ("extraction_date", "Extraction date", "DATE"),
    ]
    for key, label, field_type in definitions:
        existing = next((f for f in fields if f["key"] == key), None)
        if existing is None:
            api_request(base + "/sample-metadata-fields", "POST", {"key": key, "label": label, "type": field_type})
        elif existing["type"] != field_type or existing.get("archivedOn") is not None:
            raise SystemExit(f"Existing field {key} has a different type or is archived; leaving it unchanged.")

    samples = api_request(base + "/samples")
    for index, group in enumerate(("control", "treated"), 1):
        name = f"ISA-DEMO-{index:03}"
        if any(s["name"] == name for s in samples):
            continue
        samples.append(api_request(base + "/samples", "POST", {
            "name": name, "description": "Synthetic sample for ISA-JSON exploration",
            "collectionDate": "2026-10-01", "location": "Norway",
            "customMetadata": {"treatment_group": group, "concentration": index * 1.25,
                               "quality_checked": index == 1, "extraction_date": "2026-10-02"},
        }))
    demo_samples = [s for s in samples if s["name"] in ("ISA-DEMO-001", "ISA-DEMO-002")]
    assays = api_request(base + "/assays")
    assay = next((a for a in assays if a["name"] == "ISA demo sequencing"), None)
    if assay is None:
        assay = api_request(base + "/assays", "POST", {
            "name": "ISA demo sequencing", "libraryStrategy": "WGS", "librarySource": "GENOMIC",
            "librarySelection": "RANDOM", "libraryLayout": "PAIRED", "instrumentModel": "Illumina MiSeq",
        })
    assay_path = base + f'/assays/{assay["id"]}'
    current = api_request(assay_path + "/samples")
    missing = [s["name"] for s in demo_samples if not any(s["id"] == member["id"] for member in current)]
    if missing:
        api_request(assay_path + "/samples", "PUT", {"sampleNames": missing})
    existing_files = api_request(assay_path + "/files")
    rows = ["Sample;Forward File Name;Forward File md5;Reverse File Name;Reverse File md5"]
    checksum = hashlib.md5(b"@demo\nACGT\n+\n!!!!\n").hexdigest()
    for sample in demo_samples:
        names = [f'{sample["name"]}_R{read}.fastq' for read in (1, 2)]
        names = ["" if any(f["name"] == name for f in existing_files) else name for name in names]
        if any(names):
            rows.append(";".join((sample["name"], names[0], checksum if names[0] else "",
                                  names[1], checksum if names[1] else "")))
    if len(rows) > 1:
        boundary = "isa-demo-" + uuid4().hex
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="demo.csv"\r\n'
                'Content-Type: text/csv\r\n\r\n' + "\n".join(rows) + f'\r\n--{boundary}--\r\n').encode()
        errors = request(api + assay_path + "/experiments", "POST", body, token,
                         f"multipart/form-data; boundary={boundary}")
        if errors:
            raise SystemExit(f"File metadata import failed: {errors}")
    children = api_request(base + "/subprojects")
    child = next((p for p in children if p["name"] == "isa-json-demo-subset"), None)
    if child is None:
        child = api_request(base + "/subprojects", "POST", {
            "name": "isa-json-demo-subset", "description": "Only ISA-DEMO-001 is shared here.",
            "sampleIds": [next(s["id"] for s in demo_samples if s["name"] == "ISA-DEMO-001")],
        })

    # Reuse the local demo accounts; do not create users or reset their passwords.
    admin_realm = realm.replace("/realms/", "/admin/realms/", 1)
    for username, project_id, role in (("testuser", project["id"], "ADMIN"),
                                       ("testuser", child["id"], "ADMIN"),
                                       ("testuser2", child["id"], "VIEWER")):
        users = request(admin_realm + "/users?" + urlencode({"username": username, "exact": "true"}), token=token)
        if not users:
            print(f"Local account {username} is missing; run the existing local seed first to create it.")
            continue
        member_path = f'/projects/{project_id}/member/{users[0]["id"]}'
        members = api_request(f"/projects/{project_id}/members")
        if not any(m.get("memberId") == users[0]["id"] or m.get("id") == users[0]["id"] for m in members):
            api_request(member_path, "POST", {"role": role})

    # Save the response bytes so even large decimal values retain their original precision.
    args.output.parent.mkdir(parents=True, exist_ok=True)
    export_request = Request(api + base + "/exports/isa-json", headers={"Authorization": "Bearer " + token})
    with urlopen(export_request, timeout=60) as response:
        args.output.write_bytes(response.read())
    print(f'Project: http://localhost:5173/projects/{project["id"]}')
    print(f'Subset: http://localhost:5173/projects/{child["id"]}')
    print(f"Export: {args.output.resolve()}")


if __name__ == "__main__":
    main()
