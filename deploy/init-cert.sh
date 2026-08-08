#!/bin/sh
# Let's Encrypt 최초 발급. 서버의 ~/app 에서 한 번만 돌린다.
#
# nginx.conf가 인증서 경로를 참조하니 인증서가 없으면 nginx가 뜨지 못한다.
# 그래서 최초 발급만 nginx를 내리고 certbot이 직접 80을 잡는 standalone으로 받는다.
# 이후 갱신은 nginx를 띄운 채 webroot로 certbot 컨테이너가 알아서 한다.
set -eu

DOMAIN=88popo.kro.kr
EMAIL="${CERT_EMAIL:?CERT_EMAIL 환경변수를 지정해라}"
COMPOSE="docker compose -f compose.prod.yaml"
LIVE="/etc/letsencrypt/live/$DOMAIN"

if $COMPOSE run --rm --entrypoint sh certbot -c "[ -s $LIVE/fullchain.pem ]" 2>/dev/null; then
    echo "인증서가 이미 있다. 갱신은 certbot 컨테이너가 알아서 한다."
    exit 0
fi

echo "1/3 nginx 정지 (80 비우기)"
$COMPOSE stop nginx

echo "2/3 인증서 발급"
$COMPOSE run --rm -p 80:80 --entrypoint certbot certbot certonly --standalone \
    -d "$DOMAIN" --email "$EMAIL" --agree-tos --no-eff-email --non-interactive

echo "3/3 nginx 기동"
$COMPOSE up -d nginx

echo "완료: https://$DOMAIN"
