#!/usr/bin/env bash
set -Eeuo pipefail

: "${EC2_INSTANCE_ID:?}"
: "${IMAGE_REF:?}"
: "${GITHUB_SHA:?}"
: "${AWS_REGION:?}"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
keep_running="${KEEP_RUNNING:-false}"
started=false
succeeded=false
command_id=

cleanup() {
  local status=$?
  trap - EXIT
  if [[ -n "$command_id" && "$succeeded" != true ]]; then
    aws ssm cancel-command --command-id "$command_id" --instance-ids "$EC2_INSTANCE_ID" >/dev/null 2>&1 || true
  fi
  if [[ "$started" == true && ( "$succeeded" != true || "$keep_running" != true ) ]]; then
    if ! aws ec2 stop-instances --instance-ids "$EC2_INSTANCE_ID" >/dev/null ||
       ! aws ec2 wait instance-stopped --instance-ids "$EC2_INSTANCE_ID"; then
      echo '::error::Could not confirm the instance is stopped. Check EC2 in the AWS console.'
      status=1
    fi
  fi
  exit "$status"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

state="$(aws ec2 describe-instances --instance-ids "$EC2_INSTANCE_ID" --query 'Reservations[0].Instances[0].State.Name' --output text)"
case "$state" in
  stopped)
    started=true
    aws ec2 start-instances --instance-ids "$EC2_INSTANCE_ID" >/dev/null
    aws ec2 wait instance-running --instance-ids "$EC2_INSTANCE_ID"
    ;;
  running) ;;
  *) printf 'EC2 must be stopped or running (current: %s).\n' "$state" >&2; exit 1 ;;
esac

online=false
for n in {1..36}; do
  ping="$(aws ssm describe-instance-information --filters "Key=InstanceIds,Values=$EC2_INSTANCE_ID" \
    --query 'InstanceInformationList[0].PingStatus' --output text)"
  if [[ "$ping" == Online ]]; then online=true; break; fi
  sleep 5
done
[[ "$online" == true ]]

encoded="$(base64 < "$script_dir/release-ec2.sh" | tr -d '\n')"
printf -v remote 'printf %%s %q | base64 -d | bash -s -- %q %q %q' "$encoded" "$IMAGE_REF" "$GITHUB_SHA" "$AWS_REGION"
parameters="$(jq -nc --arg command "$remote" '{commands:[$command],executionTimeout:["600"]}')"
command_id="$(aws ssm send-command --instance-ids "$EC2_INSTANCE_ID" --document-name AWS-RunShellScript \
  --parameters "$parameters" --query 'Command.CommandId' --output text)"
printf 'SSM command %s\n' "$command_id"

for n in {1..130}; do
  invocation="$(aws ssm get-command-invocation --command-id "$command_id" --instance-id "$EC2_INSTANCE_ID" 2>&1)" || {
    if [[ "$invocation" == *InvocationDoesNotExist* ]]; then sleep 5; continue; fi
    printf '%s\n' "$invocation" >&2
    exit 1
  }
  case "$(jq -r .Status <<< "$invocation")" in
    Pending|InProgress|Delayed|Cancelling) sleep 5 ;;
    Success)
      jq -r .StandardOutputContent <<< "$invocation"
      succeeded=true
      exit 0
      ;;
    *)
      jq -r '.Status, .StandardOutputContent, .StandardErrorContent' <<< "$invocation" >&2
      command_id=
      exit 1
      ;;
  esac
done
echo 'EC2 deployment timed out.' >&2
exit 1
