#!/usr/bin/env bash
set -Eeuo pipefail

image=$1
revision=$2
region=$3
[[ "$image" =~ ^[0-9]{12}\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com/[a-z0-9._/-]+@sha256:[0-9a-f]{64}$ ]]
[[ "$revision" =~ ^[0-9a-f]{40}$ ]]
[[ "$region" == ap-northeast-2 ]]

root=/opt/fullcount
mountpoint -q /srv/fullcount
test -s /srv/fullcount/data/fullcount.db
test -s /srv/fullcount/data/fullcount-demo.db
test -s /srv/fullcount/data/kbo.tar.gz
test -f "$root/.env"
mkdir -p "$root/releases"
exec 9>"$root/.deploy.lock"
flock -n 9
previous="$(readlink -f "$root/current" || true)"
if [[ ! -f "$previous/compose.yaml" ]]; then previous=$root; fi

compose() {
  local directory=$1
  shift
  local files=(-f "$directory/compose.yaml")
  if [[ -f "$directory/compose.image.yaml" ]]; then
    files+=(-f "$directory/compose.image.yaml")
    export FULLCOUNT_GAME_ARCHIVE=/data/kbo.tar.gz
  fi
  docker compose --project-name fullcount --project-directory "$directory" \
    --env-file "$root/.env" "${files[@]}" "$@"
}

release="$(mktemp -d "$root/releases/$revision.XXXXXX")"
docker_config="$(mktemp -d)"
container=
switch_started=false
success=false

cleanup() {
  local status=$?
  trap - EXIT
  if [[ -n "$container" ]]; then docker rm "$container" >/dev/null || true; fi
  rm -rf "$docker_config"
  if [[ "$success" != true ]]; then
    if [[ "$switch_started" == true ]]; then
      echo "Deploy failed. Restoring $previous."
      (unset FULLCOUNT_GAME_ARCHIVE; compose "$previous" up -d --no-build --pull never) || echo 'Rollback failed.'
    fi
    return "$status"
  fi
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

registry="${image%%/*}"
aws ecr get-login-password --region "$region" | docker --config "$docker_config" login --username AWS --password-stdin "$registry" >/dev/null
docker --config "$docker_config" pull "$image"
test "$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$image")" = "$revision"
container="$(docker create "$image")"
docker cp "$container:/release/." "$release/"
printf 'services:\n  api:\n    image: %s\n' "$image" > "$release/compose.image.yaml"
compose "$release" config --quiet

switch_started=true
compose "$release" up -d --no-build --pull missing
api_container="$(compose "$release" ps -q api)"
test "$(docker inspect --format '{{.Image}}' "$api_container")" = "$(docker image inspect --format '{{.Id}}' "$image")"
ready=false
for n in {1..45}; do
  if curl --fail --silent --max-time 3 http://127.0.0.1:8080/api/seasons/2024/standings |
       python3 -c 'import json,sys; rows=json.load(sys.stdin); assert isinstance(rows,list) and rows and "teamId" in rows[0]' 2>/dev/null; then
    ready=true
    break
  fi
  sleep 2
done
if [[ "$ready" != true ]]; then
  compose "$release" ps
  exit 1
fi

ln -s "$release" "$release/current-link"
mv -Tf "$release/current-link" "$root/current"
success=true
printf 'Deployed %s. Team standings returned valid JSON.\n' "$revision"
