# Oracle Cloud 무료 서버로 옮기기

PC에서 며칠 돌려 보고 기준이 마음에 들면 옮깁니다. 옮길 때 코드는 바꾸지 않습니다.

## 1. 서버 만들기 (사용자가 직접)

1. https://www.oracle.com/kr/cloud/free/ 에서 가입합니다. 카드 인증이 있지만 Always Free 범위 안에서는 요금이 나가지 않습니다.
2. 콘솔에서 **컴퓨트 → 인스턴스 → 인스턴스 생성**으로 들어갑니다.
   - 이미지: **Ubuntu 24.04**
   - 셰이프: `VM.Standard.A1.Flex`(ARM, 1 OCPU, 6GB) 또는 `VM.Standard.E2.1.Micro`. 둘 다 Always Free입니다.
   - "Out of capacity" 오류가 나면 다른 가용 도메인을 고르거나 잠시 뒤에 다시 시도합니다.
   - SSH 키: "키 쌍 생성"으로 개인 키를 내려받습니다(예: `oracle.key`).
3. 만들어진 인스턴스의 **공용 IP**를 적어 둡니다.

들어오는 포트는 열 필요가 없습니다. 봇이 텔레그램에 먼저 연결하는 방식이기 때문입니다.

## 2. 코드와 데이터 옮기기 (PC의 Git Bash에서)

PC에서 실행 중인 핫딜 모니터를 먼저 끕니다(Ctrl+C). 그다음 다음을 실행합니다.

```bash
cd ~/Desktop/ai연습하기/deal-monitor
IP=서버공용IP
KEY=~/Downloads/oracle.key
chmod 600 $KEY
ssh -i $KEY ubuntu@$IP "mkdir -p ~/deal-monitor/data"
scp -i $KEY -r monitor settings.yaml requirements.txt .env deploy ubuntu@$IP:~/deal-monitor/
scp -i $KEY data/monitor.db ubuntu@$IP:~/deal-monitor/data/
```

DB를 옮기므로 이미 본 글과 등록한 키워드가 그대로 이어집니다.

## 3. 서버에서 설치와 등록

```bash
ssh -i $KEY ubuntu@$IP
sudo apt update && sudo apt install -y python3-venv
cd ~/deal-monitor
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
sudo timedatectl set-timezone Asia/Seoul   # 로그 시각 보기 편하게 (동작에는 영향 없음)
sudo cp deploy/deal-monitor.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now deal-monitor
```

## 4. 확인

- 텔레그램에 `🟢 핫딜 모니터 시작`이 옵니다.
- `/상태`를 보내 소스가 ✅인지 봅니다.
- 서버 로그: `journalctl -u deal-monitor -f` 또는 `tail -f ~/deal-monitor/logs/monitor.log`

## 운영 명령

| 할 일 | 명령 |
| --- | --- |
| 재시작 (settings.yaml 수정 뒤) | `sudo systemctl restart deal-monitor` |
| 멈추기 | `sudo systemctl stop deal-monitor` |
| 코드 갱신 | PC에서 `scp -i $KEY -r monitor ubuntu@$IP:~/deal-monitor/` 후 재시작 |
