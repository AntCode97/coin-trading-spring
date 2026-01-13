#!/bin/bash
#
# Docker Hub 자동 빌드 및 푸시 스크립트 (Multi-Architecture 지원)
#
# 사용법:
#   ./docker-build-push.sh                     # amd64 빌드/푸시 (기본, NAS용)
#   ./docker-build-push.sh v1.0.0              # 특정 버전 태그
#   ./docker-build-push.sh --multi             # 멀티아키텍처 빌드 (arm64 + amd64)
#   ./docker-build-push.sh --native            # 로컬 플랫폼만 빌드 (빠른 테스트용)
#   ./docker-build-push.sh --no-cache          # 캐시 없이 빌드
#
# 환경 변수:
#   DOCKER_USERNAME: Docker Hub 사용자명 (기본: dbswns97)
#
# 참고:
#   - M1/M2/M3/M4 Mac에서 amd64 빌드는 QEMU 에뮬레이션으로 느림 (3-5분)
#   - 빠른 amd64 빌드가 필요하면 GitHub Actions 사용 권장
#

set -e

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# 설정
DOCKER_USERNAME="${DOCKER_USERNAME:-dbswns97}"
IMAGE_NAME="coin-trading-server"
TAG="latest"
BUILD_OPTS=""
PLATFORM="linux/amd64"  # 기본값: NAS 호환
USE_BUILDX=false

# 옵션 파싱
while [[ $# -gt 0 ]]; do
    case $1 in
        --multi)
            PLATFORM="linux/amd64,linux/arm64"
            USE_BUILDX=true
            shift
            ;;
        --native)
            PLATFORM=""  # 로컬 플랫폼 사용
            shift
            ;;
        --no-cache)
            BUILD_OPTS="--no-cache"
            shift
            ;;
        -h|--help)
            echo "사용법: $0 [옵션] [태그]"
            echo ""
            echo "옵션:"
            echo "  --multi      멀티아키텍처 빌드 (arm64 + amd64)"
            echo "  --native     로컬 플랫폼만 빌드 (빠른 테스트용)"
            echo "  --no-cache   캐시 없이 빌드"
            echo "  -h, --help   도움말 출력"
            echo ""
            echo "예시:"
            echo "  $0                # amd64 빌드 (NAS용)"
            echo "  $0 v1.0.0         # v1.0.0 태그로 빌드"
            echo "  $0 --multi        # arm64 + amd64 빌드"
            echo "  $0 --native       # 로컬 테스트용 빌드 (빠름)"
            exit 0
            ;;
        *)
            # 버전 태그로 간주
            if [[ $1 != -* ]]; then
                TAG="$1"
            fi
            shift
            ;;
    esac
done

echo -e "${GREEN}======================================${NC}"
echo -e "${GREEN} Docker Hub Build & Push Script${NC}"
echo -e "${GREEN}======================================${NC}"
echo ""
echo -e "Docker Username: ${YELLOW}${DOCKER_USERNAME}${NC}"
echo -e "Tag: ${YELLOW}${TAG}${NC}"
echo -e "Platform: ${YELLOW}${PLATFORM:-native}${NC}"
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

# Docker buildx 빌더 설정 (멀티아키텍처용)
if [[ "$USE_BUILDX" == "true" || -n "$PLATFORM" ]]; then
    echo ""
    echo -e "${YELLOW}[2.5/4] Docker buildx 빌더 설정...${NC}"

    # buildx 빌더가 없으면 생성
    if ! docker buildx inspect multiarch-builder &> /dev/null; then
        echo -e "${CYAN}멀티아키텍처 빌더 생성 중...${NC}"
        docker buildx create --name multiarch-builder --driver docker-container --use
    else
        docker buildx use multiarch-builder
    fi
    echo -e "${GREEN}buildx 빌더 준비 완료${NC}"
fi

# Docker 이미지 빌드 및 푸시
echo ""
echo -e "${YELLOW}[3/4] Docker 이미지 빌드...${NC}"

if [[ "$USE_BUILDX" == "true" ]]; then
    # 멀티아키텍처 빌드 (buildx 사용, 빌드와 푸시 동시에)
    echo -e "${CYAN}멀티아키텍처 빌드 중 (시간이 걸릴 수 있음)...${NC}"
    docker buildx build ${BUILD_OPTS} \
        --platform "${PLATFORM}" \
        -t ${DOCKER_USERNAME}/${IMAGE_NAME}:${TAG} \
        -t ${DOCKER_USERNAME}/${IMAGE_NAME}:latest \
        -f coin-trading-server/Dockerfile \
        --push \
        .
    echo -e "${GREEN}Docker 이미지 빌드 및 푸시 완료${NC}"
else
    # 단일 플랫폼 빌드
    PLATFORM_OPT=""
    if [[ -n "$PLATFORM" ]]; then
        PLATFORM_OPT="--platform ${PLATFORM}"
    fi

    docker build ${BUILD_OPTS} ${PLATFORM_OPT} \
        -t ${DOCKER_USERNAME}/${IMAGE_NAME}:${TAG} \
        -t ${DOCKER_USERNAME}/${IMAGE_NAME}:latest \
        -f coin-trading-server/Dockerfile .
    echo -e "${GREEN}Docker 이미지 빌드 완료${NC}"

    # Docker Hub 푸시
    echo ""
    echo -e "${YELLOW}[4/4] Docker Hub 푸시...${NC}"
    docker push ${DOCKER_USERNAME}/${IMAGE_NAME}:${TAG}
    if [[ "$TAG" != "latest" ]]; then
        docker push ${DOCKER_USERNAME}/${IMAGE_NAME}:latest
    fi
    echo -e "${GREEN}Docker Hub 푸시 완료${NC}"
fi

echo ""
echo -e "${GREEN}======================================${NC}"
echo -e "${GREEN} 빌드 및 푸시 완료!${NC}"
echo -e "${GREEN}======================================${NC}"
echo ""
echo "이미지: ${DOCKER_USERNAME}/${IMAGE_NAME}:${TAG}"
if [[ "$USE_BUILDX" == "true" ]]; then
    echo "플랫폼: ${PLATFORM}"
fi
echo ""
echo "배포 명령:"
echo "  docker pull ${DOCKER_USERNAME}/${IMAGE_NAME}:${TAG}"
echo ""
echo "또는 docker-compose로 실행:"
echo "  docker-compose up -d"
