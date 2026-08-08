#!/bin/sh
# Let's Encrypt 최초 발급. 서버의 ~/app 에서 한 번만 돌린다.
#
# 닭과 달걀: nginx.conf가 인증서 경로를 참조하니 인증서가 없으면 nginx가 안 뜨고,
# nginx가 안 뜨면 ACME 챌린지를 못 받아 인증서를 못 만든다.
# 그래서 더미 인증서로 nginx를 먼저 띄우고, 진짜 인증서로 갈아끼운다.
set -eu

DOMAIN=88popo.kro.kr
EMAIL="${CERT_EMAIL:?CERT_EMAIL 환경변수를 지정해라}"
COMPOSE="docker compose -f compose.prod.yaml"
LIVE="/etc/letsencrypt/live/$DOMAIN"

if $COMPOSE run --rm --entrypoint sh certbot -c "[ -s $LIVE/fullchain.pem ]" 2>/dev/null; then
    echo "인증서가 이미 있다. 갱신은 certbot 컨테이너가 알아서 한다."
    exit 0
fi

echo "1/4 더미 인증서 생성"
$COMPOSE run --rm --entrypoint sh certbot -c "
    mkdir -p $LIVE &&
    openssl req -x509 -nodes -newkey rsa:2048 -days 1 \
        -keyout $LIVE/privkey.pem -out $LIVE/fullchain.pem -subj '/CN=$DOMAIN'"

echo "2/4 nginx 기동"
$COMPOSE up -d nginx

echo "3/4 더미 치우고 실제 발급"
$COMPOSE run --rm --entrypoint sh certbot -c "rm -rf $LIVE"
$COMPOSE run --rm certbot certonly --webroot -w /var/www/certbot \
    -d "$DOMAIN" --email "$EMAIL" --agree-tos --no-eff-email --non-interactive

echo "4/4 nginx reload"
$COMPOSE exec nginx nginx -s reload

echo "완료: https://$DOMAIN"
