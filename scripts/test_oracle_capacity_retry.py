"""Offline checks of the deployment decisions; never contact Oracle or GitHub."""

import io
import types
import unittest
import zipfile
from unittest.mock import Mock, patch

import oracle_capacity_retry as retry


def job(state, operation="APPLY", name="job-1"):
    return types.SimpleNamespace(id=name, operation=operation, lifecycle_state=state)


def archive(body="resource example {}", timestamp=(2026, 1, 1, 0, 0, 0)):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as bundle:
        bundle.writestr(zipfile.ZipInfo("main.tf", timestamp), body)
    return output.getvalue()


CAPACITY = "Error: 500-InternalError, Out of host capacity.\nwith oci_core_instance.queueless,\non main.tf line 1\n"


class CapacityRetryTests(unittest.TestCase):
    def test_only_capacity_failures_can_retry(self):
        self.assertTrue(retry.capacity_only(CAPACITY))
        self.assertTrue(retry.capacity_only("│ " + CAPACITY.replace("\n", "\n│ ")))
        self.assertFalse(retry.capacity_only(CAPACITY + "\nError: Invalid public key\n"))
        self.assertFalse(retry.capacity_only("Error: Out of host capacity\nwith some_other_resource.x"))
        self.assertFalse(retry.capacity_only("No logs yet"))

    def test_existing_success_is_a_permanent_stop(self):
        logs = Mock()
        self.assertEqual(retry.decision([job("FAILED"), job("SUCCEEDED")], logs), "done")
        logs.assert_not_called()

    def test_resource_manager_timestamped_capacity_log(self):
        lines = [
            "[ERROR] Error: 500-InternalError, Out of host capacity.",
            "[INFO] Suggestion: The service for this resource encountered an error.",
            "[INFO]   with oci_core_instance.generated_oci_core_instance,",
            '[INFO]   on main.tf line 3, in resource "oci_core_instance":',
        ]
        log = "\n".join("2026/10/23 17:21:39[TERRAFORM_CONSOLE] " + line for line in lines)
        self.assertTrue(retry.capacity_only(log))
        self.assertEqual(retry.decision([job("FAILED")], lambda _: log), "attempt")
        mixed = log + "\n2026/10/23 17:21:39[TERRAFORM_CONSOLE] [ERROR] Error: Invalid public key\n"
        self.assertFalse(retry.capacity_only(mixed))
        self.assertEqual(retry.decision([job("FAILED")], lambda _: mixed), "halt")

    def test_any_active_job_prevents_a_second_submission(self):
        for state in retry.ACTIVE:
            self.assertEqual(retry.decision([job(state, "PLAN")], Mock()), "busy")

    def test_failures_and_cancellations_stop(self):
        self.assertEqual(retry.decision([job("FAILED")], lambda _: CAPACITY), "attempt")
        self.assertEqual(retry.decision([job("FAILED")], lambda _: "Error: quota exceeded"), "halt")
        self.assertEqual(retry.decision([job("CANCELED")], Mock()), "halt")
        self.assertEqual(retry.decision([job("SUCCEEDED", "PLAN")], Mock()), "attempt")

    def setUp(self):
        self.stack = types.SimpleNamespace(
            variables={"ocpus": "1"}, compartment_id="compartment",
            terraform_version="1.5.x",
            config_source=types.SimpleNamespace(config_source_type="ZIP_UPLOAD"),
        )
        self.archive = archive()
        self.client = Mock()
        self.client.get_stack.return_value.data = self.stack
        self.client.get_stack_tf_config.return_value.data.content = self.archive
        self.sdk = Mock()
        self.sdk.pagination.list_call_get_all_results.return_value.data = []
        self.env = {"OCI_STACK_ID": "ocid1.ormstack.oc1.ap-hyderabad-1.example"}

    def test_digest_ignores_zip_metadata_but_covers_variables_and_source(self):
        original = retry.stack_digest(self.archive, self.stack)
        self.assertEqual(original, retry.stack_digest(archive(timestamp=(2026, 2, 1, 0, 0, 0)), self.stack))
        self.assertNotEqual(original, retry.stack_digest(archive(body="changed"), self.stack))
        self.stack.variables["ocpus"] = "8"
        self.assertNotEqual(original, retry.stack_digest(self.archive, self.stack))

    def test_inspect_never_submits(self):
        self.assertEqual(retry.run(self.client, self.sdk, self.env), 0)
        self.client.create_job.assert_not_called()

    def test_unapproved_or_changed_stack_never_submits(self):
        self.env["RETRY_MODE"] = "attempt"
        for approved in (None, "different-digest"):
            self.env["OCI_APPROVED_STACK_DIGEST"] = approved
            with self.assertRaises(ValueError):
                retry.run(self.client, self.sdk, self.env)
        self.client.create_job.assert_not_called()

    def test_mutable_git_source_is_rejected(self):
        self.stack.config_source.config_source_type = "GIT_CONFIG_SOURCE"
        with self.assertRaises(ValueError):
            retry.run(self.client, self.sdk, self.env)
        self.client.create_job.assert_not_called()

    def test_reviewed_initial_stack_submits_once_with_idempotency_token(self):
        self.env.update(RETRY_MODE="attempt", OCI_APPROVED_STACK_DIGEST=retry.stack_digest(self.archive, self.stack))
        self.assertEqual(retry.run(self.client, self.sdk, self.env), 0)
        self.client.create_job.assert_called_once()
        self.assertTrue(self.client.create_job.call_args.kwargs["opc_retry_token"])

    def test_success_disables_workflow_without_submitting(self):
        self.env.update(RETRY_MODE="attempt", OCI_APPROVED_STACK_DIGEST=retry.stack_digest(self.archive, self.stack))
        self.sdk.pagination.list_call_get_all_results.return_value.data = [job("SUCCEEDED")]
        with patch.object(retry, "disable_schedule") as disable:
            self.assertEqual(retry.run(self.client, self.sdk, self.env), 0)
            disable.assert_called_once()
        self.client.create_job.assert_not_called()


if __name__ == "__main__":
    unittest.main()
