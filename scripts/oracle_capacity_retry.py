"""One capacity attempt per invocation; credentials are supplied only by environment.

Inspect is the default. Approve the exact uploaded stack digest after reviewing
its Terraform and a plan; do not enable retries against an unreviewed stack.
"""

import hashlib
import io
import json
import os
import re
import sys
import urllib.request
import zipfile

ACTIVE = {"ACCEPTED", "IN_PROGRESS", "CANCELING"}
WORKFLOW = "oracle-capacity.yml"


def stack_digest(archive, stack):
    """Hash logical contents, ignoring ZIP timestamps that can vary on download."""
    with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
        files = [
            (item.filename, hashlib.sha256(bundle.read(item)).hexdigest())
            for item in bundle.infolist() if not item.is_dir()
        ]
    payload = {
        "files": sorted(files), "variables": stack.variables,
        "compartment": stack.compartment_id, "terraform": stack.terraform_version,
        "working_directory": getattr(stack.config_source, "working_directory", None),
    }
    return hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()


def capacity_only(log):
    """Require every Terraform error to be an instance-capacity error."""
    clean = re.sub(r"\x1b\[[0-9;]*m", "", log)
    clean = re.sub(r"(?m)^\s*│\s?", "", clean)
    errors = re.split(r"(?m)^\s*Error:\s*", clean)[1:]
    return bool(errors) and all(
        re.search(r"out of (?:host )?capacity", error, re.I)
        and "oci_core_instance" in error
        for error in errors
    )


def decision(jobs, read_logs):
    # A previous successful apply is a permanent stop, even if a later job failed.
    if any(j.operation == "APPLY" and j.lifecycle_state == "SUCCEEDED" for j in jobs):
        return "done"
    if any(j.lifecycle_state in ACTIVE for j in jobs):
        return "busy"
    applies = [j for j in jobs if j.operation == "APPLY"]
    if not applies:
        return "attempt"
    latest = applies[0]  # The API response must be sorted by creation time descending.
    if latest.lifecycle_state != "FAILED":
        return "halt"
    return "attempt" if capacity_only(read_logs(latest.id)) else "halt"


def report(message):
    print(message)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as output:
            output.write(message + "\n\n")


def disable_schedule():
    token = os.environ.get("GITHUB_TOKEN")
    repo = os.environ.get("GITHUB_REPOSITORY")
    if not token or repo != "IronMan-14/Queueless":
        raise RuntimeError("Workflow disabling requires the expected GitHub repository.")
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repo}/actions/workflows/{WORKFLOW}/disable",
        data=b"", method="PUT", headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    with urllib.request.urlopen(request, timeout=30):
        pass
    report("The retry workflow is now disabled.")


def run(client, sdk, env):
    mode = env.get("RETRY_MODE", "inspect")
    if mode not in {"inspect", "attempt"}:
        raise ValueError("Unknown retry mode.")
    stack_id = env["OCI_STACK_ID"]
    if not stack_id.startswith("ocid1.ormstack.oc1.ap-hyderabad-1."):
        raise ValueError("Expected a Hyderabad stack.")
    stack = client.get_stack(stack_id).data
    if stack.config_source.config_source_type != "ZIP_UPLOAD":
        raise ValueError("Only a fixed uploaded Terraform configuration is supported.")
    digest = stack_digest(client.get_stack_tf_config(stack_id).data.content, stack)
    report(f"Stack configuration digest: `{digest}`")
    if mode == "inspect":
        report("Inspection only. No deployment job was created.")
        return 0
    if digest != env.get("OCI_APPROVED_STACK_DIGEST"):
        raise ValueError("Stack is unreviewed or its configuration changed.")
    jobs = sdk.pagination.list_call_get_all_results(
        client.list_jobs, stack_id=stack_id,
        sort_by="TIMECREATED", sort_order="DESC",
    ).data
    action = decision(jobs, lambda job_id: client.get_job_logs_content(job_id).data)
    if action == "done":
        report("An apply job already succeeded. No further deployment attempts are needed.")
        disable_schedule()
        return 0
    if action == "busy":
        report("An Oracle job is already running. Waiting until a later scheduled check.")
        return 0
    if action == "halt":
        report("The last apply was canceled or failed for a reason other than a recognized capacity error. Review its Oracle logs.")
        disable_schedule()
        return 1
    details = sdk.resource_manager.models.CreateJobDetails(
        stack_id=stack_id, operation="APPLY",
        display_name="QueueLess hourly capacity attempt",
        apply_job_plan_resolution=sdk.resource_manager.models.ApplyJobPlanResolution(
            is_auto_approved=True,
        ),
    )
    # The idempotency token prevents duplicate jobs if the API retries a request.
    # Oracle's job status is checked on the next run, including after a timeout.
    import uuid
    job = client.create_job(details, opc_retry_token=str(uuid.uuid4())).data
    report(f"Submitted one apply job: `{job.id}`. Its result will be checked on the next hourly run.")
    return 0


def main():
    mode = os.environ.get("RETRY_MODE", "inspect")
    try:
        import oci
        config = {
            "user": os.environ["OCI_USER"],
            "tenancy": os.environ["OCI_TENANCY"],
            "fingerprint": os.environ["OCI_FINGERPRINT"],
            "key_content": os.environ["OCI_PRIVATE_KEY"],
            "region": os.environ["OCI_REGION"],
        }
        oci.config.validate_config(config)
        client = oci.resource_manager.ResourceManagerClient(config, timeout=(10, 60))
        return run(client, oci, os.environ)
    except Exception as error:
        # Do not dump API response bodies, Terraform contents, or signing keys.
        report(f"Stopped: {type(error).__name__}. Check credentials, permissions, stack approval, and Oracle job details.")
        if mode == "attempt":
            try:
                disable_schedule()
            except Exception:
                report("Automatic workflow disabling failed. Disable Oracle capacity retry in GitHub Actions before re-enabling retries.")
        return 1


if __name__ == "__main__":
    sys.exit(main())
