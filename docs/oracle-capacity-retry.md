# Retry Oracle's saved QueueLess stack

The `Oracle capacity retry` GitHub workflow is staged **disabled**. It can check
the saved Hyderabad stack hourly without leaving a computer on. It does not
install QueueLess after provisioning the server.

## Before enabling

1. Generate an Oracle API signing key under your user profile's API keys (or
   Tokens and keys). Download the private PEM file and register the public key.
   This is separate from the SSH key used to sign into the future server.
2. Store `OCI_USER`, `OCI_TENANCY`, `OCI_FINGERPRINT`, and `OCI_PRIVATE_KEY` as
   GitHub Actions repository secrets. Use encrypted secret storage, never commit
   credentials. The region and the user-provided stack OCID are in the workflow.
3. Review the stack's downloaded Terraform configuration and a current plan.
   Confirm Ubuntu, `VM.Standard.A1.Flex`, 1 OCPU, 6 GB RAM, the existing public
   subnet, the correct SSH public key, and no unwanted resources. Verify total
   tenancy usage remains within Oracle's current Always Free allowance.
4. Manually run the workflow with `mode=inspect`. This only reads the stack and
   reports a digest covering its files, variables, compartment and Terraform
   version. Set the Actions repository variable `OCI_APPROVED_STACK_DIGEST` to
   that digest only after reviewing this exact configuration and plan.
5. Set the Actions repository variable `OCI_CAPACITY_RETRY_ENABLED` to `true`,
   enable the workflow, and manually dispatch `mode=attempt` for the first try.
   Scheduled runs occur at minute 17 of each UTC hour; GitHub may delay them.

## Behavior and stopping

- Each run submits at most one apply against the same stack and its existing
  Terraform state. It never switches shape, region, or account tier.
- A running Oracle job prevents another submission. GitHub concurrency also
  serializes this workflow's runs. Do not manually apply or edit the stack while
  automated retries are enabled.
- A previous successful apply disables the workflow permanently. The next
  scheduled run normally detects the result; it can also be dispatched manually.
- Retrying a failed apply requires every Terraform error block to identify an
  `oci_core_instance` out-of-capacity error. Other failures, canceled jobs,
  missing credentials or a changed stack halt and disable the workflow.
- Raw Terraform logs and signing keys are not printed or uploaded as artifacts.
- To stop immediately, open GitHub Actions > Oracle capacity retry > Disable
  workflow. This does not cancel an already submitted Oracle job. Cancel that
  job separately in Oracle if needed. To resume after reviewing a failure,
  re-enable the workflow explicitly.
- Scheduled checks consume GitHub Actions minutes. Review the repository's
  Actions usage; capacity and hourly execution timing are not guaranteed.

Credentials are not configured and the stack has not yet been inspected from
this workspace. No live deployment or successful provisioning is claimed.
