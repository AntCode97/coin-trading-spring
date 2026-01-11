#!/bin/bash
#
# Docker Hub 자동 빌드 및 푸시 스크립트
#
# 사용법:
#   ./docker-build-push.sh                    # latest 태그로 빌드/푸시
#   ./docker-build-push.sh v1.0.0             # 특정 버전 태그로 빌드/푸시
#   ./docker-build-push.sh v1.0.0 --no-cache  # 캐시 없이 빌드
#
# 환경 변수:
#   DOCKER_USERNAME: Docker Hub 사용자명 (기본: dbswns97)
#

set -e

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 설정
DOCKER_USERNAME="${DOCKER_USERNAME:-dbswns97}"
TAG="${1:-latest}"
BUILD_OPTS=""

# --no-cache 옵션 처리
if [[ "$2" == "--no-cache" ]] || [[ "$1" == "--no-cache" ]]; then
    BUILD_OPTS="--no-cache"
    if [[ "$1" == "--no-cache" ]]; then
        TAG="latest"
    fi
fi

echo -e "${GREEN}======================================${NC}"
echo -e "${GREEN} Docker Hub Build & Push Script${NC}"
echo -e "${GREEN}======================================${NC}"
echo ""
echo -e "Docker Username: ${YELLOW}${DOCKER_USERNAME}${NC}"
echo -e "Tag: ${YELLOW}${TAG}${NC}"
echo -e "Build Options: ${YELLOW}${BUILD_OPTS:-none}${NC}"
echo ""

# Docker 로그인 확인 (credsStore 또는 auths 존재 여부)
echo -e "${YELLOW}[1/4] Docker Hub 로그인 확인...${NC}"
if ! grep -qE '"(credsStore|auths)"' ~/.docker/config.json 2>/dev/null; then
    echo -e "${RED}Docker Hub에 로그인되어 있지 않습니다.${NC}"
    echo -e "다음 명령으로 로그인하세요: docker login"
    exit 1
fi
echo -e "${GREEN}로그인 확인 완료${NC}"

# Gradle 빌드
echo ""
echo -e "${YELLOW}[2/4] Gradle 빌드...${NC}"
./gradlew :coin-trading-server:build -x test --no-daemon
echo -e "${GREEN}Gradle 빌드 완료${NC}"

# Docker 이미지 빌드
echo ""
echo -e "${YELLOW}[3/4] Docker 이미지 빌드...${NC}"
docker build ${BUILD_OPTS} \
    -t ${DOCKER_USERNAME}/coin-trading-server:${TAG} \
    -t ${DOCKER_USERNAME}/coin-trading-server:latest \
    -f coin-trading-server/Dockerfile .
echo -e "${GREEN}Docker 이미지 빌드 완료${NC}"

# Docker Hub 푸시
echo ""
echo -e "${YELLOW}[4/4] Docker Hub 푸시...${NC}"
docker push ${DOCKER_USERNAME}/coin-trading-server:${TAG}
if [[ "$TAG" != "latest" ]]; then
    docker push ${DOCKER_USERNAME}/coin-trading-server:latest
fi
echo -e "${GREEN}Docker Hub 푸시 완료${NC}"

echo ""
echo -e "${GREEN}======================================${NC}"
echo -e "${GREEN} 빌드 및 푸시 완료!${NC}"
echo -e "${GREEN}======================================${NC}"
echo ""
echo "이미지: ${DOCKER_USERNAME}/coin-trading-server:${TAG}"
echo ""
echo "배포 명령:"
echo "  docker pull ${DOCKER_USERNAME}/coin-trading-server:${TAG}"
echo ""
echo "또는 docker-compose로 실행:"
echo "  docker-compose up -d"
