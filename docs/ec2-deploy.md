# EC2 백엔드 배포

서울 EC2의 Elastic IP를 백엔드 HTTPS 주소로 사용한다. 프론트는 별도 Vercel 프로젝트에서 같은 사이트의 `/api/*`를 이 주소로 전달한다.

## 1. EC2 만들기

서울 리전의 퍼블릭 서브넷을 선택한다. 해당 서브넷의 라우팅 테이블에 인터넷 게이트웨이로 향하는 IPv4 기본 경로가 있어야 한다. 팀 AWS 계정에서 CloudFormation 스택을 만들고 [ec2.yaml](../deploy/ec2.yaml)을 업로드한다. VPC와 서브넷 ID를 입력하고 IAM 리소스 생성을 승인한다. 출력의 `InstanceId`, `PublicIp`, `DataVolumeId`를 기록한다. 스택은 서울 리전에서 만든다.

스택 완료 후 EC2 콘솔의 Session Manager로 접속한다. 인스턴스에 관리 에이전트가 표시되지 않으면 [Ubuntu용 SSM Agent](https://docs.aws.amazon.com/systems-manager/latest/userguide/agent-install-ubuntu-64-snap.html) 상태를 확인한다. [Docker 공식 Ubuntu 설치 절차](https://docs.docker.com/engine/install/ubuntu/)로 Engine과 Compose 플러그인을 설치한다. 호스트에 `nginx`와 `sqlite3`도 설치한다.

## 2. EBS와 실행 설정 준비

1. `lsblk -o NAME,SIZE,SERIAL,FSTYPE,MOUNTPOINTS`로 20GiB 디스크를 찾는다. SERIAL을 스택의 `DataVolumeId`와 대조한 뒤 **처음 연결한 빈 데이터 볼륨에만** ext4 파일시스템을 만든다. 기존 파일시스템이 있다면 그대로 사용한다. `sudo mkdir -p /srv/fullcount`로 마운트 경로를 만든다. `blkid`로 UUID를 확인하고 `/etc/fstab`에 `UUID=<UUID> /srv/fullcount ext4 defaults 0 2`를 추가한다. `sudo mount /srv/fullcount`와 `mountpoint /srv/fullcount`로 마운트를 확인한다.
2. Docker가 데이터 볼륨보다 먼저 시작하지 않도록 `sudo systemctl edit docker`에서 `[Unit]`과 `RequiresMountsFor=/srv/fullcount`를 추가한다. `sudo systemctl daemon-reload` 후 Docker를 재시작한다.
3. 배포할 백엔드 SHA의 소스를 `/opt/fullcount`에 배치한다. 팀의 비공개 저장소 접근이나 팀 AWS의 비공개 파일 전달 경로를 사용한다. `sudo install -d /srv/fullcount/data /srv/fullcount/ollama`로 저장 경로를 만들고 팀 공유 DB 스냅샷을 `/srv/fullcount/data/fullcount.db`에 배치한다. SQL 시연 파일은 아래 명령으로 따로 만들고, SQL 실행 컨테이너가 읽을 수 있게 파일 권한을 확인한다.

   ```bash
   sudo sqlite3 -readonly /srv/fullcount/data/fullcount.db ".backup /srv/fullcount/data/fullcount-demo.db"
   sudo chmod 644 /srv/fullcount/data/fullcount-demo.db
   ```

4. [관리자 비밀번호 해시 안내](admin-demo.md)에 따라 계정을 준비하고 `/opt/fullcount/.env`를 권한 `600`으로 저장한다. 필수값은 `FULLCOUNT_ADMIN_USERNAME`과 `FULLCOUNT_ADMIN_PASSWORD_HASH`다. `FULLCOUNT_DATA_DIR=/srv/fullcount/data`, `FULLCOUNT_OLLAMA_DIR=/srv/fullcount/ollama`, `FULLCOUNT_IMAGE_TAG=<백엔드 SHA>`도 지정한다. 이전 CLI·Swagger 도구를 사용할 경우에만 `FULLCOUNT_ADMIN_TOKEN`을 추가한다.
5. `/opt/fullcount`에서 `sudo docker compose config --quiet`, `sudo docker compose up -d --build`, `sudo docker compose ps`를 실행한다. 모델은 `sudo docker compose exec -T ollama ollama pull qwen2.5:1.5b`로 받고, `sudo docker compose exec -T ollama ollama run qwen2.5:1.5b '안녕'`으로 첫 실행을 확인한다. `curl --fail http://127.0.0.1:8080/api/seasons/2024/standings`로 DB 연결을 확인한다.

## 3. Elastic IP로 HTTPS 열기

1. `sudo install -d /var/www/fullcount-acme`를 실행한다. Nginx에서 80번 포트에 `/.well-known/acme-challenge/`를 열고 웹 루트를 `/var/www/fullcount-acme`로 지정한다. 이 단계에선 [Nginx 설정 예시](../deploy/fullcount.nginx.conf)의 첫 번째 `server` 블록만 설치하고, `EC2_PUBLIC_IP`는 스택 출력값으로 바꾼다. `sudo nginx -t` 후 리로드한다.
2. [Certbot 5.4 이상](https://letsencrypt.org/2026/03/11/shorter-certs-certbot)을 설치한다. `PUBLIC_IP`에 스택 출력값을 넣고 공인 IP 인증서를 발급한다.

   ```bash
   sudo certbot certonly --preferred-profile shortlived --webroot \
     --webroot-path /var/www/fullcount-acme --ip-address "$PUBLIC_IP" \
     --cert-name fullcount-ip --deploy-hook 'systemctl reload nginx'
   ```

3. 예시의 완성된 설정으로 교체하고 `sudo nginx -t` 후 리로드한다. 외부에서 `curl --fail "https://$PUBLIC_IP/api/seasons/2024/standings"`로 TLS와 API를 확인한다. 인증서 유효 기간은 약 6일이므로 자동 갱신 타이머와 `sudo certbot renew --dry-run`을 확인한다.
4. 프론트 담당자에게 `https://<Elastic IP>`와 검증할 프론트 SHA를 공유한다. Vercel Preview에서 `FULLCOUNT_API_ORIGIN=https://<Elastic IP>`, `VITE_USE_MOCK=false`를 설정한 뒤 배포한다.

## 4. 통합 검증과 원복

- Vercel Preview에서 선수 검색, 팀 순위, 경기 상세의 주요 이벤트를 확인한다.
- 상세 기록과 저장된 요약이 모두 비어 있는 아카이브 지원 경기를 골라 **요약 만들기**를 누른다. 상세와 주요 이벤트, 생성된 요약을 확인하고 새로고침해 저장된 값을 다시 확인한다. 생성 요청 시간을 기록한다. 실패하면 `sudo docker compose logs --tail 100 api game-detail summary ollama`로 타임아웃이나 데이터 오류가 발생한 구간을 찾는다.
- `/admin`에서 로그인, SQL 변경 결과, 원본 값 재조회, 로그아웃을 확인한다. [관리자 시연 순서](admin-demo.md)를 따른다.
- 발표 전 서비스 DB를 SQLite `.backup`으로 별도 보관한다. 컨테이너를 재생성하고 저장된 요약이 유지되는지 확인한다. 앱을 원복할 때는 이전 릴리스의 코드와 `FULLCOUNT_IMAGE_TAG`로 돌아간 뒤 `sudo docker compose up -d --no-build`를 실행한다. DB 백업은 별도 복원 판단이 있을 때만 사용한다.
- 발표가 끝나면 제출 기간에 맞춰 리소스를 정리한다. 스택 삭제 전 DB를 백업하고 서비스를 중지한 뒤 데이터 볼륨을 마운트 해제한다. 유지된 EBS와 할당된 Elastic IP는 별도로 확인한다.
